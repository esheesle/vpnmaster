package net.swlr.vpnmaster

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.swlr.vpnmaster.data.repository.SettingsRepository
import net.swlr.vpnmaster.logging.AppLog
import net.swlr.vpnmaster.logging.LogBuffer
import net.swlr.vpnmaster.service.WatchdogManager
import javax.inject.Inject

@HiltAndroidApp
class VpnMasterApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var watchdogManager: WatchdogManager

    // Application-lifetime scope for collectors that should live as long as the
    // process. Held as a member (not a one-shot CoroutineScope() call inside
    // onCreate) so the scope's Job is reachable from the Application instance —
    // makes it clear this is intentionally process-scoped, and gives us a single
    // place to cancel it if onTerminate ever needs to.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        LogBuffer.init(this)
        // Mirror the user's diagnostic-logging preference into AppLog so the
        // toggle takes effect immediately without requiring a restart. collectLatest
        // means changes are cheap — only the initial read does any real work.
        appScope.launch {
            settingsRepository.diagnosticLoggingEnabled.collectLatest { enabled ->
                AppLog.bufferEnabled = enabled
            }
        }
        // Bind watchdog liveness to orchestrator state, not VpnMasterService
        // lifecycle. Must run on every cold start; idempotent so a future
        // re-init (e.g. mid-process restore) is harmless.
        watchdogManager.initialize()
        AppLog.i("VpnMasterApp", "App process started")
    }
}
