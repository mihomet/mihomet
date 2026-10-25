package org.mihomet.app.data.preferences

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
    val isRootMode: Boolean = true,
    val firewallEngine: FirewallEngineType = FirewallEngineType.AUTO,
    val perAppMode: PerAppMode = PerAppMode.ALL,
    val selectedAppUids: Set<Int> = emptySet(),
    val proxyMode: ProxyMode = ProxyMode.RULE,
    val mixedPort: Int = 7890,
    val tproxyPort: Int = 7893,
    val dnsPort: Int = 1053,
    val controllerPort: Int = 9090,
    val ipv6Enabled: Boolean = false,
    val dynamicColor: Boolean = true,
    val darkTheme: Boolean = true,
    val amoledBlack: Boolean = false,
    val controllerSecret: String = "mihomet_secret_token"
)
