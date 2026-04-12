package net.swlr.vpnmaster.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.compose.ui.viewinterop.AndroidView
import net.swlr.vpnmaster.R
import net.swlr.vpnmaster.data.model.SplitTunnelMode
import net.swlr.vpnmaster.viewmodel.SplitTunnelViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitTunnelScreen(
    navController: NavController,
    profileId: String,
    viewModel: SplitTunnelViewModel = hiltViewModel()
) {
    val apps by viewModel.apps.collectAsState()
    val splitConfig by viewModel.splitConfig.collectAsState()
    val showSystemApps by viewModel.showSystemApps.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val uiMessage by viewModel.uiMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(profileId) {
        viewModel.loadProfile(profileId)
    }

    LaunchedEffect(uiMessage) {
        uiMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.split_tunnel_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                // Mode selector
                var modeExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = modeExpanded,
                    onExpandedChange = { modeExpanded = it }
                ) {
                    OutlinedTextField(
                        value = when (splitConfig.mode) {
                            SplitTunnelMode.DISABLED -> stringResource(R.string.split_tunnel_disabled)
                            SplitTunnelMode.EXCLUDE_APPS -> stringResource(R.string.split_tunnel_exclude)
                            SplitTunnelMode.INCLUDE_APPS -> stringResource(R.string.split_tunnel_include)
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.split_tunnel_mode)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modeExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = modeExpanded,
                        onDismissRequest = { modeExpanded = false }
                    ) {
                        SplitTunnelMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (mode) {
                                            SplitTunnelMode.DISABLED -> stringResource(R.string.split_tunnel_disabled)
                                            SplitTunnelMode.EXCLUDE_APPS -> stringResource(R.string.split_tunnel_exclude)
                                            SplitTunnelMode.INCLUDE_APPS -> stringResource(R.string.split_tunnel_include)
                                        }
                                    )
                                },
                                onClick = {
                                    viewModel.setMode(mode)
                                    modeExpanded = false
                                }
                            )
                        }
                    }
                }

                if (splitConfig.mode != SplitTunnelMode.DISABLED) {
                    Spacer(Modifier.height(12.dp))

                    // Route-based split tunneling
                    OutlinedTextField(
                        value = splitConfig.excludedRoutes.joinToString("\n"),
                        onValueChange = { viewModel.updateExcludedRoutes(it) },
                        label = { Text(stringResource(R.string.excluded_routes)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = splitConfig.includedRoutes.joinToString("\n"),
                        onValueChange = { viewModel.updateIncludedRoutes(it) },
                        label = { Text(stringResource(R.string.included_routes)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )

                    Spacer(Modifier.height(12.dp))

                    // Search and filter
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.setSearchQuery(it) },
                        label = { Text(stringResource(R.string.search_apps)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.show_system_apps),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = showSystemApps,
                            onCheckedChange = { viewModel.setShowSystemApps(it) }
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                }
            }

            if (splitConfig.mode != SplitTunnelMode.DISABLED) {
                // App list
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(apps, key = { it.packageName }) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = app.isSelected,
                                onCheckedChange = { viewModel.toggleApp(app.packageName) }
                            )
                            app.icon?.let { icon ->
                                AndroidView(
                                    factory = { ctx ->
                                        android.widget.ImageView(ctx).apply {
                                            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                                        }
                                    },
                                    update = { it.setImageDrawable(icon) },
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = app.label,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Save button
            Button(
                onClick = { viewModel.save() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(stringResource(R.string.save))
            }
        }
    }
}
