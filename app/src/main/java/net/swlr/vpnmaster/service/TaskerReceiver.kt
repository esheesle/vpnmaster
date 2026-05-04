package net.swlr.vpnmaster.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import net.swlr.vpnmaster.logging.AppLog as Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.swlr.vpnmaster.data.repository.ProfileRepository
import javax.inject.Inject

/**
 * BroadcastReceiver for external automation apps (Tasker, Automate, MacroDroid, etc.)
 * to start and stop VPN tunnels.
 *
 * Usage from Tasker "Send Intent" action:
 *
 *   Start tunnel by profile ID:
 *     Action:  net.swlr.vpnmaster.action.START_TUNNEL
 *     Extra:   profile_id:<uuid>
 *     Package: net.swlr.vpnmaster
 *     Target:  Broadcast Receiver
 *
 *   Start tunnel by profile name:
 *     Action:  net.swlr.vpnmaster.action.START_TUNNEL
 *     Extra:   profile_name:My VPN Profile
 *     Package: net.swlr.vpnmaster
 *     Target:  Broadcast Receiver
 *
 *   Stop tunnel:
 *     Action:  net.swlr.vpnmaster.action.STOP_TUNNEL
 *     Package: net.swlr.vpnmaster
 *     Target:  Broadcast Receiver
 *
 *   Stop tunnel (specific profile, for clarity):
 *     Action:  net.swlr.vpnmaster.action.STOP_TUNNEL
 *     Extra:   profile_id:<uuid>
 *     Package: net.swlr.vpnmaster
 *     Target:  Broadcast Receiver
 *
 *   List profiles (result broadcast):
 *     Action:  net.swlr.vpnmaster.action.LIST_PROFILES
 *     Package: net.swlr.vpnmaster
 *     Target:  Broadcast Receiver
 *     → Sends back: net.swlr.vpnmaster.action.PROFILE_LIST
 *       with extra "profiles" = "id1|name1\nid2|name2\n..."
 */
@AndroidEntryPoint
class TaskerReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TaskerReceiver"
        const val ACTION_START_TUNNEL = "net.swlr.vpnmaster.action.START_TUNNEL"
        const val ACTION_STOP_TUNNEL = "net.swlr.vpnmaster.action.STOP_TUNNEL"
        const val ACTION_LIST_PROFILES = "net.swlr.vpnmaster.action.LIST_PROFILES"
        const val ACTION_PROFILE_LIST = "net.swlr.vpnmaster.action.PROFILE_LIST"

        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_PROFILE_NAME = "profile_name"
        const val EXTRA_PROFILES = "profiles"
    }

    @Inject lateinit var profileRepository: ProfileRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Received action: ${intent.action}")

        when (intent.action) {
            ACTION_START_TUNNEL -> handleStart(context, intent)
            ACTION_STOP_TUNNEL -> handleStop(context)
            ACTION_LIST_PROFILES -> handleListProfiles(context)
            else -> Log.w(TAG, "Unknown action: ${intent.action}")
        }
    }

    private fun handleStart(context: Context, intent: Intent) {
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME)

        if (profileId == null && profileName == null) {
            Log.e(TAG, "START_TUNNEL requires either profile_id or profile_name extra")
            return
        }

        val pendingResult = goAsync()
        scope.launch {
            try {
                val profile = when {
                    profileId != null -> profileRepository.getProfileById(profileId)
                    profileName != null -> {
                        val all = profileRepository.getAllProfiles().first()
                        all.firstOrNull { it.name.equals(profileName, ignoreCase = true) }
                    }
                    else -> null
                }

                if (profile == null) {
                    Log.e(TAG, "Profile not found: id=$profileId name=$profileName")
                    return@launch
                }

                Log.i(TAG, "Starting tunnel: ${profile.name} (${profile.id})")
                val serviceIntent = Intent(context, VpnMasterService::class.java).apply {
                    action = VpnMasterService.ACTION_CONNECT
                    putExtra(VpnMasterService.EXTRA_PROFILE_ID, profile.id)
                }
                context.startForegroundService(serviceIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start tunnel", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleStop(context: Context) {
        Log.i(TAG, "Stopping tunnel")
        val serviceIntent = Intent(context, VpnMasterService::class.java).apply {
            action = VpnMasterService.ACTION_DISCONNECT
        }
        // Android 12+ throws ForegroundServiceStartNotAllowedException when an
        // FGS is started from the background without an exemption. Tasker
        // broadcasts arrive from the background by definition, so the call can
        // throw here even though the same call inside handleStart's coroutine
        // is already covered by its broad catch.
        try {
            context.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch STOP_TUNNEL — FGS start blocked from background", e)
        }
    }

    private fun handleListProfiles(context: Context) {
        val pendingResult = goAsync()
        scope.launch {
            try {
                val profiles = profileRepository.getAllProfiles().first()
                val result = profiles.joinToString("\n") { "${it.id}|${it.name}|${it.displayType}" }

                Log.i(TAG, "Sending profile list: ${profiles.size} profiles")
                val responseIntent = Intent(ACTION_PROFILE_LIST).apply {
                    putExtra(EXTRA_PROFILES, result)
                    setPackage(context.packageName)
                }
                context.sendBroadcast(responseIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to list profiles", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
