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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import net.swlr.vpnmaster.R
import net.swlr.vpnmaster.data.model.IkeV2AuthMethod
import net.swlr.vpnmaster.data.model.IkeV2Config
import net.swlr.vpnmaster.data.model.VpnType
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
            // Import buttons
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

            // Profile name
            OutlinedTextField(
                value = currentProfile.name,
                onValueChange = { viewModel.updateEditingProfile(currentProfile.copy(name = it)) },
                label = { Text(stringResource(R.string.profile_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(12.dp))

            // VPN type selector
            VpnTypeSelector(
                selected = currentProfile.type,
                onSelect = { viewModel.changeVpnType(it) }
            )

            Spacer(Modifier.height(12.dp))

            // Server address
            OutlinedTextField(
                value = currentProfile.serverAddress,
                onValueChange = { viewModel.updateEditingProfile(currentProfile.copy(serverAddress = it)) },
                label = { Text(stringResource(R.string.server_address)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(16.dp))

            // Type-specific config
            when (currentProfile.type) {
                VpnType.WIREGUARD -> WireGuardConfigForm(
                    config = currentProfile.wireGuardConfig ?: WireGuardConfig(),
                    onConfigChange = {
                        viewModel.updateEditingProfile(currentProfile.copy(wireGuardConfig = it))
                    }
                )
                VpnType.IKEV2 -> IkeV2ConfigForm(
                    config = currentProfile.ikeV2Config ?: IkeV2Config(),
                    onConfigChange = {
                        viewModel.updateEditingProfile(currentProfile.copy(ikeV2Config = it))
                    }
                )
            }

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VpnTypeSelector(selected: VpnType, onSelect: (VpnType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = when (selected) {
                VpnType.WIREGUARD -> "WireGuard"
                VpnType.IKEV2 -> "IKEv2 / IPsec"
            },
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.vpn_type)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("WireGuard") },
                onClick = { onSelect(VpnType.WIREGUARD); expanded = false }
            )
            DropdownMenuItem(
                text = { Text("IKEv2 / IPsec") },
                onClick = { onSelect(VpnType.IKEV2); expanded = false }
            )
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

            OutlinedTextField(
                value = config.privateKey,
                onValueChange = { onConfigChange(config.copy(privateKey = it)) },
                label = { Text(stringResource(R.string.wg_private_key)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = config.addresses.joinToString(", "),
                onValueChange = {
                    onConfigChange(config.copy(addresses = it.split(",").map { s -> s.trim() }))
                },
                label = { Text(stringResource(R.string.wg_addresses)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = config.dnsServers.joinToString(", "),
                onValueChange = {
                    onConfigChange(config.copy(dnsServers = it.split(",").map { s -> s.trim() }))
                },
                label = { Text(stringResource(R.string.wg_dns_servers)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
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

    // Peers
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

            OutlinedTextField(
                value = peer.preSharedKey ?: "",
                onValueChange = { onPeerChange(peer.copy(preSharedKey = it.ifBlank { null })) },
                label = { Text(stringResource(R.string.wg_peer_preshared_key)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
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

            OutlinedTextField(
                value = peer.allowedIPs.joinToString(", "),
                onValueChange = {
                    onPeerChange(peer.copy(allowedIPs = it.split(",").map { s -> s.trim() }))
                },
                label = { Text(stringResource(R.string.wg_peer_allowed_ips)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = peer.persistentKeepalive?.toString() ?: "",
                onValueChange = { onPeerChange(peer.copy(persistentKeepalive = it.toIntOrNull())) },
                label = { Text(stringResource(R.string.wg_peer_keepalive)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IkeV2ConfigForm(config: IkeV2Config, onConfigChange: (IkeV2Config) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "IKEv2 Configuration",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))

            // Auth method selector
            var authExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = authExpanded, onExpandedChange = { authExpanded = it }) {
                OutlinedTextField(
                    value = when (config.authMethod) {
                        IkeV2AuthMethod.CERTIFICATE -> "Certificate"
                        IkeV2AuthMethod.EAP -> "EAP (Username/Password)"
                        IkeV2AuthMethod.CERTIFICATE_AND_EAP -> "Certificate + EAP"
                    },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.ikev2_auth_method)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(authExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(expanded = authExpanded, onDismissRequest = { authExpanded = false }) {
                    IkeV2AuthMethod.entries.forEach { method ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    when (method) {
                                        IkeV2AuthMethod.CERTIFICATE -> "Certificate"
                                        IkeV2AuthMethod.EAP -> "EAP (Username/Password)"
                                        IkeV2AuthMethod.CERTIFICATE_AND_EAP -> "Certificate + EAP"
                                    }
                                )
                            },
                            onClick = {
                                onConfigChange(config.copy(authMethod = method))
                                authExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = config.remoteId ?: "",
                onValueChange = { onConfigChange(config.copy(remoteId = it.ifBlank { null })) },
                label = { Text(stringResource(R.string.ikev2_remote_id)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = config.localId ?: "",
                onValueChange = { onConfigChange(config.copy(localId = it.ifBlank { null })) },
                label = { Text(stringResource(R.string.ikev2_local_id)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // EAP fields
            if (config.authMethod == IkeV2AuthMethod.EAP ||
                config.authMethod == IkeV2AuthMethod.CERTIFICATE_AND_EAP
            ) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = config.username ?: "",
                    onValueChange = { onConfigChange(config.copy(username = it.ifBlank { null })) },
                    label = { Text(stringResource(R.string.ikev2_username)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = config.password ?: "",
                    onValueChange = { onConfigChange(config.copy(password = it.ifBlank { null })) },
                    label = { Text(stringResource(R.string.ikev2_password)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
            }

            // Advanced: Handshake & Tunnel Settings
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Handshake & Tunnel",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = config.ikeProposal ?: "",
                onValueChange = { onConfigChange(config.copy(ikeProposal = it.ifBlank { null })) },
                label = { Text(stringResource(R.string.ikev2_ike_proposal)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = config.espProposal ?: "",
                onValueChange = { onConfigChange(config.copy(espProposal = it.ifBlank { null })) },
                label = { Text(stringResource(R.string.ikev2_esp_proposal)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = config.mtu?.toString() ?: "",
                onValueChange = { onConfigChange(config.copy(mtu = it.toIntOrNull())) },
                label = { Text("MTU (default: 1400)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = config.dpdDelaySeconds?.toString() ?: "",
                onValueChange = { onConfigChange(config.copy(dpdDelaySeconds = it.toIntOrNull())) },
                label = { Text("DPD Interval, seconds (default: 30)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = config.rekeyIkeMinutes?.toString() ?: "",
                    onValueChange = { onConfigChange(config.copy(rekeyIkeMinutes = it.toIntOrNull())) },
                    label = { Text("IKE Rekey, min (default: 240)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = config.rekeyEspMinutes?.toString() ?: "",
                    onValueChange = { onConfigChange(config.copy(rekeyEspMinutes = it.toIntOrNull())) },
                    label = { Text("ESP Rekey, min (default: 60)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        }
    }
}
