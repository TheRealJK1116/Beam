package dev.beam

import dev.beam.core.model.BeamPayload
import dev.beam.core.model.DeviceInfo
import dev.beam.core.model.PayloadManifest
import dev.beam.core.model.TransferError
import dev.beam.core.model.TransferEvent
import dev.beam.core.model.TransportId
import dev.beam.core.session.HandshakeEvent
import dev.beam.core.session.HandshakeReducer
import dev.beam.core.session.HandshakeState
import dev.beam.core.session.HandshakeTimeouts
import dev.beam.core.session.Role
import dev.beam.core.session.SessionToken
import dev.beam.core.transport.PayloadSink
import dev.beam.core.transport.PayloadSource
import dev.beam.core.transport.TransportRegistry
import dev.beam.data.history.TransferDao
import dev.beam.data.history.TransferRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import dev.beam.core.transport.Transport
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one place a beam lives while it is happening.
 *
 * Activities come and go mid-transfer — the receiver's confirmation screen can be dismissed,
 * the sender can rotate the phone — so the transfer is owned here, at application scope, and
 * the UI only observes [state]. The foreground service keeps the process alive; it does not own
 * the transfer either.
 *
 * Transport events are funnelled through [HandshakeReducer] rather than interpreted directly,
 * so the sequencing rules (progress cannot go backwards, late events after completion are
 * ignored, each stage has a deadline) are the ones covered by the pure-Kotlin tests.
 */
class TransferCoordinator(
    private val registry: TransportRegistry,
    private val source: PayloadSource,
    private val sink: PayloadSink,
    private val dao: () -> TransferDao,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<HandshakeState>(HandshakeState.Idle)
    val state: StateFlow<HandshakeState> get() = _state

    /** Set while the receiver is waiting for the user to accept or decline. */
    private val _pendingDecision = MutableStateFlow<PendingDecision?>(null)
    val pendingDecision: StateFlow<PendingDecision?> get() = _pendingDecision

    data class PendingDecision(
        val manifest: PayloadManifest,
        val peer: DeviceInfo,
        private val answer: CompletableDeferred<Boolean>,
    ) {
        fun accept() = answer.complete(true)
        fun decline() = answer.complete(false)
    }

    private var job: Job? = null

    private companion object {
        const val TAG = "BeamFlow"
    }

    val isBusy: Boolean get() = job?.isActive == true && !_state.value.isTerminal

    /** Sender: the tap produced [token]; push [payload] across whichever backend both share. */
    fun beginSend(token: SessionToken, payload: BeamPayload) {
        // The sender knows what it is sending up front; the receiver learns it from the manifest.
        lastLabel = payload.label
        lastKind = PayloadManifest.of(payload).kind.name
        lastTotalBytes = payload.sizeBytes
        lastPrimaryUri = null

        startSending(token) { transport -> transport.send(token, payload, source) }
    }

    /**
     * Receiver: our HCE service was just read, so start publishing and wait for the sender.
     * [autoAccept] decides without prompting when the peer is already known.
     */
    fun beginReceive(token: SessionToken, autoAccept: suspend (DeviceInfo) -> Boolean) {
        startReceiving(token) { transport ->
            transport.receive(token, sink) { manifest, peer ->
                if (autoAccept(peer)) {
                    true
                } else {
                    val answer = CompletableDeferred<Boolean>()
                    _pendingDecision.value = PendingDecision(manifest, peer, answer)
                    try {
                        withTimeoutOrNull(HandshakeTimeouts.acceptance) { answer.await() } ?: false
                    } finally {
                        _pendingDecision.value = null
                    }
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _pendingDecision.value = null
        _state.value = HandshakeReducer.reduce(_state.value, HandshakeEvent.Cancelled)
    }

    /** Clears a finished beam so the UI returns to its resting state. */
    fun reset() {
        if (_state.value.isTerminal) _state.value = HandshakeState.Idle
    }

    /**
     * Sender: try each shared transport in turn.
     *
     * A transport that reports itself available can still fail when it actually runs — a
     * Wi-Fi Direct group that will not form, a Nearby call Play Services refuses. Falling back
     * only makes sense before the peer has committed to anything, so a failure after the
     * manifest has been offered is final: by then the user has seen a prompt and bytes may
     * have moved, and silently starting over on another radio would be worse than failing.
     */
    private fun startSending(
        token: SessionToken,
        events: (Transport) -> Flow<TransferEvent>,
    ) {
        job?.cancel()
        _state.value = HandshakeReducer.reduce(HandshakeState.Idle, HandshakeEvent.Begin(Role.SENDER))

        job = scope.launch {
            val candidates = candidatesFor(token)
            Log.i(TAG, "begin SENDER; token offers ${token.transports}; candidates ${candidates.map { it.id }}")
            if (candidates.isEmpty()) {
                dispatch(HandshakeEvent.Failed(noSharedTransport(token)))
                return@launch
            }

            val startedAt = System.currentTimeMillis()
            var used = candidates.first().id

            for ((index, transport) in candidates.withIndex()) {
                used = transport.id
                _state.value = HandshakeReducer.reduce(HandshakeState.Idle, HandshakeEvent.Begin(Role.SENDER))
                dispatch(HandshakeEvent.TapCompleted(token, transport.id))

                var committed = false
                events(transport).collect { event ->
                    if (event is TransferEvent.ManifestOffered || event is TransferEvent.Started) {
                        committed = true
                    }
                    dispatch(event.toHandshakeEvent())
                }

                val failed = _state.value as? HandshakeState.Failed
                val isLast = index == candidates.lastIndex
                if (failed == null || committed || isLast) break

                Log.w(TAG, "${transport.id} failed before committing (${failed.error.message}); trying next")
            }

            if (!_state.value.isTerminal) {
                dispatch(HandshakeEvent.Failed(TransferError.Io("The transfer ended unexpectedly")))
            }
            record(Role.SENDER, token, used, startedAt)
        }
    }

    /**
     * Receiver: listen on every shared transport at once.
     *
     * Trying them one at a time would need both phones to agree on which one they are on, and
     * nothing coordinates that — the sender could be dialling Wi-Fi Direct while the receiver
     * has already given up and moved to Nearby. Listening on all of them removes the question:
     * whichever the sender reaches first wins, and the rest are cancelled.
     */
    private fun startReceiving(
        token: SessionToken,
        events: (Transport) -> Flow<TransferEvent>,
    ) {
        job?.cancel()
        _state.value = HandshakeReducer.reduce(HandshakeState.Idle, HandshakeEvent.Begin(Role.RECEIVER))

        job = scope.launch {
            val candidates = candidatesFor(token)
            Log.i(TAG, "begin RECEIVER; token offers ${token.transports}; listening on ${candidates.map { it.id }}")
            if (candidates.isEmpty()) {
                dispatch(HandshakeEvent.Failed(noSharedTransport(token)))
                return@launch
            }

            dispatch(HandshakeEvent.TapCompleted(token, candidates.first().id))
            val startedAt = System.currentTimeMillis()
            val winner = AtomicReference<TransportId?>(null)

            coroutineScope {
                val listeners = mutableListOf<Job>()
                candidates.forEach { transport ->
                    listeners += launch {
                        events(transport).collect { event ->
                            // The first transport to produce a real peer owns the beam.
                            val claims = event is TransferEvent.ManifestOffered ||
                                event is TransferEvent.Started
                            if (claims) {
                                if (!winner.compareAndSet(null, transport.id) &&
                                    winner.get() != transport.id
                                ) {
                                    return@collect
                                }
                                Log.i(TAG, "${transport.id} won the beam")
                                listeners.filterIndexed { i, _ -> candidates[i].id != transport.id }
                                    .forEach { it.cancel() }
                            }

                            val current = winner.get()
                            if (current == null) {
                                // Nobody has claimed it yet: only progress-free status events
                                // can arrive here, and a failure from one radio should not
                                // kill a beam another is still working on.
                                if (event !is TransferEvent.Failed || candidates.size == 1) {
                                    dispatch(event.toHandshakeEvent())
                                }
                            } else if (current == transport.id) {
                                dispatch(event.toHandshakeEvent())
                            }
                        }
                    }
                }
            }

            if (!_state.value.isTerminal) {
                dispatch(HandshakeEvent.Failed(TransferError.Io("No sender arrived")))
            }
            record(Role.RECEIVER, token, winner.get() ?: candidates.first().id, startedAt)
        }
    }

    /** The transports both devices have, in this device's preference order. */
    private suspend fun candidatesFor(token: SessionToken): List<Transport> =
        registry.available().filter { it.id in token.transports }

    private fun noSharedTransport(token: SessionToken) = TransferError.TransportUnavailable(
        token.transports.firstOrNull() ?: TransportId.NEARBY,
        "No shared way to transfer with that device",
    )

    private fun dispatch(event: HandshakeEvent) {
        val before = _state.value
        val after = HandshakeReducer.reduce(before, event)
        if (after != before) {
            Log.i(TAG, "${before.javaClass.simpleName} --${event.javaClass.simpleName}--> $after")
        }
        _state.value = after
    }

    private suspend fun record(role: Role, token: SessionToken, transport: TransportId, startedAt: Long) {
        val finalState = _state.value
        val peer = when (finalState) {
            is HandshakeState.Complete -> finalState.peer
            else -> token.device
        }
        val failure = (finalState as? HandshakeState.Failed)?.error?.message

        runCatching {
            dao().insert(
                TransferRecord(
                    outgoing = role == Role.SENDER,
                    label = lastLabel ?: peer.displayName,
                    kind = lastKind ?: "FILES",
                    sizeBytes = lastTotalBytes,
                    peerStableId = peer.stableId,
                    peerName = peer.displayName,
                    transport = transport,
                    completedAt = System.currentTimeMillis(),
                    durationMillis = System.currentTimeMillis() - startedAt,
                    failureReason = failure,
                    primaryUri = lastPrimaryUri,
                )
            )
        }
    }

    // Captured as events stream past, so the history row can describe what actually moved.
    private var lastLabel: String? = null
    private var lastKind: String? = null
    private var lastTotalBytes: Long = 0
    private var lastPrimaryUri: String? = null

    private fun TransferEvent.toHandshakeEvent(): HandshakeEvent = when (this) {
        TransferEvent.Discovering -> HandshakeEvent.DiscoveryStarted
        TransferEvent.Connecting -> HandshakeEvent.EndpointFound

        is TransferEvent.ManifestOffered -> {
            lastLabel = manifest.describe()
            lastKind = manifest.kind.name
            lastTotalBytes = manifest.totalBytes
            HandshakeEvent.ManifestReceived(manifest, peer)
        }

        is TransferEvent.Started -> {
            lastTotalBytes = totalBytes
            HandshakeEvent.TransferStarted(peer, totalBytes)
        }

        is TransferEvent.FileStarted -> HandshakeEvent.FileStarted(index, name, count)

        is TransferEvent.Progress -> HandshakeEvent.Progressed(transferredBytes)

        is TransferEvent.Completed -> {
            lastPrimaryUri = receivedUris.firstOrNull()
            HandshakeEvent.Finished(durationMillis)
        }

        is TransferEvent.Failed -> HandshakeEvent.Failed(error)
    }
}

internal fun PayloadManifest.describe(): String = when (kind) {
    PayloadManifest.Kind.TEXT, PayloadManifest.Kind.URL -> text.orEmpty().take(60)
    PayloadManifest.Kind.FILES ->
        if (files.size == 1) files.single().name else "${files.size} files"
}
