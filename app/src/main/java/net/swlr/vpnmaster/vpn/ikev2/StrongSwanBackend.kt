package net.swlr.vpnmaster.vpn.ikev2

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.swlr.vpnmaster.data.model.IkeV2AuthMethod
import net.swlr.vpnmaster.data.model.IkeV2Config
import net.swlr.vpnmaster.data.model.VpnProfile
import net.swlr.vpnmaster.vpn.VpnBackend
import net.swlr.vpnmaster.vpn.VpnState
import net.swlr.vpnmaster.vpn.VpnStatistics
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StrongSwanBackend @Inject constructor(
    @ApplicationContext private val context: Context
) : VpnBackend {

    companion object {
        private const val TAG = "StrongSwanBackend"
    }

    private var connectedSince: Long = 0
    private var currentState: VpnState = VpnState.DISCONNECTED
    private var builderAdapter: CharonBridge.BuilderAdapter? = null

    val isNativeAvailable: Boolean
        get() = CharonBridge.isAvailable()

    fun initializeNative(adapter: CharonBridge.BuilderAdapter) {
        if (!CharonBridge.loadLibrary()) {
            throw IllegalStateException(
                "strongSwan native libraries not found. " +
                "Build them from the submodule first. See BUILD.md."
            )
        }

        builderAdapter = adapter
        val logDir = File(context.filesDir, "strongswan")
        logDir.mkdirs()
        val logFile = File(logDir, "charon.log").absolutePath

        CharonBridge.initializeCharon(
            adapter,
            logFile,
            context.filesDir.absolutePath,
            false
        )
        Log.i(TAG, "Charon daemon initialized")
    }

    override suspend fun connect(profile: VpnProfile) = withContext(Dispatchers.IO) {
        val ikeConfig = profile.ikeV2Config
            ?: throw IllegalArgumentException("IKEv2 config is required")

        if (!CharonBridge.isAvailable()) {
            throw IllegalStateException("strongSwan native libraries not loaded")
        }

        currentState = VpnState.CONNECTING

        writeCharonConfig(ikeConfig)

        val type = when (ikeConfig.authMethod) {
            IkeV2AuthMethod.CERTIFICATE -> "ikev2-cert"
            IkeV2AuthMethod.EAP -> "ikev2-eap"
            IkeV2AuthMethod.CERTIFICATE_AND_EAP -> "ikev2-cert-eap"
        }

        Log.i(TAG, "Initiating $type connection to ${profile.serverAddress}")

        CharonBridge.initiate(
            type = type,
            gateway = profile.serverAddress,
            username = ikeConfig.username,
            password = ikeConfig.password,
            localId = ikeConfig.localId,
            remoteId = ikeConfig.remoteId ?: profile.serverAddress,
            certAlias = ikeConfig.certificateAlias,
            caCertAlias = ikeConfig.caCertificateAlias,
            ikeProposal = ikeConfig.ikeProposal,
            espProposal = ikeConfig.espProposal
        )

        connectedSince = System.currentTimeMillis()
    }

    /**
     * Write strongswan.conf with user-configured connection parameters.
     * The file is restricted to app-private storage (mode 0600 by default on Android).
     */
    private fun writeCharonConfig(config: IkeV2Config) {
        val configDir = File(context.filesDir, "strongswan")
        configDir.mkdirs()
        val configFile = File(configDir, "strongswan.conf")

        val dpdDelay = config.dpdDelaySeconds ?: 30
        val rekeyIke = (config.rekeyIkeMinutes ?: 240) * 60
        val rekeyEsp = (config.rekeyEspMinutes ?: 60) * 60
        val mtu = config.mtu ?: 1400

        configFile.writeText(buildString {
            appendLine("charon {")
            appendLine("    dpd_delay = $dpdDelay")
            appendLine("    tun_mtu = $mtu")
            appendLine("    retransmit_timeout = 2.0")
            appendLine("    retransmit_tries = 5")
            appendLine("    rekey_time = $rekeyIke")
            appendLine("    child {")
            appendLine("        rekey_time = $rekeyEsp")
            appendLine("    }")
            appendLine("    close_ike_on_child_failure = yes")
            appendLine("}")
        })
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        currentState = VpnState.DISCONNECTING
        try {
            if (CharonBridge.isAvailable()) {
                CharonBridge.deinitializeCharon()
                Log.i(TAG, "Charon daemon deinitialized")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deinitializing charon: ${e.message}", e)
        } finally {
            currentState = VpnState.DISCONNECTED
            connectedSince = 0
            cleanupConfigFile()
        }
    }

    override suspend fun getState(): VpnState = withContext(Dispatchers.IO) {
        if (!CharonBridge.isAvailable()) return@withContext currentState

        when (CharonBridge.getState()) {
            CharonBridge.STATE_DISABLED -> VpnState.DISCONNECTED
            CharonBridge.STATE_CONNECTING -> VpnState.CONNECTING
            CharonBridge.STATE_CONNECTED -> VpnState.CONNECTED
            CharonBridge.STATE_DISCONNECTING -> VpnState.DISCONNECTING
            else -> {
                if (CharonBridge.getErrorState() != CharonBridge.ERROR_NONE) {
                    VpnState.ERROR
                } else {
                    currentState
                }
            }
        }
    }

    override suspend fun getStatistics(): VpnStatistics = withContext(Dispatchers.IO) {
        if (!CharonBridge.isAvailable()) return@withContext VpnStatistics()
        VpnStatistics(
            bytesReceived = CharonBridge.getRxBytes(),
            bytesSent = CharonBridge.getTxBytes(),
            connectedSince = connectedSince
        )
    }

    fun shutdown() {
        if (CharonBridge.isAvailable()) {
            try {
                CharonBridge.deinitializeCharon()
            } catch (e: Exception) {
                Log.w(TAG, "Error during shutdown: ${e.message}")
            }
        }
        builderAdapter = null
        cleanupConfigFile()
    }

    private fun cleanupConfigFile() {
        try {
            File(context.filesDir, "strongswan/strongswan.conf").delete()
        } catch (_: Exception) {}
    }
}
