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
    val testTimeoutMs: Int = 5000
)
