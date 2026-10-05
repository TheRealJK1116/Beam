package dev.beam.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.beam.BeamContainer
import dev.beam.BeamDebug
import dev.beam.ui.components.BeamBlob
import dev.beam.ui.components.BeamShape

/**
 * Developer simulation tools. Reachable only from Settings in a debug build; the release
 * variant of `BeamDebug` makes every control here inert and the entry point invisible.
 *
 * The point is to make the hard-to-stage paths one tap away: a declined beam, a forged proof of
 * tap, a link that dies at 50%, a receiver that never shows up — plus a synthetic incoming tap
 * that drives the real notification, foreground service and confirmation screen on one phone.
 */
private const val INTERRUPT_AFTER_BYTES = 512L * 1024

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevScreen(container: BeamContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val log = remember { mutableStateListOf<String>() }
    var running by remember { mutableStateOf(false) }
    val transports by container.availableTransports.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Developer") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = {
                    TextButton(onClick = { log.clear() }) { Text("Clear") }
                },
            )
        },
    ) { insets ->
        Column(
            Modifier
                .padding(insets)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Transports available: " + transports.joinToString().ifBlank { "none probed yet" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FilledTonalButton(
                onClick = {
                    val ok = BeamDebug.simulateIncomingTap(context, container)
                    log.add(if (ok) "simulated tap: session published" else "simulated tap refused (receiving off?)")
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Simulate an incoming tap") }

            Text(
                "Mints a real session and starts the real receive flow on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            Text("Shape preview", style = MaterialTheme.typography.titleMedium)
            Text(
                "Every state the blob can be in, without staging a transfer to see it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            var previewShape by remember { mutableStateOf(BeamShape.Idle) }
            var previewProgress by remember { mutableFloatStateOf(0.45f) }

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                BeamBlob(shape = previewShape, progress = previewProgress)
            }

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BeamShape.entries.forEach { shape ->
                    FilterChip(
                        selected = previewShape == shape,
                        onClick = { previewShape = shape },
                        label = { Text(shape.name) },
                    )
                }
            }

            if (previewShape == BeamShape.Transferring) {
                Slider(value = previewProgress, onValueChange = { previewProgress = it })
                Text(
                    "${(previewProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text("Resume", style = MaterialTheme.typography.titleMedium)

            var armed by remember { mutableStateOf(BeamDebug.isInterruptionArmed) }
            FilledTonalButton(
                onClick = {
                    if (armed) {
                        BeamDebug.disarmInterruption()
                        armed = false
                        log.add("interruption disarmed")
                    } else {
                        BeamDebug.armSendInterruption(INTERRUPT_AFTER_BYTES)
                        armed = true
                        log.add("armed: the next send will be cut after ${INTERRUPT_AFTER_BYTES / 1024} KB")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (armed) "Disarm interruption" else "Interrupt the next send") }

            Text(
                "Cuts one real transfer at a fixed offset so the next attempt has something to " +
                    "resume from. Fires once, then disarms itself, so the retry is free to succeed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            Text("Scenarios", style = MaterialTheme.typography.titleMedium)

            BeamDebug.scenarios.forEach { scenario ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(scenario.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            scenario.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            enabled = !running,
                            onClick = {
                                running = true
                                scope.launch {
                                    runCatching { BeamDebug.runScenario(scenario) { log.add(it) } }
                                        .onFailure { log.add("scenario crashed: ${it.message}") }
                                    running = false
                                }
                            },
                        ) { Text(if (running) "Running…" else "Run") }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Log", style = MaterialTheme.typography.titleMedium)

            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 320.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(log) { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
