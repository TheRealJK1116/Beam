package dev.beam.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import dev.beam.BeamPermissions
import dev.beam.R

/**
 * Everything the user needs to make Beam work, and a way to fix it when it does not.
 *
 * Beam previously fired a bare system dialog on first launch and, if the user said no, simply
 * stopped working with nothing on screen to explain why or any route back. That is fine while
 * the only installs are over adb with the permissions pre-granted; it is useless to anyone
 * else, which is the whole point of this screen.
 *
 * Two states matter and they need different buttons. A permission that has merely not been
 * asked for yet can be asked for again. One the user has refused twice is permanently denied
 * by the platform — further requests do nothing at all — and the only honest move is to send
 * them to the system settings page.
 */
class PermissionUiState(
    val ready: Boolean,
    val needsLocationService: Boolean,
    val canAskAgain: Boolean,
    private val onRequest: () -> Unit,
    private val onOpenSettings: () -> Unit,
) {
    fun act() = if (canAskAgain && !needsLocationService) onRequest() else onOpenSettings()
}

@Composable
fun rememberPermissionUiState(): PermissionUiState {
    val context = LocalContext.current
    val activity = context.findActivity()

    // Re-read on resume: the user may have granted something in system settings and come
    // back, and nothing would otherwise tell us.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    // Set once the system dialog has been shown and dismissed, which is what distinguishes
    // "not asked yet" from "refused".
    var asked by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        asked = true
        refresh++
    }

    return remember(refresh, asked, activity) {
        val missing = BeamPermissions.missingRequestable(context)
        val locationNeeded = BeamPermissions.isLocationServiceRequired() &&
            !BeamPermissions.isLocationServiceOn(context)

        // shouldShowRequestPermissionRationale goes false both before the first ask and after
        // a permanent denial, so it only distinguishes the two once we have asked.
        val canAskAgain = !asked || missing.any { permission ->
            activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }

        PermissionUiState(
            ready = BeamPermissions.isReady(context),
            needsLocationService = locationNeeded,
            canAskAgain = canAskAgain,
            onRequest = {
                val stillMissing = BeamPermissions.missingRequestable(context)
                if (stillMissing.isNotEmpty()) launcher.launch(stillMissing.toTypedArray())
            },
            onOpenSettings = { context.openBeamSettings(locationNeeded) },
        )
    }
}

/**
 * Shown above the history while Beam cannot actually do its job.
 *
 * Deliberately a persistent banner rather than a one-shot dialog: a dialog dismissed by
 * accident on first launch leaves an app that silently never works.
 */
@Composable
fun PermissionBanner(state: PermissionUiState, modifier: Modifier = Modifier) {
    if (state.ready) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                stringResource(
                    if (state.needsLocationService) R.string.perm_location_title
                    else R.string.perm_title
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(
                    when {
                        state.needsLocationService -> R.string.perm_location_body
                        state.canAskAgain -> R.string.perm_body
                        else -> R.string.perm_denied_body
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = state::act) {
                    Text(
                        stringResource(
                            if (state.canAskAgain && !state.needsLocationService) R.string.action_grant
                            else R.string.action_open_settings
                        )
                    )
                }
            }
        }
    }
}

/** The app's own settings page, or the location toggle when that is what is missing. */
private fun Context.openBeamSettings(locationInstead: Boolean) {
    val intent = if (locationInstead) {
        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    }
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
