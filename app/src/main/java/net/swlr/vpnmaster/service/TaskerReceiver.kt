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
import net.swlr.vpnmaster.data.repository.SettingsRepository
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
        const val EXTRA_TOKEN = "token"
    }

    @Inject lateinit var profileRepository: ProfileRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Received action: ${intent.action}")

        // Token check happens before dispatching to per-action handlers so
        // the auth result is logged once and rejected commands don't even
        // touch the repository or service. goAsync() inside the handlers
        // expects a coroutine context, but the auth check itself is fast and
        // synchronous against in-memory flows.
        val pending = goAsync()
        scope.launch {
            try {
                if (!isAuthorized(intent)) {
                    Log.w(TAG, "Rejecting ${intent.action}: token missing or invalid")
                    return@launch
                }
                when (intent.action) {
                    ACTION_START_TUNNEL -> handleStart(context, intent)
                    ACTION_STOP_TUNNEL -> handleStop(context)
                    ACTION_LIST_PROFILES -> handleListProfiles(context)
                    else -> Log.w(TAG, "Unknown action: ${intent.action}")
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun isAuthorized(intent: Intent): Boolean {
        val required = settingsRepository.taskerAuthRequired.first()
        if (!required) return true
        val expected = settingsRepository.taskerAuthToken.first()
        val provided = intent.getStringExtra(EXTRA_TOKEN) ?: return false
        // Constant-time compare so a co-resident attacker can't time-side-
        // channel the token. Equal-length compare is a prerequisite — strings
        // of different lengths are never equal anyway, so short-circuit there.
        if (provided.length != expected.length) return false
        return java.security.MessageDigest.isEqual(
            provided.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8)
        )
    }

    private suspend fun handleStart(context: Context, intent: Intent) {
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME)

        if (profileId == null && profileName == null) {
            Log.e(TAG, "START_TUNNEL requires either profile_id or profile_name extra")
            return
        }

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
                return
            }

            Log.i(TAG, "Starting tunnel: ${profile.name} (${profile.id})")
            val serviceIntent = Intent(context, VpnMasterService::class.java).apply {
                action = VpnMasterService.ACTION_CONNECT
                putExtra(VpnMasterService.EXTRA_PROFILE_ID, profile.id)
            }
            context.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start tunnel", e)
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
        // throw here.
        try {
            context.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch STOP_TUNNEL — FGS start blocked from background", e)
        }
    }

    private suspend fun handleListProfiles(context: Context) {
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
        }
    }
}
