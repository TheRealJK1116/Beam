package dev.beam.transport.wifidirect

import android.content.Context
import android.content.pm.PackageManager
import dev.beam.core.model.BeamPayload
import dev.beam.core.model.DeviceInfo
import dev.beam.core.model.PayloadManifest
import dev.beam.core.model.TransferError
import dev.beam.core.model.TransferEvent
import dev.beam.core.model.TransportId
import dev.beam.core.session.SessionToken
import dev.beam.core.transport.PayloadSink
import dev.beam.core.transport.PayloadSource
import dev.beam.core.transport.Transport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The planned replacement for Nearby Connections: raw Wi-Fi Direct, no Play Services, works on
 * a de-Googled device.
 *
 * It is deliberately unimplemented. Its job today is to hold the seam honest — this module
 * compiles against nothing but `:core:transport`, which proves the interface is sufficient to
 * describe a second backend, and it registers itself as unavailable so negotiation skips it at
 * runtime. When it is built out, no call site above the transport layer changes: the token
 * starts advertising [TransportId.WIFI_DIRECT] and the registry picks it.
 *
 * Remaining work: group owner negotiation via `WifiP2pManager`, a service record carrying
 * [SessionToken.advertisingName] so the sender can match the tap, and a TLS socket over the
 * group using the session secret as a pre-shared key (Wi-Fi Direct, unlike Nearby, gives no
 * encryption of its own).
 */
class WifiDirectTransport(context: Context) : Transport {

    private val appContext = context.applicationContext

    override val id: TransportId = TransportId.WIFI_DIRECT

    /**
     * False until the implementation lands, even where the hardware is present, so that
     * [dev.beam.core.transport.TransportRegistry] never negotiates down to a backend that
     * cannot carry a beam.
     */
    override suspend fun isAvailable(): Boolean = false

    /** True once the hardware check is the only thing standing in the way. */
    internal fun hasHardware(): Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    override fun receive(
        token: SessionToken,
        sink: PayloadSink,
        decide: suspend (PayloadManifest, DeviceInfo) -> Boolean,
    ): Flow<TransferEvent> = unavailable()

    override fun send(
        token: SessionToken,
        payload: BeamPayload,
        source: PayloadSource,
    ): Flow<TransferEvent> = unavailable()

    private fun unavailable(): Flow<TransferEvent> = flow {
        emit(
            TransferEvent.Failed(
                TransferError.TransportUnavailable(
                    TransportId.WIFI_DIRECT,
                    "Wi-Fi Direct transfer is not implemented yet",
                )
            )
        )
    }
}
