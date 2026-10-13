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
    fun requiredForTransfer(context: Context): List<String> =
        requiredFor(Build.VERSION.SDK_INT).filter { isGrantable(context, it) }

    /**
     * The required set for a given platform version, with no device involved.
     *
     * Split out from [requiredForTransfer] so the version rules can be tested directly. They
     * are the part that has actually gone wrong, and they are untestable through Robolectric,
     * which cannot emulate the SDK levels the interesting rules turn on.
     */
    fun requiredFor(sdkInt: Int): List<String> = buildList {
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_SCAN)

        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            // Android 12 routes Nearby's Wi-Fi scanning through location.
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        // Android 17 is where local network protections start being enforced, and only for
        // apps targeting it.
        //
        // This used to say Android 16, which bricked the app on the first Android 16 phone it
        // met. There the protections are opt-in: the permission is not requestable, nothing
        // offers it to the user, and checkSelfPermission reports it denied for ever. Every
        // permission the system asked about was granted and the gate still said no, with no
        // way forward from inside the app.
        if (sdkInt >= ANDROID_17) {
            add(ACCESS_LOCAL_NETWORK)
        }
    }

    /**
     * Not referenced through [Manifest.permission] so that the app still builds against an SDK
     * older than 37; the string is stable platform API.
     */
    const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    private const val ANDROID_17 = 37

    /** Everything worth asking for at once, including the nice-to-have. */
    fun allRequestable(context: Context): List<String> = buildList {
        addAll(requiredForTransfer(context))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.filter { isGrantable(context, it) }

    /**
     * Whether this device's platform defines [permission] at all.
     *
     * Requiring one it has never heard of is a gate that cannot open: the check reports it
     * denied, requesting it returns denied without showing the user anything, and the app sits
     * insisting on something nobody can give it. An SDK check is the real guard — this is the
     * backstop, so that the next platform to move a permission around costs Beam a capability
     * rather than the whole app.
     */
    private fun isGrantable(context: Context, permission: String): Boolean =
        runCatching { context.packageManager.getPermissionInfo(permission, 0) }.isSuccess

    fun missingForTransfer(context: Context): List<String> =
        requiredForTransfer(context).filter { !isGranted(context, it) }

    fun missingRequestable(context: Context): List<String> =
        allRequestable(context).filter { !isGranted(context, it) }

    fun canTransfer(context: Context): Boolean = missingForTransfer(context).isEmpty()

    /**
     * Whether the user picked "Approximate" in the Android 12 location dialog.
     *
     * That dialog grants coarse and denies fine, which is useless to a radio scan, and the
     * system will keep offering the same two choices however many times Beam asks. Left
     * unexplained it is a loop with no exit: the user picks approximate, Beam says it still
     * needs permission, the user picks approximate again. The only way out is to say which
     * of the two buttons is the one that works.
     */
    fun needsPreciseLocation(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION) &&
            !isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)

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
