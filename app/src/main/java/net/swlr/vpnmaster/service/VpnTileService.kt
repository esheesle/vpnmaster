package net.swlr.vpnmaster.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import net.swlr.vpnmaster.logging.AppLog as Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.swlr.vpnmaster.R
import net.swlr.vpnmaster.vpn.VpnOrchestrator
import net.swlr.vpnmaster.vpn.VpnState
import javax.inject.Inject

/**
 * Quick Settings tile that allows toggling the VPN from the notification shade.
 *
 * Connects using the default profile, or the last connected profile.
 * Long-press opens the app.
 */
@AndroidEntryPoint
class VpnTileService : TileService() {

    companion object {
        private const val TAG = "VpnTileService"
    }

    @Inject lateinit var orchestrator: VpnOrchestrator

    private var listeningScope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        updateTile()

        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { listeningScope = it }
        s.launch {
            orchestrator.state.collect { updateTile() }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        listeningScope?.cancel()
        listeningScope = null
    }

    override fun onClick() {
        super.onClick()

        val currentState = orchestrator.state.value

        // CONNECTED is the only state where a tap means "disconnect". CONNECTING/RECONNECTING
        // taps are ignored so a rapid double-tap can't tear down a tunnel mid-handshake.
        when (currentState) {
            VpnState.CONNECTED -> {
                Log.i(TAG, "Tile clicked: disconnecting")
                startForegroundService(
                    Intent(this, VpnMasterService::class.java).apply {
                        action = VpnMasterService.ACTION_DISCONNECT
                    }
                )
            }
            VpnState.CONNECTING, VpnState.RECONNECTING -> {
                Log.i(TAG, "Tile clicked while $currentState — ignoring (connection in progress)")
            }
            else -> {
                // Synchronous dispatch keeps us inside the FGS-from-background grace
                // window granted by the tile tap. Profile resolution happens in the
                // service so we don't have to block the tile thread on a DB read.
                Log.i(TAG, "Tile clicked: resuming")
                startForegroundService(
                    Intent(this, VpnMasterService::class.java).apply {
                        action = VpnMasterService.ACTION_RESUME
                    }
                )
            }
        }
    }

    @Suppress("unused")
    private fun openAppFromTile() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launchIntent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val state = orchestrator.state.value

        when (state) {
            VpnState.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.tile_connected)
                tile.subtitle = orchestrator.activeProfile.value?.name
            }
            VpnState.CONNECTING, VpnState.RECONNECTING -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.tile_connecting)
                tile.subtitle = null
            }
            VpnState.DISCONNECTING -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = getString(R.string.tile_disconnecting)
                tile.subtitle = null
            }
            VpnState.DISCONNECTED -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.tile_disconnected)
                tile.subtitle = null
            }
            VpnState.ERROR -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.tile_error)
                tile.subtitle = null
            }
        }

        tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn_key)
        tile.updateTile()
    }
}
