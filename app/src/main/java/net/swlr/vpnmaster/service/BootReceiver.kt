package net.swlr.vpnmaster.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.swlr.vpnmaster.data.repository.ProfileRepository
import net.swlr.vpnmaster.data.repository.SettingsRepository
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var profileRepository: ProfileRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!settingsRepository.autoConnectOnBoot.first()) return@launch

                // Resolve the profile here and dispatch ACTION_CONNECT explicitly. The
                // service's null-intent branch now refuses to auto-reconnect, so boot
                // (the legitimate auto-connect path) must pass an explicit profile ID.
                val lastId = settingsRepository.lastConnectedProfileId.first()
                val profile = (lastId?.let { profileRepository.getProfileById(it) })
                    ?: profileRepository.getDefaultProfile()
                    ?: return@launch

                val serviceIntent = Intent(context, VpnMasterService::class.java).apply {
                    action = VpnMasterService.ACTION_CONNECT
                    putExtra(VpnMasterService.EXTRA_PROFILE_ID, profile.id)
                }
                context.startForegroundService(serviceIntent)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
