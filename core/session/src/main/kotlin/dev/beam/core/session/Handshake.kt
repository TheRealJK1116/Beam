package dev.beam.core.session

import dev.beam.core.model.DeviceInfo
import dev.beam.core.model.TransferError
import dev.beam.core.model.PayloadManifest
import dev.beam.core.model.TransportId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The beam, as a pure state machine.
 *
 * Both roles run the same reducer. Nothing here knows about NFC, Nearby, Android or coroutines,
 * which is the point: the flow can be tested exhaustively on the JVM in milliseconds, and the
 * developer simulation tools drive this exact machine with synthetic events.
 *
 * Time is an input, never read from a clock, so timeout behaviour is deterministic in tests.
 */
sealed interface HandshakeState {

    /** Nothing happening. */
    data object Idle : HandshakeState

    /** Sender has content and is in NFC reader mode; receiver is simply unlocked and present. */
    data class AwaitingTap(val role: Role) : HandshakeState

    /** The tap happened and the token parsed. */
    data class TokenAcquired(val role: Role, val token: SessionToken, val transport: TransportId) : HandshakeState

    /** Sender is scanning for [SessionToken.advertisingName]; receiver is advertising it. */
    data class Discovering(val role: Role, val token: SessionToken, val transport: TransportId) : HandshakeState

    /** Endpoint found; proof-of-tap being exchanged. */
    data class Authenticating(val role: Role, val token: SessionToken, val transport: TransportId) : HandshakeState

    /** Receiver only: the user has been asked whether to accept. */
    data class AwaitingAcceptance(val token: SessionToken, val manifest: PayloadManifest, val peer: DeviceInfo) : HandshakeState

    data class Transferring(
        val role: Role,
        val peer: DeviceInfo,
        val transferredBytes: Long,
        val totalBytes: Long,
        /** Which file of how many, for a folder or multi-file beam. Zero-based. */
        val fileIndex: Int = 0,
        val fileCount: Int = 1,
        val fileName: String? = null,
    ) : HandshakeState {
        val fraction: Float
            get() = if (totalBytes <= 0) 0f else (transferredBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
    }

    data class Complete(val role: Role, val peer: DeviceInfo, val durationMillis: Long) : HandshakeState

    data class Failed(val role: Role, val error: TransferError) : HandshakeState

    /** True while the machine can still make progress on its own. */
    val isTerminal: Boolean get() = this is Complete || this is Failed
}

enum class Role { SENDER, RECEIVER }

/** Inputs to [HandshakeReducer]. Every one corresponds to something a real layer observed. */
sealed interface HandshakeEvent {
    data class Begin(val role: Role) : HandshakeEvent

    /** NFC delivered a token. On the receiver this is "my HCE service was read". */
    data class TapCompleted(val token: SessionToken, val transport: TransportId) : HandshakeEvent

    data object AdvertisingStarted : HandshakeEvent
    data object DiscoveryStarted : HandshakeEvent
    data object EndpointFound : HandshakeEvent
    data class Authenticated(val peer: DeviceInfo) : HandshakeEvent

    /** Receiver: manifest arrived and the user must decide. */
    data class ManifestReceived(val manifest: PayloadManifest, val peer: DeviceInfo) : HandshakeEvent
    data object Accepted : HandshakeEvent
    data object Declined : HandshakeEvent

    data class TransferStarted(val peer: DeviceInfo, val totalBytes: Long) : HandshakeEvent
    data class Progressed(val transferredBytes: Long) : HandshakeEvent

    /** A new file within the same transfer started moving. */
    data class FileStarted(val index: Int, val name: String, val count: Int) : HandshakeEvent
    data class Finished(val durationMillis: Long) : HandshakeEvent

    data class Failed(val error: TransferError) : HandshakeEvent
    data object Cancelled : HandshakeEvent

    /** The caller's clock says the current stage has run out of time. */
    data object Timeout : HandshakeEvent
}

/** How long each stage is allowed to take before [HandshakeEvent.Timeout] should be fed in. */
object HandshakeTimeouts {
    val awaitingTap: Duration = 60.seconds
    val discovery: Duration = 10.seconds
    val authentication: Duration = 8.seconds
    val acceptance: Duration = 30.seconds

    /** How long the receiver keeps advertising after a tap before giving the radios back. */
    val advertisingWindow: Duration = 30.seconds

    fun forState(state: HandshakeState): Duration? = when (state) {
        is HandshakeState.AwaitingTap -> awaitingTap
        is HandshakeState.TokenAcquired, is HandshakeState.Discovering -> discovery
        is HandshakeState.Authenticating -> authentication
        is HandshakeState.AwaitingAcceptance -> acceptance
        // A transfer in progress times out on stalled bytes, which the transport reports as a
        // failure; no wall-clock cap here or we would kill large legitimate transfers.
        else -> null
    }
}

object HandshakeReducer {

    /**
     * Applies [event] to [state]. Unknown combinations return [state] unchanged rather than
     * throwing: events race (a late `Progressed` after `Finished` is normal), and a dropped
     * event is always better than a crash mid-beam.
     */
    fun reduce(state: HandshakeState, event: HandshakeEvent): HandshakeState {
        // Terminal states absorb everything except an explicit restart.
        if (state.isTerminal && event !is HandshakeEvent.Begin) return state

        return when (event) {
            is HandshakeEvent.Begin -> HandshakeState.AwaitingTap(event.role)

            is HandshakeEvent.Cancelled -> HandshakeState.Failed(state.role(), TransferError.Cancelled())

            is HandshakeEvent.Failed -> HandshakeState.Failed(state.role(), event.error)

            is HandshakeEvent.Timeout -> HandshakeState.Failed(state.role(), state.timeoutError())

            is HandshakeEvent.TapCompleted -> when (state) {
                is HandshakeState.Idle, is HandshakeState.AwaitingTap ->
                    HandshakeState.TokenAcquired(state.role(), event.token, event.transport)
                else -> state
            }

            HandshakeEvent.AdvertisingStarted, HandshakeEvent.DiscoveryStarted -> when (state) {
                is HandshakeState.TokenAcquired ->
                    HandshakeState.Discovering(state.role, state.token, state.transport)
                else -> state
            }

            HandshakeEvent.EndpointFound -> when (state) {
                is HandshakeState.Discovering ->
                    HandshakeState.Authenticating(state.role, state.token, state.transport)
                is HandshakeState.TokenAcquired ->
                    HandshakeState.Authenticating(state.role, state.token, state.transport)
                else -> state
            }

            is HandshakeEvent.Authenticated -> when (state) {
                // Authentication passed but no bytes yet; hold here until the manifest or the
                // transfer start arrives.
                is HandshakeState.Authenticating ->
                    HandshakeState.Transferring(state.role, event.peer, 0, 0)
                else -> state
            }

            is HandshakeEvent.ManifestReceived -> when (state) {
                is HandshakeState.Authenticating, is HandshakeState.Transferring ->
                    HandshakeState.AwaitingAcceptance(
                        token = state.tokenOrNull()
                            ?: return HandshakeState.Failed(state.role(), TransferError.MalformedToken("no session")),
                        manifest = event.manifest,
                        peer = event.peer,
                    )
                else -> state
            }

            HandshakeEvent.Accepted -> when (state) {
                is HandshakeState.AwaitingAcceptance -> HandshakeState.Transferring(
                    role = Role.RECEIVER,
                    peer = state.peer,
                    transferredBytes = 0,
                    totalBytes = state.manifest.totalBytes,
                )
                else -> state
            }

            HandshakeEvent.Declined -> HandshakeState.Failed(state.role(), TransferError.Rejected())

            is HandshakeEvent.TransferStarted -> HandshakeState.Transferring(
                role = state.role(),
                peer = event.peer,
                transferredBytes = 0,
                totalBytes = event.totalBytes,
            )

            is HandshakeEvent.FileStarted -> when (state) {
                is HandshakeState.Transferring -> state.copy(
                    fileIndex = event.index,
                    fileCount = event.count,
                    fileName = event.name,
                )
                else -> state
            }

            is HandshakeEvent.Progressed -> when (state) {
                is HandshakeState.Transferring -> state.copy(
                    // Never let a buggy or hostile peer walk progress backwards.
                    transferredBytes = event.transferredBytes
                        .coerceIn(state.transferredBytes, maxOf(state.totalBytes, event.transferredBytes)),
                )
                else -> state
            }

            is HandshakeEvent.Finished -> when (state) {
                is HandshakeState.Transferring ->
                    HandshakeState.Complete(state.role, state.peer, event.durationMillis)
                is HandshakeState.AwaitingAcceptance ->
                    HandshakeState.Complete(Role.RECEIVER, state.peer, event.durationMillis)
                else -> state
            }
        }
    }
}

private fun HandshakeState.role(): Role = when (this) {
    is HandshakeState.AwaitingTap -> role
    is HandshakeState.TokenAcquired -> role
    is HandshakeState.Discovering -> role
    is HandshakeState.Authenticating -> role
    is HandshakeState.AwaitingAcceptance -> Role.RECEIVER
    is HandshakeState.Transferring -> role
    is HandshakeState.Complete -> role
    is HandshakeState.Failed -> role
    HandshakeState.Idle -> Role.SENDER
}

private fun HandshakeState.tokenOrNull(): SessionToken? = when (this) {
    is HandshakeState.TokenAcquired -> token
    is HandshakeState.Discovering -> token
    is HandshakeState.Authenticating -> token
    is HandshakeState.AwaitingAcceptance -> token
    else -> null
}

private fun HandshakeState.timeoutError(): TransferError = when (this) {
    is HandshakeState.AwaitingTap -> TransferError.DiscoveryTimeout("No tap detected")
    is HandshakeState.TokenAcquired, is HandshakeState.Discovering -> TransferError.DiscoveryTimeout()
    is HandshakeState.Authenticating -> TransferError.AuthenticationFailed("Timed out verifying the other device")
    is HandshakeState.AwaitingAcceptance -> TransferError.Rejected("No answer")
    else -> TransferError.Io("Timed out")
}
