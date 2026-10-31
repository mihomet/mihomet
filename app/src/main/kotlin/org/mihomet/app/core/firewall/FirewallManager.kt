package org.mihomet.app.core.firewall

import com.topjohnwu.superuser.Shell
import org.mihomet.app.data.preferences.FirewallEngineType
import org.mihomet.app.data.preferences.PerAppMode

class FirewallManager {
    private val nftablesEngine = NftablesEngine()
    private val iptablesEngine = IptablesEngine()

    private var activeEngine: FirewallEngine? = null

    fun detectEngine(preferred: FirewallEngineType): FirewallEngine {
        return when (preferred) {
            FirewallEngineType.NFTABLES -> nftablesEngine
            FirewallEngineType.IPTABLES -> iptablesEngine
            FirewallEngineType.AUTO -> {
                val hasNft = runCatching {
                    val result = Shell.cmd("nft --version").exec()
                    result.isSuccess && result.out.any { it.contains("nftables", ignoreCase = true) }
                }.getOrDefault(false)

                if (hasNft) nftablesEngine else iptablesEngine
            }
        }
    }

    fun setup(
        engineType: FirewallEngineType,
        tproxyPort: Int,
        dnsPort: Int,
        perAppMode: PerAppMode,
        appUids: Set<Int>,
        ipv6Enabled: Boolean
    ): Result<Unit> {
        val engine = detectEngine(engineType)
        activeEngine = engine
        // dnsPort is accepted for API compatibility (RootTProxyService passes
        // settings.dnsPort) but intentionally not forwarded: DNS interception is
        // destination-port-53 based and protocol-agnostic, and the core answers
        // through its own `dns.listen` socket, so the configured DNS port plays no
        // role in the firewall rules (F06 — the parameter used to be dead there too).
        return engine.setup(tproxyPort, perAppMode, appUids, ipv6Enabled)
    }

    fun teardown(ipv6Enabled: Boolean): Result<Unit> {
        val r1 = nftablesEngine.teardown(ipv6Enabled)
        val r2 = iptablesEngine.teardown(ipv6Enabled)
        activeEngine = null
        return if (r1.isSuccess || r2.isSuccess) Result.success(Unit) else r1
    }
}
