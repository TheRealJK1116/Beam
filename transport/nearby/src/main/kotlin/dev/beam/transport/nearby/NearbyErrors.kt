package dev.beam.transport.nearby

import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import dev.beam.core.model.TransferError
import dev.beam.core.model.TransportId

/**
 * Turns a Play Services failure into something a person can act on — and, just as importantly,
 * records the raw status code.
 *
 * Nearby reports most refusals as a bare numeric status. Passing `exception.message` straight
 * to the UI puts strings like "8: INTERNAL_ERROR" in front of the user, which tells them
 * nothing and tells the developer almost as little, because the code never reaches logcat
 * either. Every failure now goes through here: logged with its code at the call site, shown as
 * a sentence.
 */
internal object NearbyErrors {

    const val TAG = "BeamNearby"

    /** Status codes worth naming. The rest fall back to the code itself. */
    fun describe(statusCode: Int): String = when (statusCode) {
        ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING ->
            "Already advertising — a previous beam has not finished"
        ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING ->
            "Already searching — a previous beam has not finished"
        ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT ->
            "Already connected to that device"
        ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> "The other device declined"
        ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR -> "The connection dropped"
        ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN -> "That device is no longer reachable"
        ConnectionsStatusCodes.STATUS_OUT_OF_ORDER_API_CALL -> "Beam called Nearby out of order"
        ConnectionsStatusCodes.STATUS_ALREADY_HAVE_ACTIVE_STRATEGY ->
            "A previous beam is still running — close Beam and try again"
        ConnectionsStatusCodes.API_CONNECTION_FAILED_ALREADY_IN_USE ->
            "Another app is using the nearby-sharing radios"
        ConnectionsStatusCodes.STATUS_NETWORK_NOT_CONNECTED -> "No usable radio connection"

        // STATUS_BLUETOOTH_ERROR and STATUS_RADIO_ERROR are both 8007.
        ConnectionsStatusCodes.STATUS_RADIO_ERROR -> "Bluetooth is unavailable — try toggling it"

        ConnectionsStatusCodes.MISSING_SETTING_LOCATION_MUST_BE_ON ->
            "Turn on Location — Nearby cannot search without it"

        ConnectionsStatusCodes.MISSING_PERMISSION_NEARBY_WIFI_DEVICES,
        ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH,
        ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_ADMIN,
        ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_WIFI_STATE,
        ConnectionsStatusCodes.MISSING_PERMISSION_CHANGE_WIFI_STATE,
        ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_COARSE_LOCATION,
        ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_FINE_LOCATION,
        ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_SCAN,
        ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_ADVERTISE,
        ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_CONNECT,
        ->
            "Beam is missing a permission it needs (code $statusCode)"

        // CommonStatusCodes.INTERNAL_ERROR. What Nearby returns when it refuses the call
        // outright rather than for a reason it has a code for.
        8 -> "Play Services refused the connection (internal error)"
        ConnectionsStatusCodes.STATUS_ERROR -> "Nearby failed to start"
        else -> "Nearby error $statusCode"
    }

    /** True when the status says the refusal was about a permission or the location toggle. */
    fun isPermissionProblem(statusCode: Int?): Boolean = statusCode in setOf(
        ConnectionsStatusCodes.MISSING_SETTING_LOCATION_MUST_BE_ON,
        ConnectionsStatusCodes.MISSING_PERMISSION_NEARBY_WIFI_DEVICES,
        ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH,
        ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_ADMIN,
        ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_WIFI_STATE,
        ConnectionsStatusCodes.MISSING_PERMISSION_CHANGE_WIFI_STATE,
        ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_COARSE_LOCATION,
        ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_FINE_LOCATION,
        ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_SCAN,
        ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_ADVERTISE,
        ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_CONNECT,
    )

    /** Extracts the status code from whatever Play Services threw, or null if it is not an API failure. */
    fun statusCodeOf(throwable: Throwable?): Int? = (throwable as? ApiException)?.statusCode

    /**
     * Logs [throwable] against [stage] and returns the error to show. [stage] names the call
     * that failed, so a bug report says "startAdvertising failed: 8" rather than just "failed".
     */
    fun toTransferError(stage: String, throwable: Throwable?): TransferError {
        val code = statusCodeOf(throwable)
        Log.e(TAG, "$stage failed: code=${code ?: "none"} message=${throwable?.message}", throwable)

        return when (code) {
            ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> TransferError.Rejected()
            ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN -> TransferError.DiscoveryTimeout()
            null -> TransferError.Io(throwable?.message ?: "$stage failed")
            else -> TransferError.TransportUnavailable(TransportId.NEARBY, describe(code))
        }
    }

    /** Same, for a [com.google.android.gms.common.api.Status] handed back by a callback. */
    fun fromStatus(stage: String, statusCode: Int): TransferError {
        Log.e(TAG, "$stage failed: code=$statusCode (${describe(statusCode)})")
        return when (statusCode) {
            ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> TransferError.Rejected()
            ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN -> TransferError.DiscoveryTimeout()
            else -> TransferError.Io(describe(statusCode))
        }
    }
}
