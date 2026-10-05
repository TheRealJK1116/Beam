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
                connection.soTimeout = READ_TIMEOUT.inWholeMilliseconds.toInt()
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
        val (proof, identity) = BeamFrames.decodeAuth(auth.body)
            ?: run {
                BeamFrames.write(output, BeamFrames.AUTH_FAIL)
                emit(TransferEvent.Failed(TransferError.AuthenticationFailed("Malformed identification")))
                return
            }
        if (!SessionAuth.verify(token, authContext(token), proof)) {
            BeamFrames.write(output, BeamFrames.AUTH_FAIL)
            emit(TransferEvent.Failed(TransferError.AuthenticationFailed()))
            return
        }

        // Only now is the claimed identity worth believing. Note this must come from the
        // sender: the token describes whoever minted it, which is this device.
        val peer = DeviceInfo(
            stableId = identity.stableId.ifBlank { "unverified" },
            displayName = identity.displayName.ifBlank { DeviceInfo.Unknown.displayName },
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

        // 3b. Tell the sender what we already have from an interrupted attempt, so it can
        // skip ahead instead of re-sending gigabytes over a link that already proved flaky.
        // Resolved once and held, rather than asked about now and reopened later: checking
        // then reopening is a race where the second look can disagree with the first, delete
        // the partial, and abort a transfer whose bytes have just been destroyed.
        val resumable = mutableMapOf<Int, PayloadSink.Resumed>()
        val held = mutableMapOf<Int, Long>()
        if (manifest.kind == PayloadManifest.Kind.FILES) {
            manifest.files.forEachIndexed { index, entry ->
                val partial = runCatching { sink.resume(entry, peer.stableId) }.getOrNull() ?: return@forEachIndexed
                if (partial.bytesHeld in 1 until entry.sizeBytes) {
                    resumable[index] = partial
                    held[index] = partial.bytesHeld
                } else {
                    // Useless to us; close it rather than leaving the stream open.
                    runCatching { partial.retain(partial.bytesHeld) }
                }
            }
        }
        BeamFrames.write(output, BeamFrames.RESUME_OFFER, BeamFrames.resumeOffer(held))
        if (held.isNotEmpty()) Log.i(TAG, "Resuming ${held.size} file(s): $held")

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
                    val header = BeamFrames.parseFileHeader(frame.body)
                        ?: throw IllegalStateException("bad file header")
                    val entry = manifest.files.getOrNull(header.index)
                        ?: throw IllegalStateException("file ${header.index} is not in the manifest")

                    emit(TransferEvent.FileStarted(header.index, entry.name, manifest.files.size))

                    val digest = BeamFrames.sha256()

                    // A non-zero offset means the sender took the resume we offered, which
                    // is already open and whose length we have already committed to.
                    val resumed = resumable.remove(header.index)
                    val destination = if (header.offset > 0L) {
                        if (resumed == null || resumed.bytesHeld != header.offset) {
                            // The sender invented an offset we never offered. Keep whatever
                            // we hold - it is not the partial's fault - and abort.
                            runCatching { resumed?.retain(resumed.bytesHeld) }
                            abort(output, "resume offset ${header.offset} was never offered")
                            throw IllegalStateException("cannot resume ${entry.name} from ${header.offset}")
                        }
                        // Fold the bytes already on disk into the digest, so the final
                        // comparison still covers the whole file rather than just this run.
                        resumed.openHeld().use { BeamFrames.digestExactly(it, header.offset, digest) }
                        resumed
                    } else {
                        // Sender chose to start over; the old partial is superseded.
                        runCatching { resumed?.discard() }
                        sink.create(entry, peer.stableId)
                    }

                    val alreadyDone = completedBytes
                    var writtenThisRun = 0L

                    val ok = runCatching {
                        destination.stream.use { out ->
                            BeamFrames.copyExactly(
                                input = input,
                                output = out,
                                count = header.remaining,
                                digest = digest,
                                onProgress = { moved ->
                                    // Throttled: a gigabyte at 64 KB a time is 16k callbacks.
                                    writtenThisRun = moved
                                    emitProgress(
                                        TransferEvent.Progress(
                                            alreadyDone + header.offset + moved,
                                            manifest.totalBytes,
                                        )
                                    )
                                },
                            )
                        }
                    }
                    if (ok.isFailure) {
                        // The bytes that did land are good; keep them for the next attempt
                        // rather than throwing away the whole file.
                        destination.retain(header.offset + writtenThisRun)
                        throw ok.exceptionOrNull()!!
                    }

                    // The sender's digest of the whole file follows the body.
                    val trailer = BeamFrames.read(input)
                    if (trailer?.type != BeamFrames.FILE_DIGEST) {
                        destination.retain(header.offset + writtenThisRun)
                        throw IllegalStateException("no digest followed ${entry.name}")
                    }
                    if (!trailer.body.contentEquals(digest.digest())) {
                        // Known-bad bytes: delete rather than retain, or every later resume
                        // would build on corruption.
                        destination.discard()
                        abort(output, "${entry.name} failed its integrity check")
                        releaseUnused(resumable)
                        emit(TransferEvent.Failed(TransferError.IntegrityFailed(entry.name)))
                        return
                    }

                    received += destination.commit()
                    completedBytes += header.sizeBytes
                    emit(TransferEvent.Progress(completedBytes, manifest.totalBytes))
                }

                else -> Unit
            }
        }

        // Anything we offered but the sender skipped stays on disk for another day.
        releaseUnused(resumable)

        // Everything is committed; release the sender.
        BeamFrames.write(output, BeamFrames.RECEIVED)

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
                // Without this the sender's withTimeoutOrNull guards are decorative:
                // BeamFrames.read blocks in a socket read, which coroutine cancellation
                // cannot interrupt, so a receiver that goes quiet would pin this thread -
                // and the wake lock, Wi-Fi lock and P2P group with it - indefinitely.
                connection.soTimeout = READ_TIMEOUT.inWholeMilliseconds.toInt()
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
        val me = localDevice()
        val proof = SessionAuth.proofFor(token, authContext(token))
        BeamFrames.write(
            output,
            BeamFrames.AUTH,
            BeamFrames.encodeAuth(proof, BeamFrames.Identity(me.stableId, me.displayName)),
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

        // 3b. What the receiver already holds, if anything.
        val resumeFrame = withTimeoutOrNull(HANDSHAKE_TIMEOUT) { BeamFrames.read(input) }
        val held = if (resumeFrame?.type == BeamFrames.RESUME_OFFER) {
            BeamFrames.parseResumeOffer(resumeFrame.body)
        } else {
            emptyMap()
        }
        if (held.isNotEmpty()) Log.i(TAG, "Receiver already holds $held")

        // 3. The bytes.
        val startedAt = System.currentTimeMillis()
        emit(TransferEvent.Started(manifest.totalBytes, token.device))

        var sent = 0L
        if (payload is BeamPayload.Files) {
            // A write failing mid-file usually means the receiver hung up; it may have told
            // us why first, so look before reporting a generic I/O error.
            try {
            payload.files.forEachIndexed { index, ref ->
                val entry = manifest.files[index]
                emit(TransferEvent.FileStarted(index, entry.name, payload.files.size))

                // Never trust the offer past the end of our own file.
                val offset = (held[index] ?: 0L).coerceIn(0L, entry.sizeBytes)
                BeamFrames.write(
                    output,
                    BeamFrames.FILE_HEADER,
                    BeamFrames.fileHeader(index, entry.sizeBytes, offset),
                )

                val alreadySent = sent
                val digest = BeamFrames.sha256()
                source.open(ref).use { fileStream ->
                    // The skipped prefix still has to be hashed - the trailer covers the whole
                    // file - but it is read from local disk rather than sent over the air,
                    // which is the entire point of resuming.
                    if (offset > 0) BeamFrames.digestExactly(fileStream, offset, digest)

                    BeamFrames.copyExactly(
                        input = fileStream,
                        output = output,
                        count = entry.sizeBytes - offset,
                        digest = digest,
                        onProgress = { moved ->
                            emitProgress(
                                TransferEvent.Progress(alreadySent + offset + moved, manifest.totalBytes)
                            )
                        },
                    )
                }
                // Digested on the way past, so the file is never read twice.
                BeamFrames.write(output, BeamFrames.FILE_DIGEST, digest.digest())

                sent += entry.sizeBytes
                emit(TransferEvent.Progress(sent, manifest.totalBytes))
            }
            } catch (io: java.io.IOException) {
                val reason = pendingAbort(input)
                emit(TransferEvent.Failed(reason ?: TransferError.Io(io.message ?: "The transfer was cut off")))
                return
            }
        }

        BeamFrames.write(output, BeamFrames.DONE)

        // Block until the receiver says it has everything. Closing before this truncates the
        // transfer, and timing the beam without it measures the local socket buffer.
        val confirmation = withTimeoutOrNull(DELIVERY_TIMEOUT) {
            runCatching { BeamFrames.read(input) }.getOrNull()
        }
        if (confirmation?.type == BeamFrames.ABORT) {
            emit(TransferEvent.Failed(abortReason(confirmation.body)))
            return
        }
        if (confirmation?.type != BeamFrames.RECEIVED) {
            emit(TransferEvent.Failed(TransferError.Io("The other phone did not confirm it received everything")))
            return
        }
        emit(TransferEvent.Completed(System.currentTimeMillis() - startedAt))
    }

    /**
     * Drains whatever the receiver managed to say before hanging up.
     *
     * When a file fails its integrity check mid-folder the sender is writing, not reading, so
     * the abort sits unread in the socket buffer until the write fails. Reading it here turns
     * "the connection broke" into the reason the receiver actually gave.
     */
    private fun pendingAbort(input: java.io.InputStream): TransferError? = runCatching {
        var frame = BeamFrames.read(input)
        var guard = 0
        while (frame != null && frame.type != BeamFrames.ABORT && guard++ < 8) {
            frame = BeamFrames.read(input)
        }
        frame?.takeIf { it.type == BeamFrames.ABORT }?.let { abortReason(it.body) }
    }.getOrNull()

    private fun abortReason(body: ByteArray): TransferError {
        val text = body.toString(Charsets.UTF_8).filter { !it.isISOControl() }.take(120)
        return if (text.contains("integrity")) {
            TransferError.IntegrityFailed(text.substringBefore(" failed").ifBlank { "A file" })
        } else {
            TransferError.Io(text.ifBlank { "The other phone stopped the transfer" })
        }
    }

    /** Tells the peer why we are stopping, so it reports the real reason rather than a timeout. */
    private fun abort(output: java.io.OutputStream, reason: String) {
        runCatching { BeamFrames.write(output, BeamFrames.ABORT, reason.take(200).toByteArray(Charsets.UTF_8)) }
    }

    /** Closes resumed partials we opened but never used, keeping their bytes. */
    private suspend fun releaseUnused(resumable: MutableMap<Int, PayloadSink.Resumed>) {
        resumable.values.forEach { runCatching { it.retain(it.bytesHeld) } }
        resumable.clear()
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
        val HANDSHAKE_TIMEOUT: Duration = 15.seconds

        /** How long the sender waits for the receiver to confirm after the last byte. */
        val DELIVERY_TIMEOUT: Duration = 60.seconds

        /** Guards against a peer that connects and then goes quiet mid-transfer. */
        val READ_TIMEOUT: Duration = 60.seconds
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
