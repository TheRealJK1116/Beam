package dev.beam.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.beam.BeamApplication
import dev.beam.BeamContainer
import dev.beam.BeamDebug
import dev.beam.BeamPermissions
import dev.beam.R
import dev.beam.data.history.TransferRecord
import dev.beam.ui.theme.BeamTheme
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Beam's only conventional screen: what has been beamed, and the handful of settings. The app
 * itself is the tap; this is the part you open when you want to find a file again.
 */
class MainActivity : ComponentActivity() {

    private val container get() = (application as BeamApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            BeamTheme {
                val navController = rememberNavController()
                val permissions = rememberBeamPermissions()

                LaunchedEffect(Unit) { permissions.requestIfNeeded() }

                NavHost(navController, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            container = container,
                            onOpenSettings = { navController.navigate("settings") },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                            onOpenDev = { navController.navigate("dev") },
                        )
                    }
                    composable("dev") {
                        DevScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ permissions

/**
 * Asks for whatever is still missing, once, when the home screen opens. The real enforcement
 * lives in [dev.beam.BeamPermissions] and is re-checked by every entry point, because the
 * share sheet and the NFC receive path can both run without this screen ever being seen.
 */
@Composable
private fun rememberBeamPermissions(): PermissionRequester {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    return remember(context) {
        PermissionRequester {
            val missing = BeamPermissions.missingRequestable(context)
            if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
        }
    }
}

private fun interface PermissionRequester {
    fun requestIfNeeded()
}

// ------------------------------------------------------------------------ home

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(container: BeamContainer, onOpenSettings: () -> Unit) {
    val history by remember { container.database.transfers().recent() }
        .collectAsState(initial = emptyList())
    val context = LocalContext.current

    // Beam used to be reachable only from the share sheet, which made it both undiscoverable
    // and impossible to start a beam from the app you are looking at.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) context.startBeam(uris) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, stringResource(R.string.title_settings))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Default.NearMe, contentDescription = null) },
                text = { Text(stringResource(R.string.action_send_something)) },
            )
        },
    ) { insets ->
        if (history.isEmpty()) {
            EmptyHistory(Modifier.padding(insets))
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    top = insets.calculateTopPadding() + 8.dp,
                    bottom = insets.calculateBottomPadding() + 24.dp,
                    start = 16.dp,
                    end = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(history, key = { it.id }) { record -> HistoryRow(record) }
            }
        }
    }
}

@Composable
private fun EmptyHistory(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.home_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.home_empty_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HistoryRow(record: TransferRecord) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (record.succeeded) {
                MaterialTheme.colorScheme.surfaceContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        ),
    ) {
        ListItem(
            colors = androidx.compose.material3.ListItemDefaults.colors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
            leadingContent = {
                Icon(
                    if (record.outgoing) Icons.Default.CallMade else Icons.Default.CallReceived,
                    contentDescription = null,
                )
            },
            headlineContent = { Text(record.label) },
            supportingContent = {
                val when_ = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(record.completedAt))
                Text(
                    buildString {
                        append(record.peerName)
                        if (record.sizeBytes > 0) append(" · ${record.sizeBytes.formatBytes()}")
                        append(" · $when_")
                        record.failureReason?.let { append(" · $it") }
                    }
                )
            },
        )
    }
}

/**
 * Hands the chosen files to [SendActivity] as an ordinary share intent, so the in-app path and
 * the share-sheet path converge immediately and there is only one send flow to maintain.
 */
private fun Context.startBeam(uris: List<Uri>) {
    val intent = Intent(this, SendActivity::class.java).apply {
        type = "*/*"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (uris.size == 1) {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            action = Intent.ACTION_SEND_MULTIPLE
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    }
    startActivity(intent)
}

// -------------------------------------------------------------------- settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(container: BeamContainer, onBack: () -> Unit, onOpenDev: () -> Unit) {
    val settings by container.settingsSnapshot.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var nameDraft by remember(settings.deviceName) { mutableStateOf(settings.deviceName) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { insets ->
        Column(
            Modifier.padding(insets).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SettingSwitch(
                title = stringResource(R.string.setting_receiving),
                subtitle = stringResource(R.string.setting_receiving_sub),
                checked = settings.receivingEnabled,
                onChange = { scope.launch { container.settings.setReceivingEnabled(it) } },
            )

            SettingSwitch(
                title = stringResource(R.string.setting_auto_accept),
                subtitle = stringResource(R.string.setting_auto_accept_sub),
                checked = settings.autoAcceptKnownDevices,
                onChange = { scope.launch { container.settings.setAutoAcceptKnownDevices(it) } },
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = nameDraft,
                onValueChange = { nameDraft = it.take(32) },
                label = { Text(stringResource(R.string.setting_device_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = { scope.launch { container.settings.setDeviceName(nameDraft) } },
                enabled = nameDraft.isNotBlank() && nameDraft != settings.deviceName,
            ) { Text(stringResource(R.string.action_save)) }

            Spacer(Modifier.height(12.dp))

            TextButton(onClick = { scope.launch { container.database.transfers().clear() } }) {
                Text(stringResource(R.string.setting_clear_history))
            }

            if (BeamDebug.IS_SIMULATION_AVAILABLE) {
                TextButton(onClick = onOpenDev) { Text(stringResource(R.string.setting_developer)) }
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
