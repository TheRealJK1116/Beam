package dev.beam.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.widget.Toast
import androidx.compose.ui.platform.LocalClipboardManager
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
import dev.beam.ReceivedFile
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
                val permissions = rememberPermissionUiState()

                // Ask once on first launch so the common case needs no thought; if anything
                // is still missing afterwards the banner explains and offers a way back.
                var askedOnce by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    if (!askedOnce) {
                        askedOnce = true
                        if (!permissions.ready) permissions.act()
                    }
                }

                NavHost(navController, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            container = container,
                            permissions = permissions,
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

// ------------------------------------------------------------------------ home

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    container: BeamContainer,
    permissions: PermissionUiState,
    onOpenSettings: () -> Unit,
) {
    val history by remember { container.database.transfers().recent() }
        .collectAsState(initial = emptyList())
    val context = LocalContext.current

    // Beam used to be reachable only from the share sheet, which made it both undiscoverable
    // and impossible to start a beam from the app you are looking at.
    var showChooser by remember { mutableStateOf(false) }
    var composingText by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) context.startBeam(uris) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { tree -> if (tree != null) context.startBeamFolder(tree) }

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
                onClick = { showChooser = true },
                icon = { Icon(Icons.Default.NearMe, contentDescription = null) },
                text = { Text(stringResource(R.string.action_send_something)) },
            )
        },
    ) { insets ->
        if (showChooser) {
            SendChooser(
                onDismiss = { showChooser = false },
                onFiles = { showChooser = false; filePicker.launch(arrayOf("*/*")) },
                onFolder = { showChooser = false; folderPicker.launch(null) },
                onText = { showChooser = false; composingText = true },
            )
        }

        if (composingText) {
            TextComposer(
                onDismiss = { composingText = false },
                onSend = { text ->
                    composingText = false
                    context.startBeamText(text)
                },
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(
                top = insets.calculateTopPadding() + 8.dp,
                bottom = insets.calculateBottomPadding() + 96.dp,
                start = 16.dp,
                end = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { DeclinedTapBanner(container.declinedTaps) }
            item { PermissionBanner(permissions) }
            // After the permission banner: being allowed to beam comes before the switches
            // that beaming then needs.
            item { RadioBanner() }

            if (history.isEmpty()) {
                item { EmptyHistory(Modifier.height(320.dp)) }
            } else {
                items(history, key = { it.id }) { record ->
                    HistoryRow(record, onOpen = { context.openReceived(record) })
                }
            }
        }
    }
}

@Composable
private fun EmptyHistory(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
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
private fun HistoryRow(record: TransferRecord, onOpen: () -> Unit) {
    // Only a received file that still has somewhere to point can be opened; a sent file
    // belongs to whatever app it came from, and a failed one has nothing behind it.
    val openable = !record.outgoing && record.succeeded && !record.primaryUri.isNullOrBlank()

    Card(
        modifier = if (openable) Modifier.clickable(onClick = onOpen) else Modifier,
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

/** What can be beamed, offered where the user is already looking. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendChooser(
    onDismiss: () -> Unit,
    onFiles: () -> Unit,
    onFolder: () -> Unit,
    onText: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ListItem(
            leadingContent = { Icon(Icons.Default.InsertDriveFile, null) },
            headlineContent = { Text(stringResource(R.string.send_files)) },
            modifier = Modifier.clickable(onClick = onFiles),
        )
        ListItem(
            leadingContent = { Icon(Icons.Default.Folder, null) },
            headlineContent = { Text(stringResource(R.string.send_folder)) },
            supportingContent = { Text(stringResource(R.string.send_folder_sub)) },
            modifier = Modifier.clickable(onClick = onFolder),
        )
        ListItem(
            leadingContent = { Icon(Icons.Default.TextFields, null) },
            headlineContent = { Text(stringResource(R.string.send_text)) },
            modifier = Modifier.clickable(onClick = onText),
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** A scrap of text or a link, without having to find an app to share it from. */
@Composable
private fun TextComposer(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.send_text)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.send_text_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                TextButton(onClick = { clipboard.getText()?.text?.let { text = it } }) {
                    Text(stringResource(R.string.action_paste))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.action_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
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

/**
 * Opens a received file in whatever app handles it.
 *
 * The URI was recorded when the file was committed and has been sitting unused ever since,
 * which meant Beam could tell you something had arrived but not show it to you.
 */
private fun Context.openReceived(record: TransferRecord) {
    val uri = record.primaryUri?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return
    val type = ReceivedFile.mimeType(contentResolver, uri, record.label)

    // Installing is not opening: the installer has to be allowed to act on this app's behalf,
    // and the permission for that is one the user grants per-source in system settings.
    if (type == ReceivedFile.APK_MIME && !canInstallApks()) {
        requestInstallPermission()
        return
    }

    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, type)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    runCatching { startActivity(intent) }
        .onFailure {
            // Nothing on the device claims this type; say so rather than doing nothing.
            Toast.makeText(this, getString(R.string.history_cannot_open), Toast.LENGTH_SHORT).show()
        }
}

/** Granted per-source, and revocable, so it is checked at the moment of use rather than cached. */
private fun Context.canInstallApks(): Boolean = packageManager.canRequestPackageInstalls()

private fun Context.requestInstallPermission() {
    val intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.fromParts("package", packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    runCatching { startActivity(intent) }
        .onFailure {
            Toast.makeText(this, getString(R.string.history_cannot_install), Toast.LENGTH_LONG).show()
        }
}

/** A folder travels as its tree URI; SendActivity walks it off the main thread. */
private fun Context.startBeamFolder(tree: Uri) {
    // The picker's grant is transient and dies with the process. Taking it persistably means
    // a SendActivity restored from the task stack can still read the folder instead of
    // throwing SecurityException halfway through enumerating it.
    runCatching {
        contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    startActivity(
        Intent(this, SendActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(IntentPayloads.EXTRA_TREE_URI, tree.toString())
        }
    )
}

private fun Context.startBeamText(text: String) {
    startActivity(
        Intent(this, SendActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
    )
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
