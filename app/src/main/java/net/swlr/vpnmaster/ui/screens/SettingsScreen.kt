package net.swlr.vpnmaster.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import net.swlr.vpnmaster.R
import net.swlr.vpnmaster.backup.BackupRepository
import net.swlr.vpnmaster.data.model.VpnProfile
import net.swlr.vpnmaster.ui.navigation.Routes
import net.swlr.vpnmaster.viewmodel.BackupUiState
import net.swlr.vpnmaster.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    navController: NavHostController? = null,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val watchdogEnabled by viewModel.watchdogEnabled.collectAsState()
    val watchdogInterval by viewModel.watchdogIntervalSeconds.collectAsState()
    val watchdogProbeMaxFailures by viewModel.watchdogProbeMaxFailures.collectAsState()
    val autoConnectOnBoot by viewModel.autoConnectOnBoot.collectAsState()
    val diagnosticLoggingEnabled by viewModel.diagnosticLoggingEnabled.collectAsState()
    val taskerAuthRequired by viewModel.taskerAuthRequired.collectAsState()
    val taskerAuthToken by viewModel.taskerAuthToken.collectAsState()
    val batteryOptIgnored by viewModel.batteryOptIgnored.collectAsState()
    val showDisconnectedNotification by viewModel.showDisconnectedNotification.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val backupUiState by viewModel.backupUiState.collectAsState()
    val context = LocalContext.current

    // Battery optimization state lives in system settings; re-poll whenever
    // this screen comes back into composition (user returning from Settings).
    LaunchedEffect(Unit) { viewModel.refreshBatteryOptStatus() }

    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingExportUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val createBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(BackupRepository.MIME_TYPE)
    ) { uri ->
        if (uri != null) {
            pendingExportUri = uri
            showExportDialog = true
        }
    }

    val openBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingImportUri = uri
            showImportDialog = true
        }
    }

    LaunchedEffect(backupUiState) {
        when (val s = backupUiState) {
            is BackupUiState.ExportSuccess -> {
                Toast.makeText(context, s.message, Toast.LENGTH_SHORT).show()
                viewModel.acknowledgeBackupResult()
            }
            is BackupUiState.ImportSuccess -> {
                Toast.makeText(context, s.message, Toast.LENGTH_LONG).show()
                viewModel.acknowledgeBackupResult()
            }
            is BackupUiState.Failure -> {
                Toast.makeText(context, s.message, Toast.LENGTH_LONG).show()
                viewModel.acknowledgeBackupResult()
            }
            else -> {}
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(Modifier.height(24.dp))

        // Always-on VPN
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.always_on_vpn),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.always_on_vpn_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.always_on_vpn_instructions),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { viewModel.openVpnSettings() }) {
                    Text(stringResource(R.string.open_vpn_settings))
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Status notification when disconnected
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.status_notif_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.status_notif_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = showDisconnectedNotification,
                    onCheckedChange = { viewModel.setShowDisconnectedNotification(it) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Battery optimization
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.battery_opt_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        if (batteryOptIgnored) R.string.battery_opt_desc_unrestricted
                        else R.string.battery_opt_desc_restricted
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!batteryOptIgnored) {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { viewModel.requestIgnoreBatteryOptimizations() }) {
                        Text(stringResource(R.string.battery_opt_request_button))
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Watchdog
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.watchdog_enabled),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.watchdog_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = watchdogEnabled,
                        onCheckedChange = { viewModel.setWatchdogEnabled(it) }
                    )
                }

                if (watchdogEnabled) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "${stringResource(R.string.watchdog_interval)}: ${watchdogInterval}s",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = watchdogInterval.toFloat(),
                        onValueChange = { viewModel.setWatchdogInterval(it.toInt()) },
                        valueRange = 10f..120f,
                        steps = 10,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))
                    val probeLabel = if (watchdogProbeMaxFailures == 0) {
                        stringResource(R.string.watchdog_probe_failures_disabled_label)
                    } else {
                        "${stringResource(R.string.watchdog_probe_failures)}: $watchdogProbeMaxFailures"
                    }
                    Text(
                        text = probeLabel,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.watchdog_probe_failures_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Slider has 10 internal steps -> 11 stops total -> integers 0..10.
                    // 0 = probe-disabled mode (handshake-age fallback only).
                    Slider(
                        value = watchdogProbeMaxFailures.toFloat(),
                        onValueChange = { viewModel.setWatchdogProbeMaxFailures(it.toInt()) },
                        valueRange = 0f..10f,
                        steps = 9,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Auto-connect on boot
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.auto_connect_boot),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.auto_connect_boot_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoConnectOnBoot,
                    onCheckedChange = { viewModel.setAutoConnectOnBoot(it) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Tasker / Automation
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.tasker_integration),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.tasker_integration_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.tasker_auth_required),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.tasker_auth_required_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = taskerAuthRequired,
                        onCheckedChange = { viewModel.setTaskerAuthRequired(it) }
                    )
                }

                if (taskerAuthRequired) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.tasker_auth_token_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = taskerAuthToken,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("VPN Master Tasker token", taskerAuthToken))
                            Toast.makeText(context, context.getString(R.string.tasker_auth_token_copied), Toast.LENGTH_SHORT).show()
                        }) {
                            Text(stringResource(R.string.tasker_auth_copy))
                        }
                        OutlinedButton(onClick = { viewModel.regenerateTaskerToken() }) {
                            Text(stringResource(R.string.tasker_auth_regenerate))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.tasker_auth_extra_help),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (profiles.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.tasker_profile_ids),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    profiles.forEach { profile ->
                        TaskerProfileRow(profile, context)
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.tasker_actions_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.tasker_actions_help),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Diagnostic logs
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Diagnostic logs",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "In-app log of connection, reconnect, and network events. Copy or share for troubleshooting.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = diagnosticLoggingEnabled,
                        onCheckedChange = { viewModel.setDiagnosticLoggingEnabled(it) }
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { navController?.navigate(Routes.LOGS) },
                    enabled = navController != null
                ) {
                    Text("View logs")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Backup & Restore
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.backup_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.backup_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val name = context.getString(R.string.backup_default_filename) +
                                ".${BackupRepository.FILE_EXTENSION}"
                            createBackupLauncher.launch(name)
                        },
                        enabled = backupUiState !is BackupUiState.Working
                    ) {
                        Text(stringResource(R.string.backup_export))
                    }
                    OutlinedButton(
                        onClick = { openBackupLauncher.launch(arrayOf("*/*")) },
                        enabled = backupUiState !is BackupUiState.Working
                    ) {
                        Text(stringResource(R.string.backup_import))
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // About / Licenses
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "About",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Version, credits, and open-source license information.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { navController?.navigate(Routes.ABOUT) },
                    enabled = navController != null
                ) {
                    Text("About VPN Master")
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text = "VPN Master v${net.swlr.vpnmaster.BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }

    if (showExportDialog) {
        BackupPasswordDialog(
            title = stringResource(R.string.backup_password_export_title),
            confirmField = true,
            warningText = null,
            onDismiss = {
                showExportDialog = false
                pendingExportUri = null
            },
            onConfirm = { password ->
                pendingExportUri?.let { viewModel.exportBackup(it, password) }
                showExportDialog = false
                pendingExportUri = null
            }
        )
    }

    if (showImportDialog) {
        BackupPasswordDialog(
            title = stringResource(R.string.backup_password_import_title),
            confirmField = false,
            warningText = stringResource(R.string.backup_warn_overwrite),
            onDismiss = {
                showImportDialog = false
                pendingImportUri = null
            },
            onConfirm = { password ->
                pendingImportUri?.let { viewModel.importBackup(it, password) }
                showImportDialog = false
                pendingImportUri = null
            }
        )
    }
}

@Composable
private fun BackupPasswordDialog(
    title: String,
    confirmField: Boolean,
    warningText: String?,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val emptyMsg = stringResource(R.string.backup_password_required)
    val mismatchMsg = stringResource(R.string.backup_password_mismatch)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (warningText != null) {
                    Text(
                        text = warningText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text(stringResource(R.string.backup_password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                if (confirmField) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it; error = null },
                        label = { Text(stringResource(R.string.backup_password_confirm_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    password.isEmpty() -> error = emptyMsg
                    confirmField && password != confirm -> error = mismatchMsg
                    else -> onConfirm(password.toCharArray())
                }
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun TaskerProfileRow(profile: VpnProfile, context: Context) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Profile ID", profile.id))
                Toast.makeText(context, "ID copied", Toast.LENGTH_SHORT).show()
            }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = profile.name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = profile.id.take(8) + "...",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
