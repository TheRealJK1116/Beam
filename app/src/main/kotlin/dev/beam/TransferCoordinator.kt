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

        start(Role.SENDER, token) { transport ->
            transport.send(token, payload, source)
        }
    }

    /**
     * Receiver: our HCE service was just read, so start publishing and wait for the sender.
     * [autoAccept] decides without prompting when the peer is already known.
     */
    fun beginReceive(token: SessionToken, autoAccept: suspend (DeviceInfo) -> Boolean) {
        start(Role.RECEIVER, token) { transport ->
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

    private fun start(
        role: Role,
        token: SessionToken,
        events: (dev.beam.core.transport.Transport) -> kotlinx.coroutines.flow.Flow<TransferEvent>,
    ) {
        job?.cancel()
        _state.value = HandshakeReducer.reduce(HandshakeState.Idle, HandshakeEvent.Begin(role))

        job = scope.launch {
            val transport = registry.negotiate(token)
            android.util.Log.i(TAG, "begin $role; token offers ${token.transports}; negotiated ${transport?.id}")
            if (transport == null) {
                dispatch(
                    HandshakeEvent.Failed(
                        TransferError.TransportUnavailable(
                            token.transports.firstOrNull() ?: TransportId.NEARBY,
                            "No shared way to transfer with that device",
                        )
                    )
                )
                return@launch
            }

            dispatch(HandshakeEvent.TapCompleted(token, transport.id))

            val startedAt = System.currentTimeMillis()
            events(transport).collect { event -> dispatch(event.toHandshakeEvent()) }

            // A transport that completes its flow without a terminal event has dropped us.
            if (!_state.value.isTerminal) {
                dispatch(HandshakeEvent.Failed(TransferError.Io("The transfer ended unexpectedly")))
            }
            record(role, token, transport.id, startedAt)
        }
    }

    private fun dispatch(event: HandshakeEvent) {
        val before = _state.value
        val after = HandshakeReducer.reduce(before, event)
        if (after != before) {
            android.util.Log.i(TAG, "${before.javaClass.simpleName} --${event.javaClass.simpleName}--> $after")
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
