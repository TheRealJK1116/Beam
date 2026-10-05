package dev.beam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.PowerManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/**
 * Which runtime permissions a beam actually needs, and whether we have them.
 *
 * This is one list in one place because the three entry points cannot each guess: the share
 * sheet can open [dev.beam.ui.SendActivity] without the user ever having opened Beam's home
 * screen, and the receive path is a service woken by an NFC tap, which cannot show a permission
 * dialog at all. Asking only on first launch of the main activity — which is what Beam did
 * originally — leaves both of those paths to fail inside Nearby with an opaque status code.
 */
object BeamPermissions {

    /**
     * The permissions without which a transfer cannot work. Notifications are deliberately not
     * here: a beam still completes if the user refuses them, it just loses the progress
     * readout.
     */
    fun requiredForTransfer(): List<String> = buildList {
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_SCAN)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            // Android 12 routes Nearby's Wi-Fi scanning through location.
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        // Android 16+. Nearby Connections will not advertise or discover without it, and
        // reports its absence only as a generic internal error, so it is checked up front
        // here rather than being discovered the hard way mid-tap.
        if (Build.VERSION.SDK_INT >= ANDROID_16) {
            add(ACCESS_LOCAL_NETWORK)
        }
    }

    /**
     * Not referenced through [Manifest.permission] so that the app still builds against an SDK
     * older than 36; the string is stable platform API.
     */
    const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    private const val ANDROID_16 = 36

    /** Everything worth asking for at once, including the nice-to-have. */
    fun allRequestable(): List<String> = buildList {
        addAll(requiredForTransfer())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun missingForTransfer(context: Context): List<String> =
        requiredForTransfer().filter { !isGranted(context, it) }

    fun missingRequestable(context: Context): List<String> =
        allRequestable().filter { !isGranted(context, it) }

    fun canTransfer(context: Context): Boolean = missingForTransfer(context).isEmpty()

    /**
     * Nearby needs the system location *toggle* on, not merely the permission, and reports its
     * absence as a status code rather than a permission denial. Checking it up front turns
     * that into a sentence the user can act on.
     */
    fun isLocationServiceRequired(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    fun isLocationServiceOn(context: Context): Boolean {
        val manager = ContextCompat.getSystemService(context, LocationManager::class.java)
            ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    /** Everything needed to beam right now, permissions and system toggles together. */
    fun isReady(context: Context): Boolean =
        canTransfer(context) && (!isLocationServiceRequired() || isLocationServiceOn(context))

    /**
     * Whether the system will let Beam run when it is not on screen.
     *
     * Not required to beam while the app is open, which is why it is kept apart from
     * [isReady] - but receiving works by the platform binding Beam's HCE service when another
     * phone taps this one, and a doze-restricted app is not there to be bound. Without this
     * the app has to be open for a tap to land, which defeats the point.
     */
    fun isExemptFromBatteryOptimisation(context: Context): Boolean =
        ContextCompat.getSystemService(context, PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) ?: false

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
