package dev.beam.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.beam.BeamApplication
import dev.beam.R
import dev.beam.TransferCoordinator
import dev.beam.core.session.HandshakeState
import dev.beam.ui.components.BeamBlob
import dev.beam.ui.components.BeamShape
import dev.beam.ui.theme.BeamTheme
import kotlinx.coroutines.delay

/**
 * The receive screen, shown over the lock screen by the transfer service's full-screen intent.
 *
 * It is a confirmation and a progress view, nothing else. The transfer belongs to
 * [TransferCoordinator], so dismissing this screen mid-beam does not interrupt it — the
 * notification keeps the progress visible.
 */
class ReceiveActivity : ComponentActivity() {

    private val container get() = (application as BeamApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        showWhenLockedCompat()

        setContent {
            BeamTheme {
                val state by container.coordinator.state.collectAsStateWithLifecycle()
                val pending by container.coordinator.pendingDecision.collectAsStateWithLifecycle()

                ReceiveScreen(
                    state = state,
                    pending = pending,
                    onAccept = { pending?.accept() },
                    onDecline = {
                        pending?.decline()
                        finish()
                    },
                    onDone = { finish() },
                )
            }
        }
    }

    /** The point of a beam is that it lands without unlocking and hunting for the app. */
    private fun showWhenLockedCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }
}

@Composable
private fun ReceiveScreen(
    state: HandshakeState,
    pending: TransferCoordinator.PendingDecision?,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onDone: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(pending) {
        if (pending != null) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    LaunchedEffect(state) {
        if (state is HandshakeState.Complete) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            delay(1_400)
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
            val shape = when (state) {
                is HandshakeState.Transferring -> BeamShape.Transferring
                is HandshakeState.Complete -> BeamShape.Done
                is HandshakeState.Failed -> BeamShape.Failed
                else -> BeamShape.Searching
            }

            BeamBlob(
                shape = shape,
                progress = (state as? HandshakeState.Transferring)?.fraction ?: 0f,
                size = 200.dp,
            )

            Spacer(Modifier.height(36.dp))

            when {
                pending != null -> {
                    Text(
                        stringResource(R.string.receive_offer_title, pending.peer.displayName),
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${pending.manifest.describeForUi()} · ${pending.manifest.totalBytes.formatBytes()}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(40.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        FilledTonalButton(
                            onClick = onDecline,
                            modifier = Modifier.weight(1f).height(64.dp),
                        ) { Text(stringResource(R.string.action_decline)) }

                        Button(
                            onClick = onAccept,
                            modifier = Modifier.weight(1f).height(64.dp),
                            colors = ButtonDefaults.buttonColors(),
                        ) { Text(stringResource(R.string.action_accept)) }
                    }
                }

                state is HandshakeState.Transferring -> {
                    Text(
                        stringResource(R.string.receive_receiving_title),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${(state.fraction * 100).toInt()}% · ${state.peer.displayName}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                state is HandshakeState.Complete -> {
                    Text(
                        stringResource(R.string.receive_done_title),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.receive_done_subtitle),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                state is HandshakeState.Failed -> {
                    Text(
                        state.error.message,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onDone) { Text(stringResource(R.string.action_close)) }
                }

                else -> Text(
                    stringResource(R.string.receive_waiting_title),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun dev.beam.core.model.PayloadManifest.describeForUi(): String = when (kind) {
    dev.beam.core.model.PayloadManifest.Kind.TEXT -> text.orEmpty().take(60)
    dev.beam.core.model.PayloadManifest.Kind.URL -> text.orEmpty()
    dev.beam.core.model.PayloadManifest.Kind.FILES ->
        if (files.size == 1) files.single().name else "${files.size} files"
}
