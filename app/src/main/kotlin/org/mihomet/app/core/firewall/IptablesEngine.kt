package org.mihomet.app.core.firewall

import com.topjohnwu.superuser.Shell
import org.mihomet.app.core.CoreConstants
import org.mihomet.app.data.preferences.PerAppMode

class IptablesEngine : FirewallEngine {
    override val name: String = "iptables"

    override fun setup(
        tproxyPort: Int,
        perAppMode: PerAppMode,
        appUids: Set<Int>,
        ipv6Enabled: Boolean
    ): Result<Unit> = runCatching {
        teardown(ipv6Enabled)

        // 1. Detect active default network table (WiFi / Mobile Data)
        val defaultTable = DefaultTableProbe.detect()

        // 2..7. Build and apply the full rule set
        val cmds = buildSetupCommands(tproxyPort, perAppMode, appUids, ipv6Enabled, defaultTable)
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
         * Chains flushed and deleted on teardown. MIHOMET_UIDS is no longer created by
         * [buildSetupCommands] (per-app whitelist is expressed inline), but it stays here
         * so leftovers from older app versions are purged too.
         */
        private val MANGLE_CHAINS =
            listOf("MIHOMET_OUTPUT", "MIHOMET_PREROUTING", "MIHOMET_BYPASS", "MIHOMET_UIDS")

        /**
         * Pure builder for the iptables setup batch. [defaultTable] is the active
         * default network interface (Wi-Fi / mobile data) that fwmark-666 core traffic
         * must be routed to; at runtime it is resolved by [DefaultTableProbe.detect].
         *
         * Note: there is deliberately no dnsPort parameter. DNS interception is
         * destination-port-53 based and protocol-agnostic (both tcp and udp, see
         * [bypassChain]) — the core answers through its `dns.listen` socket no matter
         * which port the client's resolver config advertises, so the configured DNS
         * port plays no role in the firewall rules.
         */
        fun buildSetupCommands(
            tproxyPort: Int,
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
                // Purge stale IPv6 fwmark rules symmetrically to the IPv4 purge above —
                // a leftover rule pointing at a dead network blackholes core DNS over IPv6.
                add("while ip -6 rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN} 2>/dev/null; do true; done")
                add("ip -6 rule add fwmark ${CoreConstants.CORE_ROUTING_MARK} lookup $defaultTable pref ${CoreConstants.POLICY_RULE_PREF_MAIN} 2>/dev/null || true")
                add("ip -6 rule add fwmark ${CoreConstants.TPROXY_MARK_IPV6} table ${CoreConstants.POLICY_ROUTING_TABLE} pref ${CoreConstants.POLICY_RULE_PREF_LOCAL} 2>/dev/null || true")
                add("ip -6 route add local ::/0 dev lo table ${CoreConstants.POLICY_ROUTING_TABLE} 2>/dev/null || true")
            }

            // --- IPv4 mangle chains ---
            addAll(bypassChain("iptables"))
            addAll(outputChain("iptables", CoreConstants.TPROXY_MARK_IPV4, perAppMode, appUids))
            addAll(preroutingChain("iptables", tproxyPort, CoreConstants.TPROXY_MARK_IPV4))
            addAll(attachJumps("iptables"))

            // --- IPv6 mangle chains ---
            // ip6tables and iptables maintain separate kernel rule sets: chains created
            // via iptables are invisible to ip6tables, so every chain must be mirrored.
            // The ip -6 policy rules above wait for fwmark ${TPROXY_MARK_IPV6} — without
            // the ip6tables marking below, IPv6 traffic would silently stay unproxied.
            if (ipv6Enabled) {
                addAll(bypassChainV6())
                addAll(outputChain("ip6tables", CoreConstants.TPROXY_MARK_IPV6, perAppMode, appUids))
                addAll(preroutingChain("ip6tables", tproxyPort, CoreConstants.TPROXY_MARK_IPV6))
                addAll(attachJumps("ip6tables"))
            }

            // IPv6 Block when disabled
            if (!ipv6Enabled) {
                add("ip6tables -I OUTPUT 1 -m mark ! --mark ${CoreConstants.CORE_ROUTING_MARK} -j DROP 2>/dev/null || true")
            }
        }

        /**
         * Pure builder for the iptables teardown batch. Detaches every jump, flushes and
         * deletes every chain in both address families, purges both fwmark rule families
         * and the local routes.
         */
        fun buildTeardownCommands(): List<String> = buildList {
            // Purge ALL fwmark-666 rules, not just the one for the current default table —
            // rules left over from a session on another network would otherwise linger forever
            add(
                "while ip rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN} 2>/dev/null; do true; done; " +
                "while ip -6 rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN} 2>/dev/null; do true; done; "
            )
            for (tool in listOf("iptables", "ip6tables")) {
                // every jump attached with -I must be detached with -D
                add("$tool -t mangle -D OUTPUT -p tcp -j MIHOMET_OUTPUT 2>/dev/null || true")
                add("$tool -t mangle -D OUTPUT -p udp -j MIHOMET_OUTPUT 2>/dev/null || true")
                add("$tool -t mangle -D PREROUTING -p tcp -j MIHOMET_PREROUTING 2>/dev/null || true")
                add("$tool -t mangle -D PREROUTING -p udp -j MIHOMET_PREROUTING 2>/dev/null || true")
                for (chain in MANGLE_CHAINS) {
                    add("$tool -t mangle -F $chain 2>/dev/null || true")
                }
                for (chain in MANGLE_CHAINS) {
                    add("$tool -t mangle -X $chain 2>/dev/null || true")
                }
            }
            add("ip6tables -D OUTPUT -m mark ! --mark ${CoreConstants.CORE_ROUTING_MARK} -j DROP 2>/dev/null || true")
            add("ip rule del fwmark ${CoreConstants.TPROXY_MARK_IPV4} table ${CoreConstants.POLICY_ROUTING_TABLE} pref ${CoreConstants.POLICY_RULE_PREF_LOCAL} 2>/dev/null || true")
            add("ip route del local default dev lo table ${CoreConstants.POLICY_ROUTING_TABLE} 2>/dev/null || true")
            add("ip -6 rule del fwmark ${CoreConstants.TPROXY_MARK_IPV6} table ${CoreConstants.POLICY_ROUTING_TABLE} pref ${CoreConstants.POLICY_RULE_PREF_LOCAL} 2>/dev/null || true")
            add("ip -6 route del local default dev lo table ${CoreConstants.POLICY_ROUTING_TABLE} 2>/dev/null || true")
        }

        // ---------------------------------------------------------------------
        // Chain builders (shared between iptables / ip6tables where possible)
        // ---------------------------------------------------------------------

        /**
         * Bypass chain (IPv4): bypass local/private IPs UNLESS destination is port 53
         * (DNS interception) — for BOTH tcp and udp, so any DNS query to the router or
         * a private resolver is hijacked by the core (which answers from its own
         * `dns.listen` socket regardless of protocol). Multicast and broadcast stay
         * fully bypassed. Only tcp/udp ever reach this chain (the OUTPUT/PREROUTING
         * jumps are protocol-scoped); packets to private ranges with dport 53 fall
         * through into the trailing MARK/TPROXY rules.
         */
        private fun bypassChain(tool: String): List<String> = buildList {
            add("$tool -t mangle -N MIHOMET_BYPASS 2>/dev/null || $tool -t mangle -F MIHOMET_BYPASS")
            for (net in listOf("127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")) {
                add("$tool -t mangle -A MIHOMET_BYPASS -d $net -p udp ! --dport 53 -j ACCEPT")
                add("$tool -t mangle -A MIHOMET_BYPASS -d $net -p tcp ! --dport 53 -j ACCEPT")
            }
            add("$tool -t mangle -A MIHOMET_BYPASS -d 224.0.0.0/4 -j ACCEPT")
            add("$tool -t mangle -A MIHOMET_BYPASS -d 255.255.255.255/32 -j ACCEPT")
        }

        /**
         * IPv6 counterpart of [bypassChain]: loopback, ULA and link-local are bypassed
         * (again except destination port 53 for either protocol — DNS interception),
         * IPv6 multicast stays fully bypassed.
         */
        private fun bypassChainV6(): List<String> = buildList {
            add("ip6tables -t mangle -N MIHOMET_BYPASS 2>/dev/null || ip6tables -t mangle -F MIHOMET_BYPASS")
            for (net in listOf("::1/128", "fc00::/7", "fe80::/10")) {
                add("ip6tables -t mangle -A MIHOMET_BYPASS -d $net -p udp ! --dport 53 -j ACCEPT")
                add("ip6tables -t mangle -A MIHOMET_BYPASS -d $net -p tcp ! --dport 53 -j ACCEPT")
            }
            add("ip6tables -t mangle -A MIHOMET_BYPASS -d ff00::/8 -j ACCEPT")
        }

        /**
         * Mangle OUTPUT chain (local application traffic).
         *
         * Packet outcome matrix (a packet leaves the chain with either the tproxy mark
         * -> diverted into the local routing table -> PREROUTING TPROXY, or unmarked ->
         * routed directly):
         *  - core packets (fwmark-666) and the core owner (root:net_admin) -> bypass;
         *  - local/private destinations -> bypass, EXCEPT destination port 53 over tcp
         *    or udp (DNS hijack: the core answers from its own resolver);
         *  - WHITELIST with uids: selected uids are MARKed (non-terminating) and keep
         *    the mark through the catch-all ACCEPT; every other app hits the catch-all
         *    ACCEPT unmarked -> direct;
         *  - WHITELIST with an EMPTY uid set: no uid rules and no catch-all ACCEPT —
         *    the trailing tcp/udp MARK rules capture everything, i.e. the mode behaves
         *    exactly like ALL. This matches the VPN engine, where Android routes every
         *    app when the allow list is absent, and the Routing screen hint
         *    (R.string.perapp_empty_whitelist) which promises "all traffic is proxied".
         *  - BLACKLIST: excluded uids hit their ACCEPT (direct); everyone else is marked;
         *  - ALL: everything tcp/udp is marked.
         *
         * NOTE the ordering is load bearing: the per-uid MARK rules must precede the
         * catch-all ACCEPT, otherwise the whitelist is inverted (marked packets would
         * never exist). Guarded by FirewallCommandsStrictTest.
         */
        private fun outputChain(
            tool: String,
            tproxyMark: Int,
            perAppMode: PerAppMode,
            appUids: Set<Int>
        ): List<String> = buildList {
            add("$tool -t mangle -N MIHOMET_OUTPUT 2>/dev/null || $tool -t mangle -F MIHOMET_OUTPUT")
            // Loop prevention: bypass Mihomo core mark (666) and core owner (root:net_admin 3005)
            add("$tool -t mangle -A MIHOMET_OUTPUT -m mark --mark ${CoreConstants.CORE_ROUTING_MARK} -j ACCEPT")
            add("$tool -t mangle -A MIHOMET_OUTPUT -m owner --uid-owner 0 --gid-owner 3005 -j ACCEPT")
            add("$tool -t mangle -A MIHOMET_OUTPUT -j MIHOMET_BYPASS")

            // An empty whitelist is semantically ALL (see the class doc above): no uid
            // rules are emitted and the trailing catch-all MARK rules stay reachable.
            val whitelistWithUids = perAppMode == PerAppMode.WHITELIST && appUids.isNotEmpty()

            // Per-App filtering in OUTPUT
            when {
                perAppMode == PerAppMode.BLACKLIST -> {
                    for (uid in appUids) {
                        // Excluded apps bypass the proxy entirely
                        add("$tool -t mangle -A MIHOMET_OUTPUT -m owner --uid-owner $uid -j ACCEPT")
                    }
                }
                whitelistWithUids -> {
                    // MARK is non-terminating: a whitelisted packet is marked, falls through
                    // the remaining owner rules and finally through the catch-all ACCEPT
                    // below while KEEPING its mark -> the fwmark policy rule diverts it to
                    // the tproxy path. Non-whitelisted apps hit the catch-all ACCEPT without
                    // a mark -> direct. Restricted to tcp/udp, the only protocols the
                    // TPROXY rules intercept (anything else must stay unmarked -> direct).
                    for (uid in appUids) {
                        for (proto in listOf("tcp", "udp")) {
                            add("$tool -t mangle -A MIHOMET_OUTPUT -p $proto -m owner --uid-owner $uid -j MARK --set-mark $tproxyMark")
                        }
                    }
                    add("$tool -t mangle -A MIHOMET_OUTPUT -j ACCEPT")
                }
                // PerAppMode.ALL and empty WHITELIST: no per-uid rules here —
                // the trailing rules below mark every tcp/udp packet.
            }

            if (!whitelistWithUids) {
                // Mark remaining traffic to divert to the local table -> lo -> PREROUTING.
                // (Only in WHITELIST mode with at least one uid the catch-all ACCEPT above
                // terminates every packet, making these trailing mark rules unreachable.)
                add("$tool -t mangle -A MIHOMET_OUTPUT -p tcp -j MARK --set-mark $tproxyMark")
                add("$tool -t mangle -A MIHOMET_OUTPUT -p udp -j MARK --set-mark $tproxyMark")
            }
        }

        /**
         * Mangle PREROUTING chain (TProxy interception of packets rerouted to lo).
         */
        private fun preroutingChain(tool: String, tproxyPort: Int, tproxyMark: Int): List<String> = buildList {
            add("$tool -t mangle -N MIHOMET_PREROUTING 2>/dev/null || $tool -t mangle -F MIHOMET_PREROUTING")
            add("$tool -t mangle -A MIHOMET_PREROUTING -m mark --mark ${CoreConstants.CORE_ROUTING_MARK} -j ACCEPT")
            add("$tool -t mangle -A MIHOMET_PREROUTING -j MIHOMET_BYPASS")
            add("$tool -t mangle -A MIHOMET_PREROUTING -p tcp -j TPROXY --on-port $tproxyPort --tproxy-mark $tproxyMark")
            add("$tool -t mangle -A MIHOMET_PREROUTING -p udp -j TPROXY --on-port $tproxyPort --tproxy-mark $tproxyMark")
        }

        /** Attach the user chains to the built-in OUTPUT / PREROUTING hooks. */
        private fun attachJumps(tool: String): List<String> = listOf(
            "$tool -t mangle -I OUTPUT 1 -p tcp -j MIHOMET_OUTPUT",
            "$tool -t mangle -I OUTPUT 1 -p udp -j MIHOMET_OUTPUT",
            "$tool -t mangle -I PREROUTING 1 -p tcp -j MIHOMET_PREROUTING",
            "$tool -t mangle -I PREROUTING 1 -p udp -j MIHOMET_PREROUTING"
        )
    }
}
