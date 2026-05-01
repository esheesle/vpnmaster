package net.swlr.vpnmaster.logging

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val time: Long,
    val level: Char,
    val tag: String,
    val message: String
)

/**
 * In-memory ring buffer + disk-backed log store. Fed by [AppLog]. The UI
 * observes [flow]; the file at [logFile] survives process death so a crash
 * or system kill during a handoff can still be inspected after relaunch.
 *
 * Disk writes are batched: every [append] queues the formatted line and a
 * single coroutine flushes the queue every [FLUSH_INTERVAL_MS]. Reconnect
 * storms used to issue hundreds of file.length()+appendText calls per minute;
 * batching collapses each flush window into a single appendText.
 */
object LogBuffer {
    private const val CAPACITY = 3000
    private const val MAX_FILE_BYTES = 1_000_000L
    private const val ROTATED_SUFFIX = ".1"
    private const val FLUSH_INTERVAL_MS = 1_000L

    private val entries = ArrayDeque<LogEntry>()
    private val _flow = MutableStateFlow<List<LogEntry>>(emptyList())
    val flow: StateFlow<List<LogEntry>> = _flow.asStateFlow()

    private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var logFile: File? = null

    private val pendingWrites = ArrayDeque<String>()
    private var flushJob: Job? = null

    private val formatter = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        if (logFile != null) return
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        logFile = File(dir, "vpnmaster.log")
        writerScope.launch { loadExistingFile() }
    }

    @Synchronized
    fun append(entry: LogEntry) {
        entries.addLast(entry)
        while (entries.size > CAPACITY) entries.removeFirst()
        _flow.value = entries.toList()

        if (logFile == null) return
        pendingWrites.addLast(format(entry))
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = writerScope.launch {
            delay(FLUSH_INTERVAL_MS)
            flush()
        }
    }

    private fun flush() {
        val toWrite: List<String>
        synchronized(this) {
            if (pendingWrites.isEmpty()) return
            toWrite = pendingWrites.toList()
            pendingWrites.clear()
        }
        val file = logFile ?: return
        try {
            if (file.length() > MAX_FILE_BYTES) {
                val rotated = File(file.parentFile, file.name + ROTATED_SUFFIX)
                if (rotated.exists()) rotated.delete()
                file.renameTo(rotated)
            }
            file.appendText(toWrite.joinToString(separator = "\n", postfix = "\n"))
        } catch (_: Exception) {
            // Logging must never crash the app.
        }
    }

    fun snapshot(): String = synchronized(this) {
        entries.joinToString("\n") { format(it) }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        pendingWrites.clear()
        _flow.value = emptyList()
        logFile?.let { file ->
            writerScope.launch {
                try {
                    file.delete()
                    File(file.parentFile, file.name + ROTATED_SUFFIX).delete()
                } catch (_: Exception) {}
            }
        }
    }

    fun format(e: LogEntry): String =
        "${formatter.format(Date(e.time))} ${e.level}/${e.tag}: ${e.message}"

    private fun loadExistingFile() {
        val file = logFile ?: return
        if (!file.exists()) return
        try {
            val lines = file.readLines().takeLast(CAPACITY)
            val now = System.currentTimeMillis()
            val loaded = lines.map { line ->
                LogEntry(now, '-', "prev", line)
            }
            synchronized(this) {
                entries.addAll(loaded)
                while (entries.size > CAPACITY) entries.removeFirst()
                _flow.value = entries.toList()
            }
        } catch (_: Exception) {}
    }
}
