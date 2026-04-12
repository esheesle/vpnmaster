package net.swlr.vpnmaster.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import net.swlr.vpnmaster.R
import net.swlr.vpnmaster.ui.navigation.Screen
import net.swlr.vpnmaster.ui.theme.Green500
import net.swlr.vpnmaster.ui.theme.Orange500
import net.swlr.vpnmaster.ui.theme.Red500
import net.swlr.vpnmaster.viewmodel.HomeViewModel
import net.swlr.vpnmaster.vpn.VpnState

@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val vpnState by viewModel.vpnState.collectAsState()
    val stats by viewModel.statistics.collectAsState()
    val activeProfile by viewModel.activeProfile.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val defaultProfile by viewModel.defaultProfile.collectAsState()
    val selectedProfile by viewModel.selectedProfile.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.connect()
        }
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Status indicator
            StatusOrb(state = vpnState)

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = when (vpnState) {
                    VpnState.DISCONNECTED -> stringResource(R.string.vpn_disconnected)
                    VpnState.CONNECTING -> stringResource(R.string.vpn_connecting)
                    VpnState.CONNECTED -> stringResource(R.string.vpn_connected)
                    VpnState.DISCONNECTING -> stringResource(R.string.vpn_disconnecting)
                    VpnState.ERROR -> stringResource(R.string.vpn_error)
                    VpnState.RECONNECTING -> stringResource(R.string.vpn_reconnecting)
                },
                style = MaterialTheme.typography.titleLarge,
                color = when (vpnState) {
                    VpnState.CONNECTED -> Green500
                    VpnState.ERROR -> Red500
                    VpnState.CONNECTING, VpnState.RECONNECTING -> Orange500
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )

            activeProfile?.let { profile ->
                Text(
                    text = "${profile.name} (${profile.displayType})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Profile selector
            if (profiles.isNotEmpty() && vpnState == VpnState.DISCONNECTED) {
                ProfileSelector(
                    profiles = profiles,
                    selected = selectedProfile ?: defaultProfile,
                    onSelect = { viewModel.selectProfile(it) }
                )
                Spacer(modifier = Modifier.height(24.dp))
            } else if (profiles.isEmpty()) {
                Text(
                    text = stringResource(R.string.select_profile),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = { navController.navigate(Screen.Profiles.route) }) {
                    Text("Go to Profiles")
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Connect/Disconnect button
            val isConnected = vpnState == VpnState.CONNECTED
            val isTransitioning = vpnState == VpnState.CONNECTING ||
                    vpnState == VpnState.DISCONNECTING ||
                    vpnState == VpnState.RECONNECTING

            Button(
                onClick = {
                    if (isConnected) {
                        viewModel.disconnect()
                    } else {
                        val permissionIntent = viewModel.getVpnPermissionIntent()
                        if (permissionIntent != null) {
                            vpnPermissionLauncher.launch(permissionIntent)
                        } else {
                            viewModel.connect()
                        }
                    }
                },
                enabled = !isTransitioning && (isConnected || selectedProfile != null || defaultProfile != null),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected) Red500 else MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = if (isConnected) Icons.Default.LockOpen else Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isConnected) stringResource(R.string.disconnect)
                    else stringResource(R.string.connect),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            // Statistics
            if (vpnState == VpnState.CONNECTED) {
                Spacer(modifier = Modifier.height(32.dp))
                StatsCard(
                    bytesReceived = stats.bytesReceived,
                    bytesSent = stats.bytesSent,
                    durationMillis = stats.durationMillis
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun StatusOrb(state: VpnState) {
    val targetColor = when (state) {
        VpnState.CONNECTED -> Green500
        VpnState.ERROR -> Red500
        VpnState.CONNECTING, VpnState.RECONNECTING, VpnState.DISCONNECTING -> Orange500
        VpnState.DISCONNECTED -> Color.Gray
    }

    val color by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(500),
        label = "orb_color"
    )

    val scale by animateFloatAsState(
        targetValue = if (state == VpnState.CONNECTED) 1.1f else 1f,
        animationSpec = tween(300),
        label = "orb_scale"
    )

    Box(
        modifier = Modifier
            .size(120.dp)
            .scale(scale)
            .background(color.copy(alpha = 0.15f), CircleShape)
            .padding(20.dp)
            .background(color.copy(alpha = 0.3f), CircleShape)
            .padding(16.dp)
            .background(color, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (state == VpnState.CONNECTED) Icons.Default.Lock else Icons.Default.LockOpen,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(32.dp)
        )
    }
}

@Composable
private fun ProfileSelector(
    profiles: List<net.swlr.vpnmaster.data.model.VpnProfile>,
    selected: net.swlr.vpnmaster.data.model.VpnProfile?,
    onSelect: (net.swlr.vpnmaster.data.model.VpnProfile) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = selected?.name ?: stringResource(R.string.no_profile_selected),
                    style = MaterialTheme.typography.titleMedium
                )
                selected?.let {
                    Text(
                        text = "${it.displayType} - ${it.serverAddress}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(profile.name, fontWeight = FontWeight.Medium)
                            Text(
                                "${profile.displayType} - ${profile.serverAddress}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    onClick = {
                        onSelect(profile)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun StatsCard(bytesReceived: Long, bytesSent: Long, durationMillis: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem(
                label = stringResource(R.string.bytes_received),
                value = formatBytes(bytesReceived)
            )
            StatItem(
                label = stringResource(R.string.bytes_sent),
                value = formatBytes(bytesSent)
            )
            StatItem(
                label = stringResource(R.string.connection_time),
                value = formatDuration(durationMillis)
            )
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.2f GB".format(gb)
}

private fun formatDuration(millis: Long): String {
    val seconds = millis / 1000
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%02d:%02d".format(m, s)
}
