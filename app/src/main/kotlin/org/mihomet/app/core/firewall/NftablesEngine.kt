package org.mihomet.app.core.firewall

import com.topjohnwu.superuser.Shell
import org.mihomet.app.core.CoreConstants
import org.mihomet.app.data.preferences.PerAppMode

class NftablesEngine : FirewallEngine {
    override val name: String = "nftables"

    override fun setup(
        tproxyPort: Int,
        perAppMode: PerAppMode,
        appUids: Set<Int>,
        ipv6Enabled: Boolean
    ): Result<Unit> = runCatching {
        teardown(ipv6Enabled)

        // 1. Detect active default network table (WiFi / Mobile Data) and setup routing
        val defaultTable = DefaultTableProbe.detect()
        Shell.cmd(buildRoutingCommands(ipv6Enabled, defaultTable)).exec()

        // 2. Apply nftables rules atomically via here-doc
        val nftScript = buildNftScript(tproxyPort, perAppMode, appUids, ipv6Enabled)
        val applyCmd = "cat << 'EOF' | nft -f -\n$nftScript\nEOF"
        val result = Shell.cmd(applyCmd).exec()

        if (!result.isSuccess) {
            throw RuntimeException("Failed to apply nftables script: ${result.err.joinToString("\n")}")
        }
    }

    override fun teardown(ipv6Enabled: Boolean): Result<Unit> = runCatching {
        Shell.cmd(*buildTeardownCommands().toTypedArray()).exec()
    }

    companion object {
        /**
         * Pure builder for the ip-rule / sysctl routing batch (single shell string).
         * [defaultTable] is the active default network interface (Wi-Fi / mobile data)
         * that fwmark-666 core traffic must be routed to; at runtime it is resolved
         * by [DefaultTableProbe.detect].
         */
        fun buildRoutingCommands(
            ipv6Enabled: Boolean,
            defaultTable: String = DefaultTableProbe.FALLBACK
        ): String =
            // Purge stale fwmark-666 rules from previous sessions on other networks —
            // a rule pointing at a dead network blackholes every core DNS query.
            // F10: filtered by our fwmark — a bare `pref 100` loop would also delete
            // foreign pref-100 rules belonging to other VPN/firewall apps.
            "while ip rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN} fwmark ${CoreConstants.CORE_ROUTING_MARK} 2>/dev/null; do true; done; " +
            "ip rule add fwmark ${CoreConstants.CORE_ROUTING_MARK} lookup $defaultTable pref ${CoreConstants.POLICY_RULE_PREF_MAIN}; " +
            "ip rule add fwmark ${CoreConstants.TPROXY_MARK_IPV4} table ${CoreConstants.POLICY_ROUTING_TABLE} pref ${CoreConstants.POLICY_RULE_PREF_LOCAL}; " +
            "ip route add local 0.0.0.0/0 dev lo table ${CoreConstants.POLICY_ROUTING_TABLE}; " +
            // F10: snapshot the sysctls we are about to change into .sysctl.bak —
            // teardown writes the original values back
            FirewallEngine.buildSysctlBackupCommands().joinToString("; ") + "; " +
            "echo 1 > /proc/sys/net/ipv4/ip_forward; " +
            "echo 2 > /proc/sys/net/ipv4/conf/all/rp_filter 2>/dev/null || true; " +
            "echo 2 > /proc/sys/net/ipv4/conf/default/rp_filter 2>/dev/null || true; " +
            if (ipv6Enabled) {
                "while ip -6 rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN} fwmark ${CoreConstants.CORE_ROUTING_MARK} 2>/dev/null; do true; done; " +
                "ip -6 rule add fwmark ${CoreConstants.CORE_ROUTING_MARK} lookup $defaultTable pref ${CoreConstants.POLICY_RULE_PREF_MAIN} 2>/dev/null || true; " +
                "ip -6 rule add fwmark ${CoreConstants.TPROXY_MARK_IPV6} table ${CoreConstants.POLICY_ROUTING_TABLE} pref ${CoreConstants.POLICY_RULE_PREF_LOCAL} 2>/dev/null || true; " +
                "ip -6 route add local ::/0 dev lo table ${CoreConstants.POLICY_ROUTING_TABLE} 2>/dev/null || true"
            } else ""

        /**
         * Pure builder for the nft ruleset loaded through `nft -f -`.
         *
         * Marks are family-specific: the ip -4 policy rules divert fwmark
         * [CoreConstants.TPROXY_MARK_IPV4] packets, the ip -6 rules created by
         * [buildRoutingCommands] wait for fwmark [CoreConstants.TPROXY_MARK_IPV6] —
         * so IPv6 must be marked with 25, never with 20, otherwise IPv6 traffic
         * silently stays unproxied.
         *
         * Note: there is deliberately no dnsPort parameter. DNS interception is
         * destination-port-53 based and protocol-agnostic (both tcp and udp, see the
         * bypass chain) — the core answers through its `dns.listen` socket no matter
         * which port the client's resolver config advertises, so the configured DNS
         * port plays no role in the firewall rules.
         */
        fun buildNftScript(
            tproxyPort: Int,
            perAppMode: PerAppMode,
            appUids: Set<Int>,
            ipv6Enabled: Boolean
        ): String {
            val perAppRule = when (perAppMode) {
                PerAppMode.ALL -> ""
                PerAppMode.WHITELIST -> {
                    if (appUids.isNotEmpty()) {
                        val uidList = appUids.joinToString(", ")
                        "        meta skuid != { $uidList } return"
                    } else {
                        // Empty whitelist is semantically ALL (it matches the VPN engine,
                        // where Android routes every app when the allow list is absent,
                        // and the Routing screen hint R.string.perapp_empty_whitelist):
                        // no return rule — everything falls through into the family mark
                        // rules below and is diverted to tproxy.
                        ""
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

            // IPv6 marking/tproxy rules exist only when IPv6 is enabled; when disabled the
            // block rule above drops non-loopback IPv6 long before any mark would be set.
            val ipv6OutputMark = if (ipv6Enabled) {
                "        meta nfproto ipv6 meta l4proto { tcp, udp } meta mark set ${CoreConstants.TPROXY_MARK_IPV6}"
            } else {
                ""
            }
            val ipv6TproxyRule = if (ipv6Enabled) {
                "        meta nfproto ipv6 meta l4proto { tcp, udp } meta mark set ${CoreConstants.TPROXY_MARK_IPV6} tproxy to :$tproxyPort accept"
            } else {
                ""
            }

            // IPv6 counterpart of the private-range bypass (loopback / ULA / link-local),
            // still exposing destination port 53 for DNS interception over either protocol.
            val ipv6BypassRules = if (ipv6Enabled) {
                listOf(
                    "        ip6 daddr { ::1/128, fc00::/7, fe80::/10 } udp dport != 53 accept",
                    "        ip6 daddr { ::1/128, fc00::/7, fe80::/10 } tcp dport != 53 accept",
                    "        ip6 daddr ff00::/8 accept"
                )
            } else {
                emptyList()
            }

            return buildString {
                appendLine("table inet mihomet {")
                // 1. Bypass chain: skip local IPs except destination port 53 over tcp or
                //    udp (DNS hijack — any DNS query to a private/router resolver is
                //    intercepted by the core; dport-53 packets fall through into the
                //    mark/tproxy rules below).
                appendLine("    chain bypass {")
                appendLine("        ip daddr { 127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16 } udp dport != 53 accept")
                appendLine("        ip daddr { 127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16 } tcp dport != 53 accept")
                appendLine("        ip daddr { 224.0.0.0/4, 255.255.255.255/32 } accept")
                for (rule in ipv6BypassRules) {
                    appendLine(rule)
                }
                appendLine("    }")
                appendLine()
                // 2. OUTPUT mangle hook (mark 20 v4 / mark 25 v6 -> lo)
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
                appendLine("        meta nfproto ipv4 meta l4proto { tcp, udp } meta mark set ${CoreConstants.TPROXY_MARK_IPV4}")
                if (ipv6OutputMark.isNotBlank()) {
                    appendLine(ipv6OutputMark)
                }
                appendLine("    }")
                appendLine()
                // 3. PREROUTING mangle hook (tproxy)
                appendLine("    chain prerouting {")
                appendLine("        type filter hook prerouting priority mangle; policy accept;")
                appendLine("        meta mark ${CoreConstants.CORE_ROUTING_MARK} accept")
                appendLine("        jump bypass")
                appendLine("        meta nfproto ipv4 meta l4proto { tcp, udp } meta mark set ${CoreConstants.TPROXY_MARK_IPV4} tproxy to :$tproxyPort accept")
                if (ipv6TproxyRule.isNotBlank()) {
                    appendLine(ipv6TproxyRule)
                }
                appendLine("    }")
                appendLine("}")
            }
        }

        /**
         * Pure builder for the nftables teardown batch. Deletes the whole table
         * (removing every chain at once), purges our fwmark rule families (F10:
         * filtered by our fwmark — foreign pref-100 rules survive) and restores
         * the sysctls snapshotted by [buildRoutingCommands] (no-op when no
         * backup exists).
         */
        fun buildTeardownCommands(): List<String> = listOf(
            "while ip rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN} fwmark ${CoreConstants.CORE_ROUTING_MARK} 2>/dev/null; do true; done; " +
            "while ip -6 rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN} fwmark ${CoreConstants.CORE_ROUTING_MARK} 2>/dev/null; do true; done; ",
            "nft delete table inet mihomet 2>/dev/null || true",
            "ip rule del fwmark ${CoreConstants.TPROXY_MARK_IPV4} table ${CoreConstants.POLICY_ROUTING_TABLE} pref ${CoreConstants.POLICY_RULE_PREF_LOCAL} 2>/dev/null || true",
            "ip route del local default dev lo table ${CoreConstants.POLICY_ROUTING_TABLE} 2>/dev/null || true",
            "ip -6 rule del fwmark ${CoreConstants.TPROXY_MARK_IPV6} table ${CoreConstants.POLICY_ROUTING_TABLE} pref ${CoreConstants.POLICY_RULE_PREF_LOCAL} 2>/dev/null || true",
            "ip -6 route del local default dev lo table ${CoreConstants.POLICY_ROUTING_TABLE} 2>/dev/null || true"
        ) + FirewallEngine.buildSysctlRestoreCommands()
    }
}
