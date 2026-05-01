package net.swlr.vpnmaster.logging

import android.util.Log
import net.swlr.vpnmaster.BuildConfig

/**
 * Drop-in replacement for [android.util.Log] that also writes to
 * [LogBuffer] so logs are visible in the in-app viewer and persisted to
 * disk. Keep call sites terse — same shape as android.util.Log.
 *
 * On release builds, both the in-app buffer and logcat output respect the
 * user's diagnostic-logging toggle. On debug builds logcat is always on so
 * developers can attach adb without flipping a setting.
 */
object AppLog {
    @Volatile var bufferEnabled: Boolean = true

    fun v(tag: String, msg: String) { emit('V', tag, msg, null); if (logcat()) Log.v(tag, msg) }
    fun d(tag: String, msg: String) { emit('D', tag, msg, null); if (logcat()) Log.d(tag, msg) }
    fun i(tag: String, msg: String) { emit('I', tag, msg, null); if (logcat()) Log.i(tag, msg) }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        emit('W', tag, msg, t)
        if (logcat()) {
            if (t != null) Log.w(tag, msg, t) else Log.w(tag, msg)
        }
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        emit('E', tag, msg, t)
        if (logcat()) {
            if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
        }
    }

    private fun logcat(): Boolean = BuildConfig.DEBUG || bufferEnabled

    private fun emit(level: Char, tag: String, msg: String, t: Throwable?) {
        if (!bufferEnabled) return
        val text = if (t != null) "$msg\n${Log.getStackTraceString(t)}" else msg
        LogBuffer.append(LogEntry(System.currentTimeMillis(), level, tag, text))
    }
}
