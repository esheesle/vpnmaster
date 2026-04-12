package net.swlr.vpnmaster.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.swlr.vpnmaster.data.repository.SettingsRepository
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val autoConnect = settingsRepository.autoConnectOnBoot.first()
                if (autoConnect) {
                    val serviceIntent = Intent(context, VpnMasterService::class.java)
                    context.startForegroundService(serviceIntent)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
