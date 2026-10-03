package dev.beam.core.transport

import dev.beam.core.model.BeamPayload
import dev.beam.core.model.PayloadManifest
import dev.beam.core.model.DeviceInfo
import dev.beam.core.model.TransferEvent
import dev.beam.core.model.TransportId
import dev.beam.core.session.SessionToken
import kotlinx.coroutines.flow.Flow

/**
 * A way to move a beam's bytes between two devices that have already tapped.
 *
 * This is the seam the whole app is built around: NFC hands us a [SessionToken], and whichever
 * `Transport` both sides agreed on turns that token into a byte pipe. Nearby Connections is
 * today's implementation, Wi-Fi Direct is the planned replacement, and `FakeTransport` is how
 * the flow is exercised without a second phone. Nothing above this interface may name a
 * backend's types — the build fails if it does (see `checkTransportBoundary`).
 *
 * Both entry points are cold [Flow]s: collecting starts the radios, cancelling the collection
 * is how a transfer is aborted and how the receiver stops advertising. Every flow ends with
 * either [TransferEvent.Completed] or [TransferEvent.Failed] and then completes normally — an
 * implementation must not throw into the collector.
 */
interface Transport {

    val id: TransportId

    /**
     * Whether this backend can run right now: hardware present, Play Services usable,
     * permissions granted. Checked before a token is minted so the receiver only advertises
     * backends it can actually honour.
     */
    suspend fun isAvailable(): Boolean

    /**
     * Receiver side. Publishes an endpoint named [SessionToken.advertisingName] and accepts the
     * one peer that can prove it performed the tap.
     *
     * The transfer pauses after [TransferEvent.ManifestOffered] until [decide] returns: that is
     * where the user confirmation lives. Returning false declines cleanly.
     *
     * Advertising stops when the flow is cancelled or completes.
     */
    fun receive(
        token: SessionToken,
        sink: PayloadSink,
        decide: suspend (PayloadManifest, DeviceInfo) -> Boolean,
    ): Flow<TransferEvent>

    /**
     * Sender side. Finds the endpoint the token points at, proves the tap, and sends [payload].
     */
    fun send(
        token: SessionToken,
        payload: BeamPayload,
        source: PayloadSource,
    ): Flow<TransferEvent>
}
