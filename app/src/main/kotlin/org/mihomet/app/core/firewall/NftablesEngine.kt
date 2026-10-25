package org.mihomet.app.core.firewall

import com.topjohnwu.superuser.Shell
import org.mihomet.app.data.preferences.PerAppMode

class NftablesEngine : FirewallEngine {
    override val name: String = "nftables"

    override fun setup(
        tproxyPort: Int,
        dnsPort: Int,
        perAppMode: PerAppMode,
        appUids: Set<Int>,
        ipv6Enabled: Boolean
    ): Result<Unit> = runCatching {
        // First clean up any stale rules
        teardown(ipv6Enabled)

        // Setup IP rule & route table 100
        Shell.cmd(
            "ip rule add fwmark 0x1/0x1 table 100",
            "ip route add local default dev lo table 100"
        ).exec()

        if (ipv6Enabled) {
            Shell.cmd(
                "ip -6 rule add fwmark 0x1/0x1 table 100",
                "ip -6 route add local default dev lo table 100"
            ).exec()
        }

        val perAppRule = when (perAppMode) {
            PerAppMode.ALL -> ""
            PerAppMode.WHITELIST -> {
                if (appUids.isNotEmpty()) {
                    val uidList = appUids.joinToString(", ")
                    "        meta skuid != { $uidList } return"
                } else {
                    "        return" // No apps selected in whitelist -> proxy nothing
                }
            }
            PerAppMode.BLACKLIST -> {
                if (appUids.isNotEmpty()) {
                    val uidList = appUids.joinToString(", ")
                    "        meta skuid { $uidList } return"
                } else {
                    ""
                }
            }
        }

        val ipv6BlockRule = if (!ipv6Enabled) {
            "        ip6 daddr != ::1 drop"
        } else {
            ""
        }

        val nftScript = buildString {
            appendLine("table inet mihomet {")
            // 1. Dedicated NAT chain for DNS interception
            appendLine("    chain dstnat {")
            appendLine("        type nat hook output priority dstnat; policy accept;")
            appendLine("        meta mark 666 return")
            appendLine("        meta l4proto { tcp, udp } th dport 53 redirect to :$dnsPort")
            appendLine("    }")
            appendLine()
            // 2. OUTPUT mangle hook (mark 0x1 -> lo)
            appendLine("    chain output {")
            appendLine("        type route hook output priority mangle; policy accept;")
            appendLine("        meta mark 666 return")
            appendLine("        th dport $dnsPort return")
            if (ipv6BlockRule.isNotBlank()) {
                appendLine(ipv6BlockRule)
            }
            appendLine("        ip daddr { 127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 224.0.0.0/4, 255.255.255.255/32 } return")
            if (perAppRule.isNotBlank()) {
                appendLine(perAppRule)
            }
            appendLine("        meta l4proto { tcp, udp } meta mark set 0x1")
            appendLine("    }")
            appendLine()
            // 3. PREROUTING mangle hook (tproxy)
            appendLine("    chain prerouting {")
            appendLine("        type filter hook prerouting priority mangle; policy accept;")
            appendLine("        ip daddr { 127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 224.0.0.0/4, 255.255.255.255/32 } return")
            appendLine("        meta l4proto { tcp, udp } meta mark set 0x1 tproxy to :$tproxyPort accept")
            appendLine("    }")
            appendLine("}")
        }

        // Apply nftables rules atomically via here-doc
        val applyCmd = "cat << 'EOF' | nft -f -\n$nftScript\nEOF"
        val result = Shell.cmd(applyCmd).exec()

        if (!result.isSuccess) {
            throw RuntimeException("Failed to apply nftables script: ${result.err.joinToString("\n")}")
        }
    }

    override fun teardown(ipv6Enabled: Boolean): Result<Unit> = runCatching {
        Shell.cmd(
            "nft delete table inet mihomet 2>/dev/null || true",
            "ip rule del fwmark 0x1/0x1 table 100 2>/dev/null || true",
            "ip route del local default dev lo table 100 2>/dev/null || true",
            "ip -6 rule del fwmark 0x1/0x1 table 100 2>/dev/null || true",
            "ip -6 route del local default dev lo table 100 2>/dev/null || true"
        ).exec()
    }
}
