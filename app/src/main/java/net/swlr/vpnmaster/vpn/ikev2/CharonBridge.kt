package net.swlr.vpnmaster.vpn.ikev2

import android.os.Build

/**
 * JNI bridge to the strongSwan charon daemon.
 *
 * The native methods are implemented in the strongSwan source at:
 *   external/strongswan/src/frontends/android/app/src/main/jni/libandroidbridge/
 *
 * The native library (libandroidbridge.so) must be built from the strongSwan
 * submodule before the app can use IKEv2. See BUILD.md for instructions.
 */
object CharonBridge {

    private var loaded = false

    fun loadLibrary(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("androidbridge")
            loaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    fun isAvailable(): Boolean = loaded

    // --- Native methods implemented by strongSwan's androidbridge ---

    /**
     * Initialize the charon daemon.
     * @param builder VPN service builder adapter for creating the TUN device
     * @param logFile path to the charon log file
     * @param appDir path to the app's internal storage directory
     * @param byod whether to enable BYOD features
     */
    @JvmStatic
    external fun initializeCharon(
        builder: BuilderAdapter,
        logFile: String,
        appDir: String,
        byod: Boolean
    )

    @JvmStatic
    external fun deinitializeCharon()

    @JvmStatic
    external fun initiate(
        type: String,
        gateway: String,
        username: String?,
        password: String?,
        localId: String?,
        remoteId: String?,
        certAlias: String?,
        caCertAlias: String?,
        ikeProposal: String?,
        espProposal: String?
    )

    @JvmStatic
    external fun getState(): Int

    @JvmStatic
    external fun getErrorState(): Int

    @JvmStatic
    external fun getImcState(): Int

    @JvmStatic
    external fun getRxBytes(): Long

    @JvmStatic
    external fun getTxBytes(): Long

    // State constants matching strongSwan's native code
    const val STATE_DISABLED = 0
    const val STATE_CONNECTING = 1
    const val STATE_CONNECTED = 2
    const val STATE_DISCONNECTING = 3

    // Error constants
    const val ERROR_NONE = 0
    const val ERROR_AUTH_FAILED = 1
    const val ERROR_PEER_AUTH_FAILED = 2
    const val ERROR_LOOKUP_FAILED = 3
    const val ERROR_UNREACHABLE = 4
    const val ERROR_GENERIC = 5

    /**
     * Adapter interface that strongSwan's native code calls back to configure
     * the VPN tunnel via VpnService.Builder.
     */
    interface BuilderAdapter {
        fun addAddress(address: String, prefixLength: Int): Boolean
        fun addDnsServer(address: String): Boolean
        fun addRoute(address: String, prefixLength: Int): Boolean
        fun addSearchDomain(domain: String): Boolean
        fun setMtu(mtu: Int): Boolean
        fun establish(): Int
    }
}
