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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import dev.beam.BeamPermissions
import dev.beam.BeamRadios
import dev.beam.DeclinedTaps
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
    /** True when the user chose "Approximate" and needs telling that it will not do. */
    val needsPreciseLocation: Boolean,
    /** True when the system may stop Beam running while it is not on screen. */
    val batteryRestricted: Boolean,
    private val onRequest: () -> Unit,
    private val onOpenSettings: () -> Unit,
    private val onRequestBatteryExemption: () -> Unit,
) {
    fun act() = if (canAskAgain && !needsLocationService) onRequest() else onOpenSettings()

    fun allowBackground() = onRequestBatteryExemption()
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
    // "not asked yet" from "refused". Saved rather than merely remembered: a rotation or a
    // trip through system settings would otherwise reset it to "never asked", and the banner
    // would go back to offering a Grant button that the platform silently ignores.
    var asked by rememberSaveable { mutableStateOf(false) }

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
            needsPreciseLocation = BeamPermissions.needsPreciseLocation(context),
            batteryRestricted = !BeamPermissions.isExemptFromBatteryOptimisation(context),
            onRequest = {
                val stillMissing = BeamPermissions.missingRequestable(context)
                if (stillMissing.isNotEmpty()) launcher.launch(stillMissing.toTypedArray())
            },
            onOpenSettings = { context.openBeamSettings(locationNeeded) },
            onRequestBatteryExemption = { context.requestBatteryExemption() },
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
    // Two tiers, and they are genuinely different. Missing permissions mean Beam cannot work
    // at all; battery optimisation means it works while you are looking at it and quietly
    // misses taps when you are not. Showing them as one undifferentiated warning would train
    // the user to ignore both.
    if (state.ready) {
        if (state.batteryRestricted) BackgroundBanner(state, modifier)
        return
    }

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
                        // Before the generic copy: this user has already granted something,
                        // so being told "Beam needs permission" again just looks broken.
                        state.needsPreciseLocation -> R.string.perm_precise_body
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

/**
 * Lists the switches that are off, with a way to each one.
 *
 * Separate from [PermissionBanner] because it is a different kind of statement. That one says
 * Beam is not allowed to work; this one says it is allowed and something else is in the way —
 * and unlike a permission, these can be true of a perfectly healthy phone a moment before a
 * beam. So this never blocks anything: it is the difference between a transfer that fails
 * mysteriously once the phones are already touching and one that never needed to be tried.
 */
@Composable
fun RadioBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var off by remember { mutableStateOf(BeamRadios.off(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { off = BeamRadios.off(context) }

    // A phone with no NFC cannot beam in either direction, and no setting will fix it. Say so
    // plainly rather than listing switches that are beside the point.
    if (!BeamRadios.hasNfc(context)) {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    stringResource(R.string.radio_no_nfc_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.radio_no_nfc_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        return
    }

    if (off.isEmpty()) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                stringResource(R.string.radio_title),
                style = MaterialTheme.typography.titleMedium,
            )
            off.forEach { radio ->
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(radio.bodyRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = {
                        runCatching { context.startActivity(BeamRadios.settingsIntent(radio)) }
                    }
                ) {
                    Text(stringResource(radio.actionRes()))
                }
            }
        }
    }
}

private fun BeamRadios.Radio.bodyRes(): Int = when (this) {
    BeamRadios.Radio.NFC -> R.string.radio_nfc_body
    BeamRadios.Radio.WIFI -> R.string.radio_wifi_body
    BeamRadios.Radio.BLUETOOTH -> R.string.radio_bluetooth_body
    BeamRadios.Radio.LOCATION -> R.string.radio_location_body
}

private fun BeamRadios.Radio.actionRes(): Int = when (this) {
    BeamRadios.Radio.NFC -> R.string.radio_nfc_action
    BeamRadios.Radio.WIFI -> R.string.radio_wifi_action
    BeamRadios.Radio.BLUETOOTH -> R.string.radio_bluetooth_action
    BeamRadios.Radio.LOCATION -> R.string.radio_location_action
}

/**
 * Owns up to a tap that was turned away while nobody was looking.
 *
 * The notification that was supposed to explain this needs a permission of its own, and the
 * user who has granted nothing is precisely the one whose taps get declined — so without this
 * the beam simply vanished, and the person who tapped got no answer and no reason.
 */
@Composable
fun DeclinedTapBanner(declinedTaps: DeclinedTaps, modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(declinedTaps.hasUnseen) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { show = declinedTaps.hasUnseen }
    if (!show) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                stringResource(R.string.declined_tap_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.declined_tap_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = { declinedTaps.markSeen(); show = false }) {
                Text(stringResource(R.string.action_got_it))
            }
        }
    }
}

/**
 * Shown once the permissions are in place but the system may still stop Beam running in the
 * background — which is when a tap on a closed phone goes unanswered.
 */
@Composable
private fun BackgroundBanner(state: PermissionUiState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                stringResource(R.string.perm_background_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.perm_background_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = state::allowBackground) {
                    Text(stringResource(R.string.action_allow_background))
                }
                // One UI runs its own scheme on top of Android's. Being exempt from battery
                // optimisation is not the same as being on Samsung's "never sleeping" list,
                // and an app it has put to sleep is not there to answer a tap - so the
                // battery button above can report success while taps still go unanswered.
                // There is no API to read that state, only this list to send people to.
                if (context.hasSamsungSleepSettings()) {
                    TextButton(onClick = { context.openSamsungSleepingApps() }) {
                        Text(stringResource(R.string.action_samsung_sleep))
                    }
                }
            }
        }
    }
}

/**
 * Asks to be exempted from battery optimisation.
 *
 * The direct dialog needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS and some devices refuse it
 * outright, so it falls back to the system list where the user can pick Beam themselves.
 */
private fun Context.requestBatteryExemption() {
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.fromParts("package", packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    runCatching { startActivity(direct) }
        .recoverCatching { startActivity(fallback) }
}

/**
 * Samsung's "never sleeping apps" list, which is a different thing from battery optimisation.
 *
 * Gated on the intent actually resolving rather than on the manufacturer string: a Galaxy with
 * Device Care disabled should not be offered a button that goes nowhere, and some non-Samsung
 * builds ship the same package.
 */
private const val SAMSUNG_SLEEP_ACTION = "com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY"
private const val SAMSUNG_SLEEP_PACKAGE = "com.samsung.android.lool"

/** 2 selects the "never sleeping apps" tab; 0 and 1 are the sleeping and deep-sleeping lists. */
private const val SAMSUNG_NEVER_SLEEPING = 2

private fun samsungSleepIntent(): Intent =
    Intent(SAMSUNG_SLEEP_ACTION)
        .setPackage(SAMSUNG_SLEEP_PACKAGE)
        .putExtra("activity_type", SAMSUNG_NEVER_SLEEPING)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private fun Context.hasSamsungSleepSettings(): Boolean =
    samsungSleepIntent().resolveActivity(packageManager) != null

private fun Context.openSamsungSleepingApps() {
    runCatching { startActivity(samsungSleepIntent()) }
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
