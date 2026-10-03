package dev.beam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch

/**
 * Warms the caches a tap depends on, right after a reboot.
 *
 * The HCE service itself needs no help being available — it is registered in the manifest and
 * the platform routes Beam's AID to it from boot. What this avoids is the first tap of the day
 * arriving before [BeamContainer] has read the user's settings or probed which transports work,
 * which would otherwise make that one tap slower or fall back to a guess.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        // Touching the container is the warm-up; Application.onCreate has already run by now.
        (context.applicationContext as? BeamApplication)?.container?.let { container ->
            container.scope.launch { container.refreshTransportAvailability() }
        }
    }
}

