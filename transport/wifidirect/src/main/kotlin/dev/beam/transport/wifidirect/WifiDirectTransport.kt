package dev.beam.transport.wifidirect

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import dev.beam.core.model.BeamPayload
import dev.beam.core.model.DeviceInfo
import dev.beam.core.model.PayloadManifest
import dev.beam.core.model.TransferError
import dev.beam.core.model.TransferEvent
import dev.beam.core.model.TransportId
import dev.beam.core.session.SessionAuth
import dev.beam.core.session.SessionToken
import dev.beam.core.transport.BeamFrames
import dev.beam.core.transport.PayloadSink
import dev.beam.core.transport.PayloadSource
import dev.beam.core.transport.Transport
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Wi-Fi Direct transport. No Play Services, no Bluetooth, no pairing dialog.
 *
 * Nearby Connections always raises its control channel over Bluetooth or BLE, which makes
 * Android show a pairing dialog — the precise ceremony a tap exists to abolish. This backend
 * avoids the problem by not needing discovery at all: the NFC tap already carried a nonce and
 * a secret, both sides derive the same [GroupCredentials] from them, the receiver creates a
 * group with exactly those credentials and the sender joins a network whose name and
 * passphrase it already knows.
 *
 * The receiver is always the group owner and therefore the server; the sender connects to it
 * on [PORT]. The payload is protected by WPA2 using a key derived from the tap, so unlike the
 * Nearby path there is no reliance on a third party for confidentiality. The frame-level
 * proof-of-tap is still exchanged, because a correct implementation should not depend on the
 * link layer alone.
 */
class WifiDirectTransport(
    context: Context,
    private val localDevice: () -> DeviceInfo,
) : Transport {

    private val appContext = context.applicationContext
    private val manager: WifiP2pManager? =
        appContext.getSystemService(WifiP2pManager::class.java)

    override val id: TransportId = TransportId.WIFI_DIRECT

    override suspend fun isAvailable(): Boolean =
        manager != null &&
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) &&
            // Creating a group with explicit credentials, which is what removes the dialog,
            // arrived in Android 10.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    // ---------------------------------------------------------------- receiver

    override fun receive(
        token: SessionToken,
        sink: PayloadSink,
        decide: suspend (PayloadManifest, DeviceInfo) -> Boolean,
    ): Flow<TransferEvent> = channelFlow {
        val manager = manager ?: run {
            send(unavailable("Wi-Fi Direct is not supported on this device"))
            return@channelFlow
        }
        send(TransferEvent.Discovering)

        val credentials = GroupCredentials.of(token)
        val channel = manager.initialize(appContext, Looper.getMainLooper(), null)
        var server: ServerSocket? = null

        try {
            // A leftover group from an abandoned beam would block this one.
            manager.removeGroupQuietly(channel)

            if (!manager.createGroup(channel, credentials)) {
                send(unavailable("Could not create a Wi-Fi Direct group"))
                return@channelFlow
            }
            Log.i(TAG, "Group ${credentials.networkName} up; listening on $PORT")

            server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
                soTimeout = CONNECT_TIMEOUT.inWholeMilliseconds.toInt()
            }

            send(TransferEvent.Connecting)

            val socket = runCatching { server.accept() }.getOrNull()
            if (socket == null) {
                send(TransferEvent.Failed(TransferError.DiscoveryTimeout("The other phone did not join")))
                return@channelFlow
            }

            socket.use { connection ->
                connection.tcpNoDelay = true
                receiveOver(connection, token, sink, decide, { send(it) }, { trySend(it) })
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Receive failed", t)
            send(TransferEvent.Failed(TransferError.Io(t.message ?: "Wi-Fi Direct receive failed")))
        } finally {
            runCatching { server?.close() }
            manager.removeGroupQuietly(channel)
            channel.closeQuietly()
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun receiveOver(
        socket: Socket,
        token: SessionToken,
        sink: PayloadSink,
        decide: suspend (PayloadManifest, DeviceInfo) -> Boolean,
        emit: suspend (TransferEvent) -> Unit,
        emitProgress: (TransferEvent) -> Unit,
    ) {
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())

        // 1. Proof of tap.
        val auth = BeamFrames.read(input)
        if (auth == null || auth.type != BeamFrames.AUTH) {
            emit(TransferEvent.Failed(TransferError.AuthenticationFailed("The other phone did not identify itself")))
            return
        }
        val proof = auth.body.copyOfRange(0, minOf(BeamFrames.PROOF_BYTES, auth.body.size))
        if (!SessionAuth.verify(token, authContext(token), proof)) {
            BeamFrames.write(output, BeamFrames.AUTH_FAIL)
            emit(TransferEvent.Failed(TransferError.AuthenticationFailed()))
            return
        }
        val peer = DeviceInfo(
            stableId = auth.body.drop(BeamFrames.PROOF_BYTES).toByteArray()
                .toString(Charsets.UTF_8)
                .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                .ifBlank { "unverified" },
            displayName = token.device.displayName,
        )
        BeamFrames.write(output, BeamFrames.AUTH_OK)

        // 2. What is coming.
        val manifestFrame = BeamFrames.read(input)
        if (manifestFrame == null || manifestFrame.type != BeamFrames.MANIFEST) {
            emit(TransferEvent.Failed(TransferError.MalformedToken("No manifest arrived")))
            return
        }
        val manifest = PayloadManifest.decode(manifestFrame.body).getOrElse {
            emit(TransferEvent.Failed(TransferError.MalformedToken("Unreadable manifest")))
            return
        }

        // 3. The user's answer.
        emit(TransferEvent.ManifestOffered(manifest, peer))
        if (!decide(manifest, peer)) {
            BeamFrames.write(output, BeamFrames.DECLINE)
            emit(TransferEvent.Failed(TransferError.Rejected()))
            return
        }
        BeamFrames.write(output, BeamFrames.ACCEPT)

        // 4. The bytes.
        val startedAt = System.currentTimeMillis()
        emit(TransferEvent.Started(manifest.totalBytes, peer))

        val received = mutableListOf<String>()
        var completedBytes = 0L

        if (manifest.kind != PayloadManifest.Kind.FILES) {
            received += sink.acceptText(manifest, manifest.text.orEmpty())
            completedBytes = manifest.totalBytes
            emit(TransferEvent.Progress(completedBytes, manifest.totalBytes))
        }

        while (true) {
            val frame = BeamFrames.read(input) ?: break
            when (frame.type) {
                BeamFrames.DONE -> break

                BeamFrames.FILE_HEADER -> {
                    val (index, size) = BeamFrames.parseFileHeader(frame.body)
                        ?: throw IllegalStateException("bad file header")
                    val entry = manifest.files.getOrNull(index)
                        ?: throw IllegalStateException("file $index is not in the manifest")

                    val destination = sink.create(entry)
                    val alreadyDone = completedBytes
                    val ok = runCatching {
                        destination.stream.use { out ->
                            BeamFrames.copyExactly(input, out, size) { moved ->
                                emitProgress(TransferEvent.Progress(alreadyDone + moved, manifest.totalBytes))
                            }
                        }
                    }
                    if (ok.isFailure) {
                        destination.discard()
                        throw ok.exceptionOrNull()!!
                    }
                    received += destination.commit()
                    completedBytes += size
                    emit(TransferEvent.Progress(completedBytes, manifest.totalBytes))
                }

                else -> Unit
            }
        }

        emit(
            TransferEvent.Completed(
                durationMillis = System.currentTimeMillis() - startedAt,
                receivedUris = received,
            )
        )
    }

    // ------------------------------------------------------------------ sender

    override fun send(
        token: SessionToken,
        payload: BeamPayload,
        source: PayloadSource,
    ): Flow<TransferEvent> = channelFlow {
        val manager = manager ?: run {
            send(unavailable("Wi-Fi Direct is not supported on this device"))
            return@channelFlow
        }
        send(TransferEvent.Discovering)

        val credentials = GroupCredentials.of(token)
        val channel = manager.initialize(appContext, Looper.getMainLooper(), null)

        try {
            manager.removeGroupQuietly(channel)

            // No discovery: we know the network because the tap told us.
            if (!manager.joinGroup(channel, credentials)) {
                send(unavailable("Could not join the other phone's Wi-Fi Direct group"))
                return@channelFlow
            }
            send(TransferEvent.Connecting)

            val socket = connectWithRetries(manager, channel)
            if (socket == null) {
                send(TransferEvent.Failed(TransferError.DiscoveryTimeout("Could not reach the other phone")))
                return@channelFlow
            }

            socket.use { connection ->
                connection.tcpNoDelay = true
                sendOver(connection, token, payload, source, { send(it) }, { trySend(it) })
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Send failed", t)
            send(TransferEvent.Failed(TransferError.Io(t.message ?: "Wi-Fi Direct send failed")))
        } finally {
            manager.removeGroupQuietly(channel)
            channel.closeQuietly()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * The group owner's socket is not up the instant the group forms, so this retries until
     * the deadline rather than failing on the first refusal.
     */
    private suspend fun connectWithRetries(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
    ): Socket? {
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT.inWholeMilliseconds
        var ownerAddress: String? = null

        while (System.currentTimeMillis() < deadline) {
            if (ownerAddress == null) {
                ownerAddress = manager.groupOwnerAddress(channel)
            }
            val host = ownerAddress ?: DEFAULT_GROUP_OWNER
            val attempt = runCatching {
                Socket().apply {
                    connect(InetSocketAddress(host, PORT), SOCKET_ATTEMPT_MILLIS)
                }
            }
            attempt.getOrNull()?.let { return it }
            delay(RETRY_INTERVAL_MILLIS)
        }
        return null
    }

    private suspend fun sendOver(
        socket: Socket,
        token: SessionToken,
        payload: BeamPayload,
        source: PayloadSource,
        emit: suspend (TransferEvent) -> Unit,
        emitProgress: (TransferEvent) -> Unit,
    ) {
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        val manifest = PayloadManifest.of(payload)

        // 1. Prove the tap, and say who we are.
        val proof = SessionAuth.proofFor(token, authContext(token))
        BeamFrames.write(
            output,
            BeamFrames.AUTH,
            proof + localDevice().stableId.take(24).toByteArray(Charsets.UTF_8),
        )
        val authReply = BeamFrames.read(input)
        if (authReply?.type != BeamFrames.AUTH_OK) {
            emit(TransferEvent.Failed(TransferError.AuthenticationFailed()))
            return
        }

        // 2. Declare the payload and wait for the human.
        BeamFrames.write(output, BeamFrames.MANIFEST, manifest.encode())
        val answer = withTimeoutOrNull(ACCEPT_TIMEOUT) { BeamFrames.read(input) }
        if (answer?.type != BeamFrames.ACCEPT) {
            emit(TransferEvent.Failed(TransferError.Rejected()))
            return
        }

        // 3. The bytes.
        val startedAt = System.currentTimeMillis()
        emit(TransferEvent.Started(manifest.totalBytes, token.device))

        var sent = 0L
        if (payload is BeamPayload.Files) {
            payload.files.forEachIndexed { index, ref ->
                val entry = manifest.files[index]
                BeamFrames.write(output, BeamFrames.FILE_HEADER, BeamFrames.fileHeader(index, entry.sizeBytes))
                val alreadySent = sent
                source.open(ref).use { fileStream ->
                    BeamFrames.copyExactly(fileStream, output, entry.sizeBytes) { moved ->
                        emitProgress(TransferEvent.Progress(alreadySent + moved, manifest.totalBytes))
                    }
                }
                sent += entry.sizeBytes
                emit(TransferEvent.Progress(sent, manifest.totalBytes))
            }
        }

        BeamFrames.write(output, BeamFrames.DONE)
        emit(TransferEvent.Completed(System.currentTimeMillis() - startedAt))
    }

    private fun unavailable(reason: String) = TransferEvent.Failed(
        TransferError.TransportUnavailable(TransportId.WIFI_DIRECT, reason)
    )

    private companion object {
        const val TAG = "BeamWifiDirect"

        /** The group owner always takes this address on Android's P2P implementation. */
        const val DEFAULT_GROUP_OWNER = "192.168.49.1"
        const val PORT = 8988

        val CONNECT_TIMEOUT: Duration = 20.seconds
        val ACCEPT_TIMEOUT: Duration = 30.seconds
        const val SOCKET_ATTEMPT_MILLIS = 1_500
        const val RETRY_INTERVAL_MILLIS = 400L

        /**
         * Binds the proof to this session. Unlike the Nearby path there are no connection
         * digits to mix in, but the WPA2 key is itself derived from the tap, so only a device
         * that tapped can be on this network at all.
         */
        fun authContext(token: SessionToken): String = token.advertisingName
    }
}
