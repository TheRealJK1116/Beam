package dev.beam.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.beam.BeamApplication
import dev.beam.BeamPermissions
import dev.beam.R
import dev.beam.core.model.BeamPayload
import dev.beam.core.session.HandshakeState
import dev.beam.feature.nfc.BeamReaderController
import dev.beam.feature.nfc.TapResult
import dev.beam.ui.components.BeamBlob
import dev.beam.ui.components.BeamShape
import dev.beam.ui.theme.BeamTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The send screen: the entire "choose Beam from the share sheet, touch the phones" flow.
 *
 * It is one screen with no controls beyond cancel, because the user's hands are busy holding
 * two phones together. Everything it has to say, it says through the blob and one line of text.
 */
class SendActivity : ComponentActivity() {

    private val container get() = (application as BeamApplication).container
    private val payload = MutableStateFlow<BeamPayload?>(null)
    private val resolveError = MutableStateFlow(false)
    private val ready = MutableStateFlow(false)

    /**
     * The share sheet can launch this screen without Beam ever having been opened, so this is
     * the first and only chance to ask. Reader mode is not armed until we are ready - tapping
     * phones and then failing inside Nearby with a status code is the worst possible way to
     * tell someone they need to grant a permission.
     */
    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { ready.value = BeamPermissions.isReady(this) }

    private lateinit var reader: BeamReaderController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        reader = BeamReaderController(this)

        lifecycleScope.launch {
            val resolved = IntentPayloads.from(this@SendActivity, intent)
            payload.value = resolved
            resolveError.value = resolved == null
        }

        setContent {
            BeamTheme {
                val state by container.coordinator.state.collectAsStateWithLifecycle()
                val current by payload.collectAsStateWithLifecycle()
                val failed by resolveError.collectAsStateWithLifecycle()
                val isReady by ready.collectAsStateWithLifecycle()

                SendScreen(
                    state = state,
                    payload = current,
                    nothingToSend = failed,
                    nfcEnabled = reader.isNfcEnabled,
                    needsPermissions = !isReady,
                    locationOff = BeamPermissions.isLocationServiceRequired() &&
                        !BeamPermissions.isLocationServiceOn(this@SendActivity),
                    onGrantPermissions = {
                        val missing = BeamPermissions.missingRequestable(this@SendActivity)
                        if (missing.isNotEmpty()) {
                            permissionLauncher.launch(missing.toTypedArray())
                        } else {
                            // Only the system location toggle is left, which needs Settings.
                            startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        }
                    },
                    onCancel = {
                        container.coordinator.cancel()
                        finish()
                    },
                    onDone = { finish() },
                )
            }
        }

        ready.value = BeamPermissions.isReady(this)
        awaitTap()
    }

    override fun onResume() {
        super.onResume()
        // They may have granted something in Settings and come back.
        ready.value = BeamPermissions.isReady(this)
    }

    /**
     * Reader mode runs for as long as this screen is up. The first tap that yields a usable
     * token starts the transfer; a tap that does not is reported and the reader keeps listening,
     * because the usual cause is simply a slightly misaligned phone.
     */
    private fun awaitTap() {
        lifecycleScope.launch {
            // Hold off until Beam can actually complete a transfer.
            ready.first { it }
            reader.taps().collect { result ->
                when (result) {
                    is TapResult.Success -> {
                        val toSend = payload.value ?: return@collect
                        if (!container.coordinator.isBusy) {
                            container.coordinator.beginSend(result.token, toSend)
                        }
                    }

                    is TapResult.Failure -> tapFailures.value = result.error.message
                }
            }
        }
    }

    private val tapFailures = MutableStateFlow<String?>(null)

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && !container.coordinator.state.value.isTerminal) {
            container.coordinator.cancel()
        }
    }
}

@Composable
private fun SendScreen(
    state: HandshakeState,
    payload: BeamPayload?,
    nothingToSend: Boolean,
    nfcEnabled: Boolean,
    needsPermissions: Boolean,
    locationOff: Boolean,
    onGrantPermissions: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var lastShape by remember { mutableStateOf(BeamShape.Idle) }

    val shape = if (needsPermissions) BeamShape.Failed else state.toBlobShape()
    val progress = (state as? HandshakeState.Transferring)?.fraction ?: 0f

    // A beam is something you feel as much as see — one buzz when it starts moving, a firmer
    // one when it lands.
    LaunchedEffect(shape) {
        if (shape != lastShape) {
            when (shape) {
                BeamShape.Transferring -> haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                BeamShape.Done -> haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                else -> Unit
            }
            lastShape = shape
        }
    }

    // Completion is its own reward; get off the screen rather than making them tap OK.
    LaunchedEffect(state) {
        if (state is HandshakeState.Complete) {
            delay(1_200)
            onDone()
        }
    }

    Scaffold { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BeamBlob(shape = shape, progress = progress)

            Spacer(Modifier.height(40.dp))

            AnimatedContent(
                targetState = when {
                    needsPermissions && locationOff -> stringResource(R.string.send_location_off_title)
                    needsPermissions -> stringResource(R.string.send_permissions_title)
                    else -> state.headline(nothingToSend, nfcEnabled)
                },
                transitionSpec = {
                    (fadeIn(spring(stiffness = Spring.StiffnessMedium)))
                        .togetherWith(fadeOut(spring(stiffness = Spring.StiffnessMedium)))
                },
                label = "headline",
            ) { headline ->
                Text(
                    text = headline,
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = if (needsPermissions) {
                    stringResource(
                        if (locationOff) R.string.send_location_off_subtitle
                        else R.string.send_permissions_subtitle
                    )
                } else {
                    state.subtitle(payload)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            if (payload != null && state !is HandshakeState.Complete) {
                Spacer(Modifier.height(32.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(payload.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            payload.sizeBytes.formatBytes(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            when {
                needsPermissions -> Button(
                    onClick = onGrantPermissions,
                    modifier = Modifier.height(56.dp),
                ) {
                    Text(
                        stringResource(
                            if (locationOff) R.string.action_open_settings else R.string.action_grant
                        )
                    )
                }

                state is HandshakeState.Failed -> Button(onClick = onCancel) {
                    Text(stringResource(R.string.action_close))
                }

                state is HandshakeState.Complete -> Unit

                else -> TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }
}

private fun HandshakeState.toBlobShape(): BeamShape = when (this) {
    HandshakeState.Idle, is HandshakeState.AwaitingTap -> BeamShape.Idle
    is HandshakeState.TokenAcquired, is HandshakeState.Discovering, is HandshakeState.Authenticating ->
        BeamShape.Searching
    is HandshakeState.AwaitingAcceptance -> BeamShape.Searching
    is HandshakeState.Transferring -> BeamShape.Transferring
    is HandshakeState.Complete -> BeamShape.Done
    is HandshakeState.Failed -> BeamShape.Failed
}

@Composable
private fun HandshakeState.headline(nothingToSend: Boolean, nfcEnabled: Boolean): String = when {
    nothingToSend -> stringResource(R.string.send_nothing_title)
    !nfcEnabled && this is HandshakeState.AwaitingTap -> stringResource(R.string.send_nfc_off_title)
    else -> when (this) {
        HandshakeState.Idle, is HandshakeState.AwaitingTap -> stringResource(R.string.send_tap_title)
        is HandshakeState.TokenAcquired, is HandshakeState.Discovering ->
            stringResource(R.string.send_connecting_title)
        is HandshakeState.Authenticating -> stringResource(R.string.send_verifying_title)
        is HandshakeState.AwaitingAcceptance -> stringResource(R.string.send_waiting_title)
        is HandshakeState.Transferring -> stringResource(R.string.send_sending_title)
        is HandshakeState.Complete -> stringResource(R.string.send_done_title)
        is HandshakeState.Failed -> error.message
    }
}

@Composable
private fun HandshakeState.subtitle(payload: BeamPayload?): String = when (this) {
    HandshakeState.Idle, is HandshakeState.AwaitingTap -> stringResource(R.string.send_tap_subtitle)
    is HandshakeState.Transferring ->
        "${(fraction * 100).toInt()}% · ${peer.displayName}"
    is HandshakeState.Complete -> stringResource(R.string.send_done_subtitle, peer.displayName)
    is HandshakeState.Failed -> stringResource(R.string.send_failed_subtitle)
    else -> payload?.label.orEmpty()
}
