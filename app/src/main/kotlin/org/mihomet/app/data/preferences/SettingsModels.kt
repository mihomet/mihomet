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
    val overrideSnifferEnabled: Boolean = true
)
