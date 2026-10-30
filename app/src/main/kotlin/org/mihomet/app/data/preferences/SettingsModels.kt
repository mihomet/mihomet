package org.mihomet.app.data.preferences

import org.mihomet.app.core.CoreConstants

enum class FirewallEngineType {
    AUTO,
    NFTABLES,
    IPTABLES
}

enum class PerAppMode {
    ALL,
    WHITELIST,
    BLACKLIST
}

enum class ProxyMode(val apiValue: String) {
    RULE("rule"),
    GLOBAL("global"),
    DIRECT("direct")
}

/**
 * Typed performance override layer (CMFA-style, minimal subset): these settings are
 * applied by [org.mihomet.app.core.config.ConfigGenerator] to ANY profile and always
 * take priority over the values coming from the profile YAML itself.
 */
object ProfileOverrides {
    /** Valid mihomo log-level values, in UI cycling order (info → warning → error → silent). */
    val LOG_LEVELS = listOf("info", "warning", "error", "silent")

    /** Fallback when the stored/constructed log-level value is not a valid core value. */
    const val DEFAULT_LOG_LEVEL = "info"

    fun isValidLogLevel(level: String): Boolean = level in LOG_LEVELS
}

/**
 * TUN interface options (Advanced settings): validated `tun.stack` values and the
 * MTU clamp range. Shared by [SettingsRepository] (persist/read-time sanitization),
 * [org.mihomet.app.core.config.ConfigGenerator] (generate-time sanitization) and the
 * VPN Builder in MihometVpnService (interface MTU).
 */
object TunOptions {
    /** Valid mihomo `tun.stack` values, in UI cycling order (gvisor → system → mixed). */
    val STACKS = listOf("gvisor", "system", "mixed")

    /** Fallback when the stored/constructed stack value is not a valid core value. */
    const val DEFAULT_STACK = "gvisor"

    /** CMFA/FlClash standard TUN MTU (gVisor-friendly large packets). */
    const val DEFAULT_MTU = 9000

    /**
     * MTU clamp bounds. 1280 is the IPv6 minimum link MTU (anything below cannot
     * even carry IPv6); 65535 is the largest MTU a Linux TUN device accepts.
     */
    const val MIN_MTU = 1280
    const val MAX_MTU = 65535

    /** Values offered by the Settings screen tap-cycle (gvisor-friendly presets). */
    val MTU_CHOICES = listOf(1500, 8500, 9000)

    fun isValidStack(stack: String): Boolean = stack in STACKS

    /** Unknown/garbage stacks fall back to [DEFAULT_STACK]. */
    fun sanitizeStack(stack: String?): String = stack?.takeIf { isValidStack(it) } ?: DEFAULT_STACK

    /**
     * Out-of-range MTUs are CLAMPED (not replaced by the default): any value inside
     * [MIN_MTU]..[MAX_MTU] is legal for both Android TUN and mihomo, so clamping
     * preserves user intent while guaranteeing a valid device MTU.
     */
    fun sanitizeMtu(mtu: Int?): Int = (mtu ?: DEFAULT_MTU).coerceIn(MIN_MTU, MAX_MTU)
}

/** Persisted marker of the last successfully started service mode (autostart signal). */
object LastRunMode {
    const val VPN = "vpn"
    const val ROOT = "root"
}

data class AppSettings(
    val isRootMode: Boolean = false,
    val firewallEngine: FirewallEngineType = FirewallEngineType.AUTO,
    val perAppMode: PerAppMode = PerAppMode.ALL,
    val selectedAppUids: Set<Int> = emptySet(),
    val proxyMode: ProxyMode = ProxyMode.RULE,
    val selectedNodeName: String = "",
    val mixedPort: Int = CoreConstants.DEFAULT_MIXED_PORT,
    val tproxyPort: Int = CoreConstants.DEFAULT_TPROXY_PORT,
    val dnsPort: Int = CoreConstants.DEFAULT_DNS_PORT,
    val controllerPort: Int = CoreConstants.DEFAULT_CONTROLLER_PORT,
    val ipv6Enabled: Boolean = false,
    val dynamicColor: Boolean = true,
    val darkTheme: Boolean = true,
    val amoledBlack: Boolean = false,
    val showSpeedInNotification: Boolean = true,
    val customDns: String = "",
    val controllerSecret: String = "",
    val testUrl: String = "http://www.gstatic.com/generate_204",
    val testTimeoutMs: Int = 5000,
    // Typed profile override layer (applied by ConfigGenerator to any profile):
    val overrideUnifiedDelay: Boolean = false,
    val overrideTcpConcurrent: Boolean = true,
    val overrideLogLevel: String = ProfileOverrides.DEFAULT_LOG_LEVEL,
    val overrideSnifferEnabled: Boolean = true,
    // Autostart on boot: the BootReceiver starts the last successfully started
    // service ([lastMode]) when this is enabled. Default off — starting a VPN
    // tunnel without the user watching is opt-in.
    val autoStartOnBoot: Boolean = false,
    // "vpn" | "root" | "" — persisted by the services after a successful start
    // (proxy signal for the boot receiver, see [LastRunMode]).
    val lastMode: String = "",
    // TUN interface options (non-root VPN mode), see [TunOptions].
    val tunStack: String = TunOptions.DEFAULT_STACK,
    val tunMtu: Int = TunOptions.DEFAULT_MTU,
    // CMFA bypassPrivateNetwork pattern: route everything EXCEPT private networks
    // into the tunnel (see CoreConstants.TUN_BYPASS_LAN_ROUTES_*).
    val bypassLan: Boolean = false
)
