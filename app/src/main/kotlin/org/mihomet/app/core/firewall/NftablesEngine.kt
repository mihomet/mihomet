package org.mihomet.app.core.firewall

import android.util.Log
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

        // 1. Detect active default network table (WiFi / Mobile Data) and apply
        //    the routing rules with per-command verification (audit N03): the
        //    mandatory `ip rule add` / `ip route add` commands run individually
        //    and abort the setup on a non-zero exit, the self-guarded optionals
        //    run as one best-effort batch.
        val defaultTable = DefaultTableProbe.detect()
        val optionalBatch = mutableListOf<String>()
        for (spec in buildRoutingSpecs(ipv6Enabled, defaultTable)) {
            if (spec.mandatory) {
                flushOptionalBatch(optionalBatch)
                val result = Shell.cmd(spec.cmd).exec()
                if (!result.isSuccess) {
                    throw RuntimeException("Failed: ${spec.cmd} (${result.errorTail()})")
                }
            } else {
                optionalBatch += spec.cmd
            }
        }
        val batchResult = flushOptionalBatch(optionalBatch)
        if (batchResult != null && !batchResult.isSuccess) {
            throw RuntimeException("Failed to apply nftables routing: ${batchResult.err.joinToString("\n")}")
        }

        // 2. Apply nftables rules atomically via here-doc
        val nftScript = buildNftScript(tproxyPort, perAppMode, appUids, ipv6Enabled)
        val applyCmd = "cat << 'EOF' | nft -f -\n$nftScript\nEOF"
        val result = Shell.cmd(applyCmd).exec()

        if (!result.isSuccess) {
            throw RuntimeException("Failed to apply nftables script: ${result.err.joinToString("\n")}")
        }

        // 3. Post-verification (audit N03): `nft -f -` succeeding proves nothing
        //    about the TPROXY target being supported — the loaded table must
        //    actually list the tproxy rules (v4 + v6, or v4 only when IPv6 is off).
        verifyTproxyRulesApplied(ipv6Enabled)
    }

    override fun teardown(ipv6Enabled: Boolean): Result<Unit> = runCatching {
        // Audit N03: teardown failures are never silent — every command runs
        // individually and failed ones are collected and logged (the commands
        // themselves stay `|| true`-guarded, so the log fires only on real
        // shell-level breakage).
        val failed = mutableListOf<String>()
        for (cmd in buildTeardownCommands()) {
            val result = Shell.cmd(cmd).exec()
            if (!result.isSuccess) failed += "$cmd (${result.errorTail()})"
        }
        if (failed.isNotEmpty()) {
            Log.e(TAG, "nftables teardown: ${failed.size} command(s) failed:\n${failed.joinToString("\n")}")
        }
        // Belt-and-braces post-check: the table must be gone. A table that still
        // exists means the kernel refused the delete — log it instead of
        // swallowing (audit N03: show the actual teardown errors).
        val leftover = Shell.cmd("nft list table inet mihomet 2>/dev/null").exec()
        if (leftover.isSuccess) {
            Log.e(TAG, "nftables teardown incomplete: table inet mihomet still present")
        }
    }

    companion object {
        private const val TAG = "NftablesEngine"

        /** Executed individually, failure aborts the setup. */
        private fun m(cmd: String) = FirewallSetupCommand(cmd, mandatory = true)

        /** Batched best-effort (self-guarded with `|| true`). */
        private fun o(cmd: String) = FirewallSetupCommand(cmd, mandatory = false)

        /**
         * Pure builder for the ip-rule / sysctl routing batch (single shell string).
         * [defaultTable] is the active default network interface (Wi-Fi / mobile data)
         * that fwmark-666 core traffic must be routed to; at runtime it is resolved
         * by [DefaultTableProbe.detect].
         */
        fun buildRoutingCommands(
            ipv6Enabled: Boolean,
            defaultTable: String = DefaultTableProbe.FALLBACK
        ): String = buildRoutingSpecs(ipv6Enabled, defaultTable).joinToString("; ") { it.cmd }

        /**
         * Ordered [FirewallSetupCommand] specs behind [buildRoutingCommands] (audit N03):
         * the `ip rule add` / `ip route add` commands are [FirewallSetupCommand.mandatory]
         * and run one per shell call with an individual exit-code check; only the
         * `|| true`-guarded rp_filter relaxations stay in the best-effort batch. The
         * IPv6 policy rules are mandatory when [ipv6Enabled] is true — a silently
         * skipped v6 rule would leave IPv6 traffic unproxied.
         */
        fun buildRoutingSpecs(
            ipv6Enabled: Boolean,
            defaultTable: String = DefaultTableProbe.FALLBACK
        ): List<FirewallSetupCommand> = buildList {
            // Purge stale fwmark-666 rules from previous sessions on other networks —
            // a rule pointing at a dead network blackholes every core DNS query.
            // F10: filtered by our fwmark — a bare `pref 100` loop would also delete
            // foreign pref-100 rules belonging to other VPN/firewall apps.
            add(m("while ip rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN} fwmark ${CoreConstants.CORE_ROUTING_MARK} 2>/dev/null; do true; done"))
            add(m("ip rule add fwmark ${CoreConstants.CORE_ROUTING_MARK} lookup $defaultTable pref ${CoreConstants.POLICY_RULE_PREF_MAIN}"))
            add(m("ip rule add fwmark ${CoreConstants.TPROXY_MARK_IPV4} table ${CoreConstants.POLICY_ROUTING_TABLE} pref ${CoreConstants.POLICY_RULE_PREF_LOCAL}"))
            add(m("ip route add local 0.0.0.0/0 dev lo table ${CoreConstants.POLICY_ROUTING_TABLE}"))
            // F10: snapshot the sysctls we are about to change into .sysctl.bak —
            // teardown writes the original values back
            addAll(FirewallEngine.buildSysctlBackupCommands().map(::m))
            add(m("echo 1 > /proc/sys/net/ipv4/ip_forward"))
            add(o("echo 2 > /proc/sys/net/ipv4/conf/all/rp_filter 2>/dev/null || true"))
            add(o("echo 2 > /proc/sys/net/ipv4/conf/default/rp_filter 2>/dev/null || true"))
            if (ipv6Enabled) {
                add(m("while ip -6 rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN} fwmark ${CoreConstants.CORE_ROUTING_MARK} 2>/dev/null; do true; done"))
                // N03: mandatory when IPv6 is enabled — a skipped v6 policy rule
                // would silently leave IPv6 traffic unproxied.
                add(m("ip -6 rule add fwmark ${CoreConstants.CORE_ROUTING_MARK} lookup $defaultTable pref ${CoreConstants.POLICY_RULE_PREF_MAIN}"))
                add(m("ip -6 rule add fwmark ${CoreConstants.TPROXY_MARK_IPV6} table ${CoreConstants.POLICY_ROUTING_TABLE} pref ${CoreConstants.POLICY_RULE_PREF_LOCAL}"))
                add(m("ip -6 route add local ::/0 dev lo table ${CoreConstants.POLICY_ROUTING_TABLE}"))
            }
        }

        /**
         * N03 post-verification: the loaded `inet mihomet` table must actually
         * contain the tproxy rules — one for IPv4, plus one more for IPv6 when
         * [ipv6Enabled] is true. Throwing here triggers the caller's rollback.
         */
        private fun verifyTproxyRulesApplied(ipv6Enabled: Boolean) {
            val expected = if (ipv6Enabled) 2 else 1
            val listing = Shell.cmd("nft list table inet mihomet 2>/dev/null | grep -c tproxy").exec()
            val count = listing.out.mapNotNull { it.trim().toIntOrNull() }.lastOrNull() ?: 0
            if (count < expected) {
                throw RuntimeException(
                    "TPROXY rules not applied: nft lists $count tproxy rule(s) in table inet mihomet, " +
                            "expected >= $expected (the kernel TPROXY support is missing or the apply failed) ${listing.errorTail()}"
                )
            }
        }

        /**
         * Pure builder for the nft ruleset loaded through `nft -f -`.
         *
         * Marks are family-specific: the ip -4 policy rules divert fwmark
         * [CoreConstants.TPROXY_MARK_IPV4] packets, the ip -6 rules created by
         * [buildRoutingSpecs] wait for fwmark [CoreConstants.TPROXY_MARK_IPV6] —
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
         * the sysctls snapshotted by [buildRoutingSpecs] (no-op when no
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
