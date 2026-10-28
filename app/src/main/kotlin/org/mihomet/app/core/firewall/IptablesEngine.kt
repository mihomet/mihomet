package org.mihomet.app.core.firewall

import com.topjohnwu.superuser.Shell
import org.mihomet.app.core.CoreConstants
import org.mihomet.app.data.preferences.PerAppMode

class IptablesEngine : FirewallEngine {
    override val name: String = "iptables"

    override fun setup(
        tproxyPort: Int,
        dnsPort: Int,
        perAppMode: PerAppMode,
        appUids: Set<Int>,
        ipv6Enabled: Boolean
    ): Result<Unit> = runCatching {
        teardown(ipv6Enabled)

        // 1. Detect active default network table (WiFi / Mobile Data)
        val defaultTable = DefaultTableProbe.detect()

        // 2..7. Build and apply the full rule set
        val cmds = buildSetupCommands(tproxyPort, dnsPort, perAppMode, appUids, ipv6Enabled, defaultTable)
        val result = Shell.cmd(*cmds.toTypedArray()).exec()
        if (!result.isSuccess) {
            throw RuntimeException("Failed to apply iptables: ${result.err.joinToString("\n")}")
        }
    }

    override fun teardown(ipv6Enabled: Boolean): Result<Unit> = runCatching {
        Shell.cmd(*buildTeardownCommands().toTypedArray()).exec()
    }

    companion object {
        /**
         * Pure builder for the iptables setup batch. [defaultTable] is the active
         * default network interface (Wi-Fi / mobile data) that fwmark-666 core traffic
         * must be routed to; at runtime it is resolved by [DefaultTableProbe.detect].
         */
        fun buildSetupCommands(
            tproxyPort: Int,
            dnsPort: Int,
            perAppMode: PerAppMode,
            appUids: Set<Int>,
            ipv6Enabled: Boolean,
            defaultTable: String = DefaultTableProbe.FALLBACK
        ): List<String> = buildList {
            // IP routing rules
            // Critical: Route Mihomo core traffic (fwmark 666) to physical network interface table.
            // First purge stale fwmark rules from previous sessions: a rule pointing at a dead
            // network (e.g. mobile data when the phone is now on Wi-Fi) blackholes every core
            // DNS query ("dns resolve failed: context deadline exceeded"). Deleting by pref is
            // safe — pref 100 is reserved for our fwmark rules only.
            add("while ip rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN} 2>/dev/null; do true; done")
            add("ip rule add fwmark ${CoreConstants.CORE_ROUTING_MARK} lookup $defaultTable pref ${CoreConstants.POLICY_RULE_PREF_MAIN}")
            // Route marked packets to local loopback table 2025
            add("ip rule add fwmark ${CoreConstants.TPROXY_MARK_IPV4} table ${CoreConstants.POLICY_ROUTING_TABLE} pref ${CoreConstants.POLICY_RULE_PREF_LOCAL}")
            add("ip route add local 0.0.0.0/0 dev lo table ${CoreConstants.POLICY_ROUTING_TABLE}")
            add("echo 1 > /proc/sys/net/ipv4/ip_forward")
            add("echo 2 > /proc/sys/net/ipv4/conf/all/rp_filter 2>/dev/null || true")
            add("echo 2 > /proc/sys/net/ipv4/conf/default/rp_filter 2>/dev/null || true")

            if (ipv6Enabled) {
                add("ip -6 rule add fwmark ${CoreConstants.CORE_ROUTING_MARK} lookup $defaultTable pref ${CoreConstants.POLICY_RULE_PREF_MAIN} 2>/dev/null || true")
                add("ip -6 rule add fwmark ${CoreConstants.TPROXY_MARK_IPV6} table ${CoreConstants.POLICY_ROUTING_TABLE} pref ${CoreConstants.POLICY_RULE_PREF_LOCAL} 2>/dev/null || true")
                add("ip -6 route add local ::/0 dev lo table ${CoreConstants.POLICY_ROUTING_TABLE} 2>/dev/null || true")
            }

            // Bypass chain: bypass local/private IPs UNLESS destination is port 53 (DNS interception)
            add("iptables -t mangle -N MIHOMET_BYPASS 2>/dev/null || iptables -t mangle -F MIHOMET_BYPASS")
            add("iptables -t mangle -A MIHOMET_BYPASS -d 127.0.0.0/8 -p udp ! --dport 53 -j ACCEPT")
            add("iptables -t mangle -A MIHOMET_BYPASS -d 127.0.0.0/8 ! -p udp -j ACCEPT")
            add("iptables -t mangle -A MIHOMET_BYPASS -d 10.0.0.0/8 -p udp ! --dport 53 -j ACCEPT")
            add("iptables -t mangle -A MIHOMET_BYPASS -d 10.0.0.0/8 ! -p udp -j ACCEPT")
            add("iptables -t mangle -A MIHOMET_BYPASS -d 172.16.0.0/12 -p udp ! --dport 53 -j ACCEPT")
            add("iptables -t mangle -A MIHOMET_BYPASS -d 172.16.0.0/12 ! -p udp -j ACCEPT")
            add("iptables -t mangle -A MIHOMET_BYPASS -d 192.168.0.0/16 -p udp ! --dport 53 -j ACCEPT")
            add("iptables -t mangle -A MIHOMET_BYPASS -d 192.168.0.0/16 ! -p udp -j ACCEPT")
            add("iptables -t mangle -A MIHOMET_BYPASS -d 224.0.0.0/4 -j ACCEPT")
            add("iptables -t mangle -A MIHOMET_BYPASS -d 255.255.255.255/32 -j ACCEPT")

            // Mangle OUTPUT chain (Local application traffic)
            add("iptables -t mangle -N MIHOMET_OUTPUT 2>/dev/null || iptables -t mangle -F MIHOMET_OUTPUT")
            // Loop prevention: bypass Mihomo core mark (666) and core owner (root:net_admin 3005)
            add("iptables -t mangle -A MIHOMET_OUTPUT -m mark --mark ${CoreConstants.CORE_ROUTING_MARK} -j ACCEPT")
            add("iptables -t mangle -A MIHOMET_OUTPUT -m owner --uid-owner 0 --gid-owner 3005 -j ACCEPT")
            add("iptables -t mangle -A MIHOMET_OUTPUT -j MIHOMET_BYPASS")

            // Per-App filtering in OUTPUT
            when (perAppMode) {
                PerAppMode.ALL -> { /* Capture all */ }
                PerAppMode.WHITELIST -> {
                    if (appUids.isNotEmpty()) {
                        add("iptables -t mangle -N MIHOMET_UIDS 2>/dev/null || iptables -t mangle -F MIHOMET_UIDS")
                        for (uid in appUids) {
                            add("iptables -t mangle -A MIHOMET_UIDS -m owner --uid-owner $uid -j RETURN")
                        }
                        // Non-matching apps skip proxying
                        add("iptables -t mangle -A MIHOMET_OUTPUT -j MIHOMET_UIDS")
                        add("iptables -t mangle -A MIHOMET_OUTPUT -j ACCEPT")
                    } else {
                        add("iptables -t mangle -A MIHOMET_OUTPUT -j ACCEPT")
                    }
                }
                PerAppMode.BLACKLIST -> {
                    for (uid in appUids) {
                        add("iptables -t mangle -A MIHOMET_OUTPUT -m owner --uid-owner $uid -j ACCEPT")
                    }
                }
            }

            // Mark remaining traffic to divert to table 2025 -> lo -> PREROUTING
            add("iptables -t mangle -A MIHOMET_OUTPUT -p tcp -j MARK --set-mark ${CoreConstants.TPROXY_MARK_IPV4}")
            add("iptables -t mangle -A MIHOMET_OUTPUT -p udp -j MARK --set-mark ${CoreConstants.TPROXY_MARK_IPV4}")

            // Mangle PREROUTING chain (TProxy interception)
            add("iptables -t mangle -N MIHOMET_PREROUTING 2>/dev/null || iptables -t mangle -F MIHOMET_PREROUTING")
            add("iptables -t mangle -A MIHOMET_PREROUTING -m mark --mark ${CoreConstants.CORE_ROUTING_MARK} -j ACCEPT")
            add("iptables -t mangle -A MIHOMET_PREROUTING -j MIHOMET_BYPASS")
            add("iptables -t mangle -A MIHOMET_PREROUTING -p tcp -j TPROXY --on-port $tproxyPort --tproxy-mark ${CoreConstants.TPROXY_MARK_IPV4}")
            add("iptables -t mangle -A MIHOMET_PREROUTING -p udp -j TPROXY --on-port $tproxyPort --tproxy-mark ${CoreConstants.TPROXY_MARK_IPV4}")

            // Attach to main chains
            add("iptables -t mangle -I OUTPUT 1 -p tcp -j MIHOMET_OUTPUT")
            add("iptables -t mangle -I OUTPUT 1 -p udp -j MIHOMET_OUTPUT")
            add("iptables -t mangle -I PREROUTING 1 -p tcp -j MIHOMET_PREROUTING")
            add("iptables -t mangle -I PREROUTING 1 -p udp -j MIHOMET_PREROUTING")

            // IPv6 Block when disabled
            if (!ipv6Enabled) {
                add("ip6tables -I OUTPUT 1 -m mark ! --mark ${CoreConstants.CORE_ROUTING_MARK} -j DROP 2>/dev/null || true")
            }
        }

        /**
         * Pure builder for the iptables teardown batch. Detaches every jump, flushes and
         * deletes every chain, purges both fwmark rule families and the local route.
         */
        fun buildTeardownCommands(): List<String> = listOf(
            // Purge ALL fwmark-666 rules, not just the one for the current default table —
            // rules left over from a session on another network would otherwise linger forever
            "while ip rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN} 2>/dev/null; do true; done; " +
            "while ip -6 rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN} 2>/dev/null; do true; done; ",
            "iptables -t mangle -D OUTPUT -p tcp -j MIHOMET_OUTPUT 2>/dev/null || true",
            "iptables -t mangle -D OUTPUT -p udp -j MIHOMET_OUTPUT 2>/dev/null || true",
            "iptables -t mangle -D PREROUTING -p tcp -j MIHOMET_PREROUTING 2>/dev/null || true",
            "iptables -t mangle -D PREROUTING -p udp -j MIHOMET_PREROUTING 2>/dev/null || true",
            "iptables -t mangle -F MIHOMET_OUTPUT 2>/dev/null || true",
            "iptables -t mangle -F MIHOMET_PREROUTING 2>/dev/null || true",
            "iptables -t mangle -F MIHOMET_BYPASS 2>/dev/null || true",
            "iptables -t mangle -F MIHOMET_UIDS 2>/dev/null || true",
            "iptables -t mangle -X MIHOMET_OUTPUT 2>/dev/null || true",
            "iptables -t mangle -X MIHOMET_PREROUTING 2>/dev/null || true",
            "iptables -t mangle -X MIHOMET_BYPASS 2>/dev/null || true",
            "iptables -t mangle -X MIHOMET_UIDS 2>/dev/null || true",
            "ip6tables -D OUTPUT -m mark ! --mark ${CoreConstants.CORE_ROUTING_MARK} -j DROP 2>/dev/null || true",
            "ip rule del fwmark ${CoreConstants.TPROXY_MARK_IPV4} table ${CoreConstants.POLICY_ROUTING_TABLE} pref ${CoreConstants.POLICY_RULE_PREF_LOCAL} 2>/dev/null || true",
            "ip route del local default dev lo table ${CoreConstants.POLICY_ROUTING_TABLE} 2>/dev/null || true",
            "ip -6 rule del fwmark ${CoreConstants.TPROXY_MARK_IPV6} table ${CoreConstants.POLICY_ROUTING_TABLE} pref ${CoreConstants.POLICY_RULE_PREF_LOCAL} 2>/dev/null || true",
            "ip -6 route del local default dev lo table ${CoreConstants.POLICY_ROUTING_TABLE} 2>/dev/null || true"
        )
    }
}
