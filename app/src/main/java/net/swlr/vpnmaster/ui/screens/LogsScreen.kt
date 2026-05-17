package net.swlr.vpnmaster.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import net.swlr.vpnmaster.R
import net.swlr.vpnmaster.logging.LogBuffer
import net.swlr.vpnmaster.logging.LogEntry
import java.io.File

private val LEVEL_LABELS = listOf("All", "E", "W", "I", "D", "V")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(navController: NavHostController) {
    val entries by LogBuffer.flow.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var autoScroll by remember { mutableStateOf(true) }

    var tagFilter by remember { mutableStateOf("") }
    var levelFilter by remember { mutableStateOf("All") }
    var levelExpanded by remember { mutableStateOf(false) }

    // derivedStateOf so filtering only re-runs when its inputs (entries, tag, level)
    // actually change, not on every recomposition.
    val filtered by remember {
        derivedStateOf {
            val tag = tagFilter.trim().lowercase()
            entries.filter { entry ->
                (levelFilter == "All" || entry.level == levelFilter[0]) &&
                        (tag.isEmpty() || entry.tag.lowercase().contains(tag))
            }
        }
    }

    LaunchedEffect(filtered.size, autoScroll) {
        if (autoScroll && filtered.isNotEmpty()) {
            listState.scrollToItem(filtered.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs (${filtered.size}/${entries.size})") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { copyToClipboard(context) },
                    modifier = Modifier.weight(1f)
                ) { Text("Copy") }
                Button(
                    onClick = { shareLogs(context) },
                    modifier = Modifier.weight(1f)
                ) { Text("Share") }
                OutlinedButton(
                    onClick = {
                        LogBuffer.clear()
                        Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Clear") }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = tagFilter,
                    onValueChange = { tagFilter = it },
                    label = { Text(stringResource(R.string.logs_filter_tag)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                ExposedDropdownMenuBox(
                    expanded = levelExpanded,
                    onExpandedChange = { levelExpanded = it },
                    modifier = Modifier.width(110.dp)
                ) {
                    OutlinedTextField(
                        value = levelFilter,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.logs_filter_level)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(levelExpanded) },
                        modifier = Modifier.menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = levelExpanded,
                        onDismissRequest = { levelExpanded = false }
                    ) {
                        LEVEL_LABELS.forEach { lvl ->
                            DropdownMenuItem(
                                text = { Text(lvl) },
                                onClick = {
                                    levelFilter = lvl
                                    levelExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { autoScroll = !autoScroll }
                ) {
                    Text(if (autoScroll) "Pause scroll" else "Resume scroll")
                }
            }

            Spacer(Modifier.height(4.dp))

            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp)
                ) {
                    items(filtered) { entry -> LogRow(entry) }
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val color = when (entry.level) {
        'E' -> Color(0xFFE57373)
        'W' -> Color(0xFFFFB74D)
        'I' -> MaterialTheme.colorScheme.onSurface
        'D' -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = LogBuffer.format(entry),
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
    )
}

private fun copyToClipboard(context: Context) {
    val text = LogBuffer.snapshot()
    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cb.setPrimaryClip(ClipData.newPlainText("VpnMaster logs", text))
    Toast.makeText(context, "Copied ${text.length} chars", Toast.LENGTH_SHORT).show()
}

private fun shareLogs(context: Context) {
    // Write to a file and share via FileProvider rather than putting the log
    // text in EXTRA_TEXT. Intent extras travel through a Binder transaction
    // (~1MB hard ceiling); a few days of reconnect-heavy logs blow past that
    // and the chooser silently fails to appear. EXTRA_STREAM hands over a
    // URI so the receiving app reads the bytes on its own.
    val text = LogBuffer.snapshot()
    val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
    val outFile = File(sharedDir, "vpnmaster-logs.txt")
    try {
        outFile.writeText(text)
    } catch (e: Exception) {
        Toast.makeText(context, "Could not write logs: ${e.message}", Toast.LENGTH_LONG).show()
        return
    }
    val uri = try {
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile
        )
    } catch (e: Exception) {
        Toast.makeText(context, "Could not share logs: ${e.message}", Toast.LENGTH_LONG).show()
        return
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "VpnMaster logs")
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(Intent.createChooser(intent, "Share logs").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    })
}
