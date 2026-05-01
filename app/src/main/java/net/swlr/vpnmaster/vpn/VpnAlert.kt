package net.swlr.vpnmaster.vpn

/**
 * One-shot user-visible alerts emitted by [VpnOrchestrator]. Distinct from
 * [VpnState] (continuous, observed by the ongoing-status notification) — these
 * fire at moments where the user should be told *something happened* and
 * pre-empt being silently stuck. Channels are wired separately in
 * VpnMasterService so users can configure sound/importance per type.
 */
sealed class VpnAlert {
    /**
     * The retry loop in connect() exhausted [attempts] without a handshake.
     * Triggered only on explicit user-initiated connect paths (UI button,
     * Tasker, boot, service restart). Watchdog-driven recovery uses
     * [RecoveryFailed] instead.
     */
    data class InitialConnectFailed(
        val profileName: String,
        val attempts: Int,
        val cause: String
    ) : VpnAlert()

    /**
     * Background recovery (watchdog, network change, internet restored,
     * ERROR-state recovery) failed its first attempt. Fires once per
     * recovery cycle, only when the tunnel was actually up before — so a
     * fresh "never connected" failure is signalled by [InitialConnectFailed]
     * alone, not both.
     */
    data class RecoveryFailed(
        val profileName: String,
        val cause: String
    ) : VpnAlert()
}
