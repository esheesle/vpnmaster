package net.swlr.vpnmaster.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import net.swlr.vpnmaster.R
import net.swlr.vpnmaster.data.model.WireGuardConfig
import net.swlr.vpnmaster.data.model.WireGuardPeer
import net.swlr.vpnmaster.ui.navigation.Routes
import net.swlr.vpnmaster.viewmodel.ProfileViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(
    navController: NavController,
    profileId: String?,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val profile by viewModel.editingProfile.collectAsState()
    val uiMessage by viewModel.uiMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.importFromUri(it) }
    }

    LaunchedEffect(profileId) {
        viewModel.loadProfile(profileId)
    }

    val currentEntry = navController.currentBackStackEntry
    LaunchedEffect(currentEntry) {
        val handle = currentEntry?.savedStateHandle ?: return@LaunchedEffect
        handle.getStateFlow<String?>("qr_data", null).collect { qrData ->
            if (qrData != null) {
                viewModel.importFromQrCode(qrData)
                handle.remove<String>("qr_data")
            }
        }
    }

    LaunchedEffect(uiMessage) {
        uiMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.profileSaved.collect {
            navController.popBackStack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (profileId == null) stringResource(R.string.add_profile)
                        else stringResource(R.string.edit_profile)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        val currentProfile = profile ?: return@Scaffold

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { filePickerLauncher.launch("*/*") },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Upload, null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.import_file))
                }
                OutlinedButton(
                    onClick = { navController.navigate(Routes.QR_SCAN) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.QrCodeScanner, null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.import_qr))
                }
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = currentProfile.name,
                onValueChange = { viewModel.updateEditingProfile(currentProfile.copy(name = it)) },
                label = { Text(stringResource(R.string.profile_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = currentProfile.serverAddress,
                onValueChange = { viewModel.updateEditingProfile(currentProfile.copy(serverAddress = it)) },
                label = { Text(stringResource(R.string.server_address)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(16.dp))

            WireGuardConfigForm(
                config = currentProfile.wireGuardConfig ?: WireGuardConfig(),
                onConfigChange = {
                    viewModel.updateEditingProfile(currentProfile.copy(wireGuardConfig = it))
                }
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { viewModel.saveProfile() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.save_profile))
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun WireGuardConfigForm(config: WireGuardConfig, onConfigChange: (WireGuardConfig) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Interface",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))

            SecretField(
                value = config.privateKey,
                onValueChange = { onConfigChange(config.copy(privateKey = it)) },
                label = stringResource(R.string.wg_private_key)
            )

            Spacer(Modifier.height(8.dp))

            CsvListField(
                initial = config.addresses,
                onListChange = { onConfigChange(config.copy(addresses = it)) },
                label = stringResource(R.string.wg_addresses)
            )

            Spacer(Modifier.height(8.dp))

            CsvListField(
                initial = config.dnsServers,
                onListChange = { onConfigChange(config.copy(dnsServers = it)) },
                label = stringResource(R.string.wg_dns_servers)
            )

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = config.listenPort?.toString() ?: "",
                    onValueChange = {
                        onConfigChange(config.copy(listenPort = it.toIntOrNull()))
                    },
                    label = { Text(stringResource(R.string.wg_listen_port)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = config.mtu?.toString() ?: "",
                    onValueChange = {
                        onConfigChange(config.copy(mtu = it.toIntOrNull()))
                    },
                    label = { Text(stringResource(R.string.wg_mtu)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        }
    }

    Spacer(Modifier.height(12.dp))

    config.peers.forEachIndexed { index, peer ->
        PeerForm(
            peer = peer,
            index = index,
            onPeerChange = { newPeer ->
                val newPeers = config.peers.toMutableList()
                newPeers[index] = newPeer
                onConfigChange(config.copy(peers = newPeers))
            },
            onRemove = if (config.peers.size > 1) {
                {
                    val newPeers = config.peers.toMutableList()
                    newPeers.removeAt(index)
                    onConfigChange(config.copy(peers = newPeers))
                }
            } else null
        )
        Spacer(Modifier.height(8.dp))
    }

    OutlinedButton(
        onClick = {
            onConfigChange(config.copy(peers = config.peers + WireGuardPeer()))
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.Add, null)
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.wg_add_peer))
    }
}

@Composable
private fun PeerForm(
    peer: WireGuardPeer,
    index: Int,
    onPeerChange: (WireGuardPeer) -> Unit,
    onRemove: (() -> Unit)?
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    text = "Peer ${index + 1}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                onRemove?.let {
                    IconButton(onClick = it) {
                        Icon(Icons.Default.Remove, stringResource(R.string.wg_remove_peer))
                    }
                }
            }

            OutlinedTextField(
                value = peer.publicKey,
                onValueChange = { onPeerChange(peer.copy(publicKey = it)) },
                label = { Text(stringResource(R.string.wg_peer_public_key)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(8.dp))

            SecretField(
                value = peer.preSharedKey ?: "",
                onValueChange = { onPeerChange(peer.copy(preSharedKey = it.ifBlank { null })) },
                label = stringResource(R.string.wg_peer_preshared_key)
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = peer.endpoint,
                onValueChange = { onPeerChange(peer.copy(endpoint = it)) },
                label = { Text(stringResource(R.string.wg_peer_endpoint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(8.dp))

            CsvListField(
                initial = peer.allowedIPs,
                onListChange = { onPeerChange(peer.copy(allowedIPs = it)) },
                label = stringResource(R.string.wg_peer_allowed_ips)
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = peer.persistentKeepalive?.toString() ?: "",
                onValueChange = { onPeerChange(peer.copy(persistentKeepalive = it.toIntOrNull())) },
                label = { Text(stringResource(R.string.wg_peer_keepalive) + " (default: 25)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
    }
}

/**
 * Editable text field whose displayed value is the user's raw input, not the
 * round-trip of `list.split(",").joinToString(", ")`. Splitting on every keystroke
 * caused the cursor to jump and made trailing commas/spaces unrepresentable.
 * The parent receives the parsed list only on each edit (which is fine — round
 * tripping the *internal* state is the only thing we needed to avoid).
 */
@Composable
private fun CsvListField(
    initial: List<String>,
    onListChange: (List<String>) -> Unit,
    label: String
) {
    // Local text is the source of truth while the user is typing — that's what
    // makes "10.0.0.1, " (trailing space) representable without the cursor jumping
    // back. If the *parent* swaps in a list that disagrees with what our local
    // text would parse to (e.g. config import populated the field from elsewhere),
    // resync — but only then. The previous remember(joined) reset on every
    // keystroke because parsing+rejoining the user's text rarely round-trips
    // exactly to the typed string.
    var text by remember { mutableStateOf(initial.joinToString(", ")) }
    val parsed = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    LaunchedEffect(initial) {
        if (initial != parsed) text = initial.joinToString(", ")
    }

    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onListChange(it.split(",").map { s -> s.trim() }.filter { s -> s.isNotEmpty() })
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = false,
        minLines = 1,
        maxLines = 4
    )
}

/**
 * Password-masked text field with a reveal toggle. Pasting a 44-char base64 key
 * is otherwise unverifiable.
 */
@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String
) {
    var revealed by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { revealed = !revealed }) {
                Icon(
                    imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (revealed) "Hide" else "Show"
                )
            }
        }
    )
}
