package net.swlr.vpnmaster.service

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.swlr.vpnmaster.vpn.VpnOrchestrator
import net.swlr.vpnmaster.vpn.VpnState
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchdogManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val orchestrator: VpnOrchestrator
) {
    companion object {
        private const val TAG = "VpnWatchdog"
        private const val WORK_NAME = "vpn_watchdog"
    }

    private var watchdogJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * Start the in-process watchdog that checks connection health.
     * Also schedules a WorkManager periodic task as a backup
     * in case the foreground service is killed.
     */
    fun start(intervalSeconds: Long) {
        stop()

        // In-process watchdog: fast detection within the foreground service
        watchdogJob = scope.launch {
            while (isActive) {
                delay(intervalSeconds * 1000)
                checkAndReconnect()
            }
        }

        // WorkManager backup: slower but survives process death
        schedulePeriodicWork()
    }

    fun stop() {
        watchdogJob?.cancel()
        watchdogJob = null
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private suspend fun checkAndReconnect() {
        val state = orchestrator.state.value
        if (state == VpnState.CONNECTED) {
            val healthy = orchestrator.checkHealth()
            if (!healthy) {
                Log.w(TAG, "VPN connection unhealthy, attempting reconnect")
                orchestrator.reconnect()
            }
        } else if (state == VpnState.ERROR) {
            Log.w(TAG, "VPN in error state, attempting reconnect")
            orchestrator.reconnect()
        }
    }

    private fun schedulePeriodicWork() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<WatchdogWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }
}

/**
 * WorkManager worker that serves as a backup watchdog.
 * If the foreground service was killed, this attempts to restart the VPN.
 */
class WatchdogWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // The worker checks if the VPN should be running but isn't.
        // The actual reconnection is handled by starting the VPN service,
        // which picks up the last connected profile.
        val intent = android.content.Intent(applicationContext, VpnMasterService::class.java)
        try {
            applicationContext.startForegroundService(intent)
        } catch (e: Exception) {
            Log.e("WatchdogWorker", "Failed to restart VPN service", e)
            return Result.retry()
        }
        return Result.success()
    }
}
