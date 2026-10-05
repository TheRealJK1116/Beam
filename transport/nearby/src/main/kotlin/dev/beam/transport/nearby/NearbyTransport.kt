package dev.beam.transport.nearby

import android.content.Context
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionOptions
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionType
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Nearby Connections backend.
 *
 * Nearby handles radio selection (it will use Wi-Fi Direct, Wi-Fi Aware, hotspot or Bluetooth
 * depending on what the two devices can agree on) and encrypts the channel. What it does not do
 * is tell us *which* nearby device the user physically tapped — that is what the NFC session
 * token is for: the receiver advertises under the token's nonce and the sender proves, inside
 * the connection, that it knows the secret that only crossed the 1 cm NFC gap.
 *
 * Everything Nearby-specific stops at this file; the rest of the app sees only [Transport].
 */
class NearbyTransport(
    context: Context,
    private val localDevice: () -> DeviceInfo,
    private val timeouts: Timeouts = Timeouts(),
) : Transport {

    data class Timeouts(
        val discovery: Duration = 10.seconds,
        val handshake: Duration = 8.seconds,
        val acceptance: Duration = 30.seconds,
    )

    private val appContext = context.applicationContext
    private val client by lazy { Nearby.getConnectionsClient(appContext) }

    override val id: TransportId = TransportId.NEARBY

    override suspend fun isAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) ==
            ConnectionResult.SUCCESS

    // ---------------------------------------------------------------- receiver

    override fun receive(
        token: SessionToken,
        sink: PayloadSink,
        decide: suspend (PayloadManifest, DeviceInfo) -> Boolean,
    ): Flow<TransferEvent> = callbackFlow {
        val session = ReceiveSession(this, token, sink, decide, client, timeouts)
        trySend(TransferEvent.Discovering)

        suspend fun advertise() = client.startAdvertising(
            token.advertisingName,
            SERVICE_ID,
            session.connectionCallback,
            advertisingOptions(),
        ).await()

        val started = runCatching { advertise() }.recoverCatching { failure ->
            // An advertisement left running by an earlier session - a process that died
            // mid-beam, say - makes Play Services refuse the new one outright. Clear it and
            // try once more rather than leaving the user permanently unable to receive.
            if (NearbyErrors.statusCodeOf(failure) == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING) {
                Log.w(NearbyErrors.TAG, "Stale advertisement found; stopping it and retrying")
                client.stopAdvertising()
                advertise()
            } else {
                throw failure
            }
        }
        if (started.isFailure) {
            trySend(
                TransferEvent.Failed(
                    NearbyErrors.toTransferError("startAdvertising", started.exceptionOrNull())
                )
            )
            close()
            return@callbackFlow
        }

        awaitClose {
            client.stopAdvertising()
            client.stopAllEndpoints()
        }
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------------ sender

    override fun send(
        token: SessionToken,
        payload: BeamPayload,
        source: PayloadSource,
    ): Flow<TransferEvent> = callbackFlow {
        val session = SendSession(this, token, payload, source, client, localDevice(), timeouts)
        trySend(TransferEvent.Discovering)

        val started = runCatching {
            client.startDiscovery(
                SERVICE_ID,
                session.discoveryCallback,
                DiscoveryOptions.Builder().setStrategy(STRATEGY).build(),
            ).await()
        }
        if (started.isFailure) {
            trySend(
                TransferEvent.Failed(
                    NearbyErrors.toTransferError("startDiscovery", started.exceptionOrNull())
                )
            )
            close()
            return@callbackFlow
        }

        // Nothing found in time is the single most common real-world failure, so it gets an
        // explicit deadline rather than an indefinite scan.
        launch {
            if (withTimeoutOrNull(timeouts.discovery) { session.endpointFound.await() } == null) {
                trySend(TransferEvent.Failed(TransferError.DiscoveryTimeout()))
                close()
            }
        }

        awaitClose {
            client.stopDiscovery()
            client.stopAllEndpoints()
        }
    }.flowOn(Dispatchers.IO)

    internal companion object {
        /** Bumping this is how an incompatible wire change is kept from pairing. */
        const val SERVICE_ID = "dev.beam.transfer.v1"

        /**
         * P2P_CLUSTER, not P2P_POINT_TO_POINT.
         *
         * POINT_TO_POINT offers the highest bandwidth but establishes its control channel over
         * Bluetooth/BLE only — which makes Android show a Bluetooth pairing dialog, the exact
         * ceremony a tap exists to replace, and fails outright if Bluetooth cannot connect.
         * CLUSTER can bring the connection up over Wi-Fi LAN directly. Beam only ever has two
         * devices, so the cluster topology costs nothing.
         */
        val STRATEGY: Strategy = Strategy.P2P_CLUSTER

        /**
         * Push Nearby towards a direct Wi-Fi link rather than Bluetooth.
         *
         * Left to its own judgement, Nearby may settle on a Bluetooth medium for the payload,
         * which on some devices makes the system show a Bluetooth pairing dialog — exactly the
         * ceremony a tap is supposed to replace. DISRUPTIVE permits it to drop the current
         * Wi-Fi association to form a direct link, and the disruptive upgrade lets it move the
         * payload onto that link once connected.
         *
         * The cost is honest: Wi-Fi may blink out for the duration of a transfer. For a beam
         * that should last a few seconds, and which is far faster over Wi-Fi than Bluetooth,
         * that is the right trade.
         */
        fun advertisingOptions(): AdvertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(STRATEGY)
            .setConnectionType(ConnectionType.DISRUPTIVE)
            .setDisruptiveUpgrade(true)
            .build()

        fun connectionOptions(): ConnectionOptions = ConnectionOptions.Builder()
            .setConnectionType(ConnectionType.DISRUPTIVE)
            .setDisruptiveUpgrade(true)
            .build()

        /**
         * Binds the proof-of-tap to this specific Nearby connection.
         *
         * Nearby derives [ConnectionInfo.getAuthenticationDigits] from the key exchange, so both
         * ends of one connection see the same value and no other connection shares it. MAC-ing
         * it means a proof captured off the air cannot be replayed into a different connection.
         */
        fun authContext(token: SessionToken, digits: String): String =
            "${token.advertisingName}|$digits"
    }
}

// --------------------------------------------------------------------- receiver

private class ReceiveSession(
    private val scope: kotlinx.coroutines.channels.ProducerScope<TransferEvent>,
    private val token: SessionToken,
    private val sink: PayloadSink,
    private val decide: suspend (PayloadManifest, DeviceInfo) -> Boolean,
    private val client: com.google.android.gms.nearby.connection.ConnectionsClient,
    private val timeouts: NearbyTransport.Timeouts,
) {
    private var peer: DeviceInfo = DeviceInfo.Unknown
    private var authDigits: String = ""
    private var manifest: PayloadManifest? = null
    private var pendingFileIndex: Int = -1
    private var bytesCompleted: Long = 0
    private var startedAt: Long = 0
    private val committedUris = mutableListOf<String>()
    private val destinations = ConcurrentHashMap<Long, PayloadSink.Destination>()
    private val payloadIndex = ConcurrentHashMap<Long, Int>()

    /**
     * The digest the sender promised for each file, which may arrive before or after the
     * bytes it describes.
     *
     * Unlike a socket, Nearby hands BYTES frames and STREAM payloads to different callbacks
     * with no ordering guarantee between them, so the stream handler waits on this rather
     * than assuming a trailer has already landed.
     */
    private val expectedDigests = ConcurrentHashMap<Int, CompletableDeferred<ByteArray>>()

    /** Resumes resolved up front, so the offer and the reopen cannot disagree. */
    private val resumable = ConcurrentHashMap<Int, PayloadSink.Resumed>()

    private val headers = ConcurrentHashMap<Int, BeamFrames.FileHeader>()

    private fun digestSlot(index: Int): CompletableDeferred<ByteArray> =
        expectedDigests.getOrPut(index) { CompletableDeferred() }

    val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            authDigits = info.authenticationDigits
            // Only the display name is known at this point. The sender's durable identity
            // arrives in the AUTH frame, once it has proved it is entitled to claim one.
            peer = DeviceInfo(stableId = PENDING_PEER_ID, displayName = info.endpointName.sanitizedPeerName())
            scope.trySend(TransferEvent.Connecting)
            // Accept at the Nearby level; the real gate is the proof-of-tap frame below.
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.statusCode != ConnectionsStatusCodes.STATUS_OK) {
                scope.trySend(
                    TransferEvent.Failed(
                        NearbyErrors.fromStatus("receiver onConnectionResult", resolution.status.statusCode)
                    )
                )
                scope.close()
                return
            }
            // Only one peer per tap: stop advertising so a second device cannot join.
            client.stopAdvertising()
            startedAt = System.currentTimeMillis()
        }

        override fun onDisconnected(endpointId: String) {
            // A disconnect after we have emitted Completed is the normal teardown.
            scope.trySend(TransferEvent.Failed(TransferError.Io("The other device disconnected")))
            scope.close()
        }
    }

    val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> handleControlFrame(endpointId, payload.asBytes() ?: return)
                Payload.Type.STREAM -> handleIncomingStream(payload)
                else -> Unit
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val total = manifest?.totalBytes ?: return
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS ->
                    if (payloadIndex.containsKey(update.payloadId)) {
                        scope.trySend(
                            TransferEvent.Progress(
                                (bytesCompleted + update.bytesTransferred).coerceAtMost(total),
                                total,
                            )
                        )
                    }

                PayloadTransferUpdate.Status.FAILURE ->
                    if (payloadIndex.containsKey(update.payloadId)) {
                        scope.launch { abort(endpointId, TransferError.Io("A file failed in transit")) }
                    }

                else -> Unit
            }
        }
    }

    private fun handleControlFrame(endpointId: String, frame: ByteArray) {
        when (BeamFrames.typeOf(frame)) {
            BeamFrames.AUTH -> verifyProof(endpointId, BeamFrames.bodyOf(frame))
            BeamFrames.MANIFEST -> receiveManifest(endpointId, BeamFrames.bodyOf(frame))
            BeamFrames.FILE_HEADER -> BeamFrames.parseFileHeader(BeamFrames.bodyOf(frame))?.let {
                headers[it.index] = it
                pendingFileIndex = it.index
            }

            BeamFrames.FILE_DIGEST -> BeamFrames.parseIndexedDigest(BeamFrames.bodyOf(frame))
                ?.let { (index, digest) -> digestSlot(index).complete(digest) }
            BeamFrames.DONE -> finish()
            else -> Unit
        }
    }

    /**
     * The AUTH frame is `proof ‖ senderStableId`. The identity rides along with the proof, and
     * is only adopted once the proof checks out — otherwise any passing device could claim to
     * be one the user has already trusted and skip the confirmation.
     */
    private fun verifyProof(endpointId: String, body: ByteArray) {
        val decoded = BeamFrames.decodeAuth(body)
        val proof = decoded?.first ?: ByteArray(0)
        val claimed = decoded?.second

        val expectedContext = NearbyTransport.authContext(token, authDigits)
        if (SessionAuth.verify(token, expectedContext, proof)) {
            claimed?.let {
                if (it.stableId.isNotBlank()) peer = peer.copy(stableId = it.stableId)
                // The endpoint name is already the sender's, so only override if it gave one.
                if (it.displayName.isNotBlank()) peer = peer.copy(displayName = it.displayName)
            }
            client.sendPayload(endpointId, Payload.fromBytes(BeamFrames.of(BeamFrames.AUTH_OK)))
        } else {
            client.sendPayload(endpointId, Payload.fromBytes(BeamFrames.of(BeamFrames.AUTH_FAIL)))
            scope.launch { abort(endpointId, TransferError.AuthenticationFailed()) }
        }
    }

    private fun receiveManifest(endpointId: String, body: ByteArray) {
        val decoded = PayloadManifest.decode(body).getOrElse {
            scope.launch { abort(endpointId, TransferError.MalformedToken("Unreadable manifest")) }
            return
        }
        manifest = decoded
        scope.trySend(TransferEvent.ManifestOffered(decoded, peer))

        scope.launch {
            val accepted = withTimeoutOrNull(timeouts.acceptance) { decide(decoded, peer) } ?: false
            if (accepted) {
                client.sendPayload(endpointId, Payload.fromBytes(BeamFrames.of(BeamFrames.ACCEPT)))

                // Tell the sender what survived an earlier attempt so it can skip ahead.
                val held = mutableMapOf<Int, Long>()
                if (decoded.kind == PayloadManifest.Kind.FILES) {
                    decoded.files.forEachIndexed { index, entry ->
                        val partial = runCatching { sink.resume(entry, peer.stableId) }.getOrNull()
                            ?: return@forEachIndexed
                        if (partial.bytesHeld in 1 until entry.sizeBytes) {
                            resumable[index] = partial
                            held[index] = partial.bytesHeld
                        } else {
                            runCatching { partial.retain(partial.bytesHeld) }
                        }
                    }
                }
                client.sendPayload(
                    endpointId,
                    Payload.fromBytes(BeamFrames.of(BeamFrames.RESUME_OFFER, BeamFrames.resumeOffer(held))),
                )

                startedAt = System.currentTimeMillis()
                scope.trySend(TransferEvent.Started(decoded.totalBytes, peer))
                if (decoded.kind != PayloadManifest.Kind.FILES) {
                    committedUris += sink.acceptText(decoded, decoded.text.orEmpty())
                }
            } else {
                client.sendPayload(endpointId, Payload.fromBytes(BeamFrames.of(BeamFrames.DECLINE)))
                abort(endpointId, TransferError.Rejected())
            }
        }
    }

    private fun handleIncomingStream(payload: Payload) {
        val manifest = manifest ?: return
        val index = pendingFileIndex
        val entry = manifest.files.getOrNull(index) ?: return
        payloadIndex[payload.id] = index

        val header = headers[index] ?: BeamFrames.FileHeader(index, entry.sizeBytes, 0L)
        val resumed = resumable.remove(index)

        scope.launch(Dispatchers.IO) {
            scope.trySend(TransferEvent.FileStarted(index, entry.name, manifest.files.size))

            val digest = BeamFrames.sha256()
            val destination = if (header.offset > 0L && resumed != null && resumed.bytesHeld == header.offset) {
                // Fold what is already on disk into the hash, so the comparison covers the
                // whole file rather than only the bytes from this attempt.
                runCatching { resumed.openHeld().use { BeamFrames.digestExactly(it, header.offset, digest) } }
                resumed
            } else {
                runCatching { resumed?.discard() }
                sink.create(entry, peer.stableId)
            }
            destinations[payload.id] = destination

            var written = 0L
            val result = runCatching {
                payload.asStream()?.asInputStream()?.use { input: InputStream ->
                    destination.stream.use { output ->
                        BeamFrames.copyExactly(
                            input = input,
                            output = output,
                            count = header.remaining,
                            digest = digest,
                            onProgress = { moved -> written = moved },
                        )
                    }
                } ?: error("stream payload had no stream")
            }

            if (result.isFailure) {
                // The bytes that arrived are good; keep them so the next attempt resumes.
                destination.retain(header.offset + written)
                scope.trySend(TransferEvent.Failed(TransferError.Io("Could not save ${entry.name}")))
                scope.close()
                return@launch
            }

            // Nearby may hand us the digest frame before or after the body.
            val expected = withTimeoutOrNull(DIGEST_TIMEOUT) { digestSlot(index).await() }
            if (expected == null || !expected.contentEquals(digest.digest())) {
                // Known-bad bytes: delete rather than retain, or every later resume would
                // build on corruption.
                destination.discard()
                scope.trySend(TransferEvent.Failed(TransferError.IntegrityFailed(entry.name)))
                scope.close()
                return@launch
            }

            committedUris += destination.commit()
            bytesCompleted += entry.sizeBytes
            scope.trySend(TransferEvent.Progress(bytesCompleted, manifest.totalBytes))
        }
    }

    private fun finish() {
        scope.trySend(
            TransferEvent.Completed(
                durationMillis = System.currentTimeMillis() - startedAt,
                receivedUris = committedUris.toList(),
            )
        )
        scope.close()
    }

    private suspend fun abort(endpointId: String, error: TransferError) {
        destinations.values.forEach { runCatching { it.discard() } }
        client.disconnectFromEndpoint(endpointId)
        scope.trySend(TransferEvent.Failed(error))
        scope.close()
    }

    private companion object {
        val DIGEST_TIMEOUT = 30.seconds
    }
}

// ----------------------------------------------------------------------- sender

private class SendSession(
    private val scope: kotlinx.coroutines.channels.ProducerScope<TransferEvent>,
    private val token: SessionToken,
    private val payload: BeamPayload,
    private val source: PayloadSource,
    private val client: com.google.android.gms.nearby.connection.ConnectionsClient,
    private val localDevice: DeviceInfo,
    private val timeouts: NearbyTransport.Timeouts,
) {
    val endpointFound = CompletableDeferred<String>()

    private val control = Channel<Byte>(Channel.BUFFERED)

    /** Body of the last RESUME_OFFER, read alongside its type from the control channel. */
    @Volatile
    private var resumeOffer: Map<Int, Long> = emptyMap()
    private val payloadDone = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
    private var authDigits: String = ""
    private var startedAt: Long = 0
    private val manifest = PayloadManifest.of(payload)

    val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // The tap told us exactly which advertisement is ours; ignore every other device.
            if (info.endpointName != token.advertisingName) return
            if (!endpointFound.complete(endpointId)) return

            client.stopDiscovery()
            scope.trySend(TransferEvent.Connecting)
            client.requestConnection(
                localDevice.displayName.take(32),
                endpointId,
                connectionCallback,
                NearbyTransport.connectionOptions(),
            )
                .addOnFailureListener { error ->
                    scope.trySend(
                        TransferEvent.Failed(NearbyErrors.toTransferError("requestConnection", error))
                    )
                    scope.close()
                }
        }

        override fun onEndpointLost(endpointId: String) = Unit
    }

    val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            authDigits = info.authenticationDigits
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.statusCode != ConnectionsStatusCodes.STATUS_OK) {
                scope.trySend(
                    TransferEvent.Failed(
                        NearbyErrors.fromStatus("sender onConnectionResult", resolution.status.statusCode)
                    )
                )
                scope.close()
                return
            }
            scope.launch(Dispatchers.IO) { runProtocol(endpointId) }
        }

        override fun onDisconnected(endpointId: String) {
            scope.trySend(TransferEvent.Failed(TransferError.Io("The other device disconnected")))
            scope.close()
        }
    }

    val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val frame = payload.asBytes() ?: return
            val type = BeamFrames.typeOf(frame) ?: return
            if (type == BeamFrames.RESUME_OFFER) {
                resumeOffer = BeamFrames.parseResumeOffer(BeamFrames.bodyOf(frame))
            }
            control.trySend(type)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS ->
                    scope.trySend(
                        TransferEvent.Progress(
                            (sentBefore + update.bytesTransferred).coerceAtMost(manifest.totalBytes),
                            manifest.totalBytes,
                        )
                    )

                PayloadTransferUpdate.Status.SUCCESS -> payloadDone[update.payloadId]?.complete(true)
                PayloadTransferUpdate.Status.FAILURE -> payloadDone[update.payloadId]?.complete(false)
                else -> Unit
            }
        }
    }

    private var sentBefore: Long = 0

    /** The sender's half of the frame sequence documented on [BeamFrame]. */
    private suspend fun runProtocol(endpointId: String) {
        suspend fun fail(error: TransferError) {
            client.disconnectFromEndpoint(endpointId)
            scope.trySend(TransferEvent.Failed(error))
            scope.close()
        }

        val proof = SessionAuth.proofFor(token, NearbyTransport.authContext(token, authDigits))
        // proof ‖ our stable id, so the receiver can recognise this phone next time.
        val authBody = BeamFrames.encodeAuth(
            proof,
            BeamFrames.Identity(localDevice.stableId, localDevice.displayName),
        )
        client.sendPayload(endpointId, Payload.fromBytes(BeamFrames.of(BeamFrames.AUTH, authBody)))

        when (withTimeoutOrNull(timeouts.handshake) { control.receive() }) {
            BeamFrames.AUTH_OK -> Unit
            BeamFrames.AUTH_FAIL -> return fail(TransferError.AuthenticationFailed())
            else -> return fail(TransferError.AuthenticationFailed("No answer from the other device"))
        }

        client.sendPayload(endpointId, Payload.fromBytes(BeamFrames.of(BeamFrames.MANIFEST, manifest.encode())))

        when (withTimeoutOrNull(timeouts.acceptance) { control.receive() }) {
            BeamFrames.ACCEPT -> Unit
            BeamFrames.DECLINE -> return fail(TransferError.Rejected())
            else -> return fail(TransferError.Rejected("No answer"))
        }

        startedAt = System.currentTimeMillis()
        scope.trySend(TransferEvent.Started(manifest.totalBytes, token.device))

        // What the receiver already holds, if anything.
        val held = withTimeoutOrNull(timeouts.handshake) { control.receive() }
            ?.let { if (it == BeamFrames.RESUME_OFFER) resumeOffer else emptyMap() }
            ?: emptyMap()

        if (payload is BeamPayload.Files) {
            payload.files.forEachIndexed { index, ref ->
                val entry = manifest.files[index]
                scope.trySend(TransferEvent.FileStarted(index, entry.name, payload.files.size))

                val offset = (held[index] ?: 0L).coerceIn(0L, entry.sizeBytes)

                /*
                 * The digest is computed in a pass of its own and sent before the body.
                 *
                 * Nearby hands BYTES frames and STREAM payloads to different callbacks with
                 * no ordering guarantee, and the stream is read by Play Services rather than
                 * by us, so there is no natural point at which to hash it on the way past as
                 * the Wi-Fi Direct path does. An extra local read on the fallback transport
                 * is a fair price for the receiver being able to detect a damaged file.
                 */
                val digest = runCatching {
                    BeamFrames.sha256().also { d ->
                        source.open(ref).use { BeamFrames.digestExactly(it, entry.sizeBytes, d) }
                    }.digest()
                }.getOrElse { return fail(TransferError.Io("Could not read ${ref.safeName}")) }

                client.sendPayload(
                    endpointId,
                    Payload.fromBytes(BeamFrames.of(BeamFrames.FILE_DIGEST, BeamFrames.indexedDigest(index, digest))),
                )
                client.sendPayload(
                    endpointId,
                    Payload.fromBytes(
                        BeamFrames.of(BeamFrames.FILE_HEADER, BeamFrames.fileHeader(index, entry.sizeBytes, offset)),
                    ),
                )

                val stream = runCatching {
                    source.open(ref).also { if (offset > 0) it.skipExactly(offset) }
                }.getOrElse {
                    return fail(TransferError.Io("Could not read ${ref.safeName}"))
                }

                val filePayload = Payload.fromStream(stream)
                val done = CompletableDeferred<Boolean>()
                payloadDone[filePayload.id] = done
                client.sendPayload(endpointId, filePayload)
                if (!done.await()) return fail(TransferError.Io("Sending ${ref.safeName} failed"))
                sentBefore += entry.sizeBytes
                scope.trySend(TransferEvent.Progress(sentBefore, manifest.totalBytes))
            }
        }

        client.sendPayload(endpointId, Payload.fromBytes(BeamFrames.of(BeamFrames.DONE)))
        scope.trySend(TransferEvent.Completed(System.currentTimeMillis() - startedAt))
        scope.close()
    }
}

/** SHA-256 output: the fixed-size prefix of an AUTH frame. */
private const val PROOF_BYTES = 32

/** Matches the session token's cap on the field. */
private const val MAX_STABLE_ID = 24

/** Stands in until the sender has proved who it is. */
private const val PENDING_PEER_ID = "unverified"

/** Advances a stream by exactly [count] bytes; skip() is allowed to do less. */
private fun java.io.InputStream.skipExactly(count: Long) {
    var remaining = count
    val scratch = ByteArray(64 * 1024)
    while (remaining > 0) {
        val read = read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
        if (read < 0) throw java.io.EOFException("could not skip $count bytes")
        remaining -= read
    }
}

/** Endpoint names come from another device; never render one unfiltered. */
private fun String.sanitizedPeerName(): String =
    filter { !it.isISOControl() }.trim().take(32).ifBlank { DeviceInfo.Unknown.displayName }
