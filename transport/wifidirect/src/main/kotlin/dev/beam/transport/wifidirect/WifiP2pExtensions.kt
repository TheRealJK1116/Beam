package dev.beam.transport.wifidirect

import android.annotation.SuppressLint
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Coroutine bridges over `WifiP2pManager`'s listener API.
 *
 * Every call here needs NEARBY_WIFI_DEVICES (or location below Android 13), which the app
 * checks before a tap is ever answered; the suppressions are there because lint cannot see
 * that check across the module boundary.
 */

private const val TAG = "BeamWifiDirect"

/** Builds the config that makes a group joinable without any prompt on either device. */
@RequiresApi(Build.VERSION_CODES.Q)
private fun GroupCredentials.toConfig(): WifiP2pConfig =
    WifiP2pConfig.Builder()
        .setNetworkName(networkName)
        .setPassphrase(passphrase)
        // A beam is single-use; persisting the group would leave it behind for later taps.
        .enablePersistentMode(false)
        .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
        .build()

/**
 * Receiver side: become the group owner on a network whose name and passphrase the sender can
 * derive from the tap.
 */
@SuppressLint("MissingPermission")
suspend fun WifiP2pManager.createGroup(
    channel: WifiP2pManager.Channel,
    credentials: GroupCredentials,
): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    return suspendCancellableCoroutine { continuation ->
        createGroup(channel, credentials.toConfig(), object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (continuation.isActive) continuation.resume(true)
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "createGroup failed: ${reasonName(reason)}")
                if (continuation.isActive) continuation.resume(false)
            }
        })
    }
}

/**
 * Sender side: join the receiver's group directly.
 *
 * Because the config carries the network name and passphrase, this is a join rather than an
 * invitation — which is exactly why no dialog appears on either phone.
 */
@SuppressLint("MissingPermission")
suspend fun WifiP2pManager.joinGroup(
    channel: WifiP2pManager.Channel,
    credentials: GroupCredentials,
): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    return suspendCancellableCoroutine { continuation ->
        connect(channel, credentials.toConfig(), object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (continuation.isActive) continuation.resume(true)
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "connect failed: ${reasonName(reason)}")
                if (continuation.isActive) continuation.resume(false)
            }
        })
    }
}

/** The group owner's address, or null if the group is not formed yet. */
@SuppressLint("MissingPermission")
suspend fun WifiP2pManager.groupOwnerAddress(channel: WifiP2pManager.Channel): String? =
    suspendCancellableCoroutine { continuation ->
        requestConnectionInfo(channel) { info ->
            val address = info?.groupOwnerAddress?.hostAddress?.takeIf { info.groupFormed }
            if (continuation.isActive) continuation.resume(address)
        }
    }

/**
 * Tears down any group this app owns. Called before forming one and again afterwards: a group
 * left behind from an abandoned beam will block the next one, and leaving the radio in a group
 * costs battery.
 */
@SuppressLint("MissingPermission")
fun WifiP2pManager.removeGroupQuietly(channel: WifiP2pManager.Channel) {
    runCatching {
        removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit
            override fun onFailure(reason: Int) = Unit // Usually "no group", which is fine.
        })
    }
}

fun WifiP2pManager.Channel.closeQuietly() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        runCatching { close() }
    }
}

private fun reasonName(reason: Int): String = when (reason) {
    WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
    WifiP2pManager.BUSY -> "BUSY"
    WifiP2pManager.ERROR -> "ERROR"
    WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS"
    else -> "reason $reason"
}
