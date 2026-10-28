package org.mihomet.app.core.firewall

import com.topjohnwu.superuser.Shell
import org.mihomet.app.core.CoreConstants
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
        teardown(ipv6Enabled)

        // 1. Detect active default network table (WiFi / Mobile Data) and setup routing
        Shell.cmd(
            "DEFAULT_TABLE=\$(ip rule show | grep -E '^31000:' | awk '{print \$NF}'); " +
            "[ -z \"\$DEFAULT_TABLE\" ] && DEFAULT_TABLE=\$(ip route show table main | grep default | awk '{print \$5}'); " +
            "[ -z \"\$DEFAULT_TABLE\" ] && DEFAULT_TABLE=\"wlan0\"; " +
            // Purge stale fwmark-666 rules from previous sessions on other networks —
            // a rule pointing at a dead network blackholes every core DNS query
            "while ip rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN} 2>/dev/null; do true; done; " +
            "ip rule add fwmark ${CoreConstants.CORE_ROUTING_MARK} lookup \$DEFAULT_TABLE pref ${CoreConstants.POLICY_RULE_PREF_MAIN}; " +
            "ip rule add fwmark ${CoreConstants.TPROXY_MARK_IPV4} table ${CoreConstants.POLICY_ROUTING_TABLE} pref ${CoreConstants.POLICY_RULE_PREF_LOCAL}; " +
            "ip route add local 0.0.0.0/0 dev lo table ${CoreConstants.POLICY_ROUTING_TABLE}; " +
            "echo 1 > /proc/sys/net/ipv4/ip_forward; " +
            "echo 2 > /proc/sys/net/ipv4/conf/all/rp_filter 2>/dev/null || true; " +
            "echo 2 > /proc/sys/net/ipv4/conf/default/rp_filter 2>/dev/null || true; " +
            if (ipv6Enabled) {
                "while ip -6 rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN} 2>/dev/null; do true; done; " +
                "ip -6 rule add fwmark ${CoreConstants.CORE_ROUTING_MARK} lookup \$DEFAULT_TABLE pref ${CoreConstants.POLICY_RULE_PREF_MAIN} 2>/dev/null || true; " +
                "ip -6 rule add fwmark ${CoreConstants.TPROXY_MARK_IPV6} table ${CoreConstants.POLICY_ROUTING_TABLE} pref ${CoreConstants.POLICY_RULE_PREF_LOCAL} 2>/dev/null || true; " +
                "ip -6 route add local ::/0 dev lo table ${CoreConstants.POLICY_ROUTING_TABLE} 2>/dev/null || true"
            } else ""
        ).exec()

        val perAppRule = when (perAppMode) {
            PerAppMode.ALL -> ""
            PerAppMode.WHITELIST -> {
                if (appUids.isNotEmpty()) {
                    val uidList = appUids.joinToString(", ")
                    "        meta skuid != { $uidList } return"
                } else {
                    "        return"
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
            // 1. Bypass chain: skip local IPs except destination port 53 (DNS interception)
            appendLine("    chain bypass {")
            appendLine("        ip daddr { 127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16 } udp dport != 53 accept")
            appendLine("        ip daddr { 127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16 } meta l4proto != udp accept")
            appendLine("        ip daddr { 224.0.0.0/4, 255.255.255.255/32 } accept")
            appendLine("    }")
            appendLine()
            // 2. OUTPUT mangle hook (mark 20 -> lo)
            appendLine("    chain output {")
            appendLine("        type route hook output priority mangle; policy accept;")
            appendLine("        meta mark ${CoreConstants.CORE_ROUTING_MARK} accept")
            appendLine("        meta skuid 0 meta skgid 3005 accept")
            appendLine("        jump bypass")
            if (ipv6BlockRule.isNotBlank()) {
                appendLine(ipv6BlockRule)
            }
            if (perAppRule.isNotBlank()) {
                appendLine(perAppRule)
            }
            appendLine("        meta l4proto { tcp, udp } meta mark set ${CoreConstants.TPROXY_MARK_IPV4}")
            appendLine("    }")
            appendLine()
            // 3. PREROUTING mangle hook (tproxy)
            appendLine("    chain prerouting {")
            appendLine("        type filter hook prerouting priority mangle; policy accept;")
            appendLine("        meta mark ${CoreConstants.CORE_ROUTING_MARK} accept")
            appendLine("        jump bypass")
            appendLine("        meta l4proto { tcp, udp } meta mark set ${CoreConstants.TPROXY_MARK_IPV4} tproxy to :$tproxyPort accept")
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
            "DEFAULT_TABLE=\$(ip rule show | grep -E '^31000:' | awk '{print \$NF}'); " +
            "[ -z \"\$DEFAULT_TABLE\" ] && DEFAULT_TABLE=\$(ip route show table main | grep default | awk '{print \$5}'); " +
            "[ -z \"\$DEFAULT_TABLE\" ] && DEFAULT_TABLE=\"wlan0\"; " +
            // Purge ALL fwmark-666 rules — stale ones from sessions on other networks linger forever
            "while ip rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN} 2>/dev/null; do true; done; " +
            "while ip -6 rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN} 2>/dev/null; do true; done; ",
            "nft delete table inet mihomet 2>/dev/null || true",
            "ip rule del fwmark ${CoreConstants.TPROXY_MARK_IPV4} table ${CoreConstants.POLICY_ROUTING_TABLE} pref ${CoreConstants.POLICY_RULE_PREF_LOCAL} 2>/dev/null || true",
            "ip route del local default dev lo table ${CoreConstants.POLICY_ROUTING_TABLE} 2>/dev/null || true",
            "ip -6 rule del fwmark ${CoreConstants.TPROXY_MARK_IPV6} table ${CoreConstants.POLICY_ROUTING_TABLE} pref ${CoreConstants.POLICY_RULE_PREF_LOCAL} 2>/dev/null || true",
            "ip -6 route del local default dev lo table ${CoreConstants.POLICY_ROUTING_TABLE} 2>/dev/null || true"
        ).exec()
    }
}
