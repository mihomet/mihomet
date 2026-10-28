package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.core.CoreConstants
import org.mihomet.app.core.firewall.IptablesEngine
import org.mihomet.app.core.firewall.NftablesEngine
import org.mihomet.app.core.root.RootShellManager
import org.mihomet.app.data.preferences.PerAppMode

/**
 * Strict command-builder tests for the root-mode firewall and daemon shell.
 *
 * Guards against command-level regressions that only show up on-device, most
 * notably the stale-fwmark-rule bug: a leftover `fwmark 666 lookup <dead-table>`
 * rule from a previous session blackholed every core DNS query in root mode.
 * Every port/mark/table/pref must come from [CoreConstants] (or an explicit
 * builder parameter) and nothing else.
 */
class FirewallCommandsStrictTest {

    // ---------- helpers ----------

    private fun allInts(text: String): Set<Int> =
        Regex("\\d+").findAll(text).map { it.value.toInt() }.toSet()

    /**
     * Numeric values legitimately present in the firewall command builders.
     * Anything outside this set is foreign magic that must fail the build.
     */
    private val allowedFirewallNumbers: Set<Int> = buildSet {
        // Routing values — must originate from CoreConstants only
        addAll(
            listOf(
                CoreConstants.CORE_ROUTING_MARK,      // 666 — core SO_MARK
                CoreConstants.TPROXY_MARK_IPV4,       // 20 — tproxy mark v4
                CoreConstants.TPROXY_MARK_IPV6,       // 25 — tproxy mark v6
                CoreConstants.POLICY_ROUTING_TABLE,   // 2025 — local routing table
                CoreConstants.POLICY_RULE_PREF_MAIN,  // 100 — fwmark-666 rule pref
                CoreConstants.POLICY_RULE_PREF_LOCAL, // 2025 — tproxy rule pref
                CoreConstants.DEFAULT_TPROXY_PORT,    // 7893 — tproxy port passed in these tests
                CoreConstants.DEFAULT_DNS_PORT        // 1053 — dnsPort passed in these tests
            )
        )
        // Non-routing system values
        addAll(
            listOf(
                53,   // DNS dport exemption inside the bypass chains
                3005, // net_admin gid (core owner bypass / setuidgid)
                0,    // uid 0, 0.0.0.0 octets, ::/0
                1,    // ip_forward=1, chain insert positions (OUTPUT 1 / PREROUTING 1), ::1
                2,    // rp_filter=2, "2>/dev/null" redirects
                6,    // "ip -6" / "ip6tables" address family
                4,    // "ipv4" inside /proc sysctl paths
                127, 8, 10, 12, 16, 172, 192, 168, 224, 255, 32 // bypass CIDR octets
            )
        )
    }

    private fun assertNoForeignNumbers(text: String, context: String) {
        val foreign = allInts(text).filter { it !in allowedFirewallNumbers }
        assertTrue(
            "$context must not contain numeric magic outside CoreConstants/system values, found: $foreign",
            foreign.isEmpty()
        )
    }

    private fun iptablesSetup(
        perAppMode: PerAppMode = PerAppMode.ALL,
        appUids: Set<Int> = emptySet(),
        ipv6Enabled: Boolean = true,
        tproxyPort: Int = CoreConstants.DEFAULT_TPROXY_PORT,
        defaultTable: String = "wlan0"
    ): List<String> = IptablesEngine.buildSetupCommands(
        tproxyPort = tproxyPort,
        dnsPort = CoreConstants.DEFAULT_DNS_PORT,
        perAppMode = perAppMode,
        appUids = appUids,
        ipv6Enabled = ipv6Enabled,
        defaultTable = defaultTable
    )

    // ---------- ports / marks come from CoreConstants only ----------

    @Test
    fun `iptables setup carries core routing constants and no foreign numeric magic`() {
        val cmds = iptablesSetup(ipv6Enabled = true)
        val joined = cmds.joinToString("\n")

        assertTrue(joined.contains("fwmark ${CoreConstants.CORE_ROUTING_MARK}"))        // 666
        assertTrue(joined.contains("--tproxy-mark ${CoreConstants.TPROXY_MARK_IPV4}"))  // 20
        assertTrue(joined.contains("fwmark ${CoreConstants.TPROXY_MARK_IPV6}"))         // 25
        assertTrue(joined.contains("table ${CoreConstants.POLICY_ROUTING_TABLE}"))      // 2025
        assertTrue(joined.contains("pref ${CoreConstants.POLICY_RULE_PREF_MAIN}"))      // 100
        assertTrue(joined.contains("pref ${CoreConstants.POLICY_RULE_PREF_LOCAL}"))     // 2025
        assertTrue(joined.contains("--on-port ${CoreConstants.DEFAULT_TPROXY_PORT}"))

        assertNoForeignNumbers(joined, "iptables setup commands")
    }

    @Test
    fun `iptables setup routes core traffic through the passed default table`() {
        val cmds = iptablesSetup(ipv6Enabled = true, defaultTable = "rmnet_data0")
        val joined = cmds.joinToString("\n")

        // fwmark 666 (core) must be routed to the detected interface, not a hardcoded one
        assertTrue(joined.contains("ip rule add fwmark ${CoreConstants.CORE_ROUTING_MARK} lookup rmnet_data0 pref ${CoreConstants.POLICY_RULE_PREF_MAIN}"))
        assertTrue(joined.contains("ip -6 rule add fwmark ${CoreConstants.CORE_ROUTING_MARK} lookup rmnet_data0 pref ${CoreConstants.POLICY_RULE_PREF_MAIN}"))
        // tproxy port parameter must flow through to the TPROXY targets
        val custom = iptablesSetup(ipv6Enabled = false, tproxyPort = 17993)
        val customJoined = custom.joinToString("\n")
        assertEquals(2, Regex("--on-port 17993").findAll(customJoined).count())
    }

    @Test
    fun `iptables setup purges stale pref-100 rules in a loop before adding new ones`() {
        // Regression: a leftover fwmark rule pointing at a dead network (previous
        // session on another connection) blackholed every core DNS query. A single
        // del is not enough — duplicates from repeated crashes must be purged too.
        val cmds = iptablesSetup(ipv6Enabled = false)
        val joined = cmds.joinToString("\n")

        assertTrue(
            "setup must purge stale fwmark rules with a while-loop",
            Regex("while ip rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN} 2>/dev/null; do true; done").containsMatchIn(joined)
        )
        val purgeIndex = cmds.indexOfFirst { it.startsWith("while ip rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN}") }
        val addIndex = cmds.indexOfFirst { it.startsWith("ip rule add fwmark ${CoreConstants.CORE_ROUTING_MARK}") }
        assertTrue("purge loop must run before any fwmark rule is re-added", purgeIndex in 0 until addIndex)
    }

    // ---------- iptables loop prevention and DNS interception ----------

    @Test
    fun `iptables setup bypasses core mark and core owner to prevent loops`() {
        val joined = iptablesSetup().joinToString("\n")

        // mark-666 bypass
        assertTrue(joined.contains("iptables -t mangle -A MIHOMET_OUTPUT -m mark --mark ${CoreConstants.CORE_ROUTING_MARK} -j ACCEPT"))
        assertTrue(joined.contains("iptables -t mangle -A MIHOMET_PREROUTING -m mark --mark ${CoreConstants.CORE_ROUTING_MARK} -j ACCEPT"))
        // owner bypass: core runs as root:net_admin (3005)
        assertTrue(joined.contains("iptables -t mangle -A MIHOMET_OUTPUT -m owner --uid-owner 0 --gid-owner 3005 -j ACCEPT"))
    }

    @Test
    fun `iptables setup never bypasses udp dns from private ranges`() {
        // Regression guard: udp dport 53 to private ranges must NOT be accepted by
        // the bypass chain, otherwise the core DNS hijack is silently bypassed.
        val bypassAccepts = iptablesSetup().filter { it.contains("-A MIHOMET_BYPASS -d") }

        for (net in listOf("127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")) {
            assertTrue(
                "udp dns (dport 53) to $net must stay exempt from the bypass chain",
                bypassAccepts.any { it.contains("-d $net -p udp ! --dport 53 -j ACCEPT") }
            )
            assertTrue(
                "non-udp traffic to $net must be bypassed",
                bypassAccepts.any { it.contains("-d $net ! -p udp -j ACCEPT") }
            )
            assertFalse(
                "an unconditional accept for $net would blackhole DNS interception",
                bypassAccepts.any {
                    it.contains("-d $net") && it.endsWith("-j ACCEPT") &&
                        "! --dport 53" !in it && "! -p udp" !in it
                }
            )
        }
        // Multicast / broadcast stay fully bypassed
        assertTrue(bypassAccepts.any { it.contains("-d 224.0.0.0/4 -j ACCEPT") })
        assertTrue(bypassAccepts.any { it.contains("-d 255.255.255.255/32 -j ACCEPT") })
    }

    // ---------- iptables per-app modes ----------

    @Test
    fun `iptables per-app whitelist returns matched uids and accepts the rest of traffic flow`() {
        val uids = setOf(10100, 10101)
        val joined = iptablesSetup(perAppMode = PerAppMode.WHITELIST, appUids = uids, ipv6Enabled = false).joinToString("\n")

        assertTrue(joined.contains("-N MIHOMET_UIDS"))
        for (uid in uids) {
            assertTrue("whitelisted uid $uid must RETURN from the uid chain", joined.contains("-A MIHOMET_UIDS -m owner --uid-owner $uid -j RETURN"))
        }
        assertTrue(joined.contains("-A MIHOMET_OUTPUT -j MIHOMET_UIDS"))
        assertTrue(joined.contains("-A MIHOMET_OUTPUT -j ACCEPT"))
    }

    @Test
    fun `iptables per-app blacklist accepts matched uids without uid chain`() {
        val uids = setOf(10100, 10101)
        val joined = iptablesSetup(perAppMode = PerAppMode.BLACKLIST, appUids = uids, ipv6Enabled = false).joinToString("\n")

        assertFalse("blacklist mode must not create the uid chain", joined.contains("MIHOMET_UIDS"))
        for (uid in uids) {
            assertTrue("blacklisted uid $uid must bypass the proxy", joined.contains("-A MIHOMET_OUTPUT -m owner --uid-owner $uid -j ACCEPT"))
        }
    }

    @Test
    fun `iptables per-app whitelist with empty uid set falls through to accept`() {
        val joined = iptablesSetup(perAppMode = PerAppMode.WHITELIST, appUids = emptySet(), ipv6Enabled = false).joinToString("\n")

        assertFalse(joined.contains("MIHOMET_UIDS"))
        assertTrue(joined.contains("-A MIHOMET_OUTPUT -j ACCEPT"))
        // only the core-owner bypass (uid 0) may carry --uid-owner
        assertTrue(joined.contains("--uid-owner 0 --gid-owner 3005"))
        assertFalse(Regex("--uid-owner (?!0\\s)").containsMatchIn(joined))
    }

    @Test
    fun `iptables per-app all mode captures everything without uid rules`() {
        val joined = iptablesSetup(perAppMode = PerAppMode.ALL, appUids = setOf(10100), ipv6Enabled = false).joinToString("\n")

        assertFalse(joined.contains("MIHOMET_UIDS"))
        // only the core-owner bypass (uid 0) may carry --uid-owner
        assertTrue(joined.contains("--uid-owner 0 --gid-owner 3005"))
        assertFalse(Regex("--uid-owner (?!0\\s)").containsMatchIn(joined))
    }

    // ---------- iptables ipv6 gating ----------

    @Test
    fun `iptables ipv6 rules follow ipv6Enabled flag`() {
        val on = iptablesSetup(ipv6Enabled = true).joinToString("\n")
        assertTrue(on.contains("ip -6 rule add fwmark ${CoreConstants.CORE_ROUTING_MARK}"))
        assertTrue(on.contains("ip -6 rule add fwmark ${CoreConstants.TPROXY_MARK_IPV6}"))
        assertTrue(on.contains("ip -6 route add local ::/0"))
        assertFalse("ipv6 must not be blocked when ipv6Enabled=true", on.contains("ip6tables"))

        val off = iptablesSetup(ipv6Enabled = false).joinToString("\n")
        assertFalse("ipv6 routing rules must not appear when ipv6Enabled=false", off.contains("ip -6 rule add"))
        assertTrue(
            off.contains("ip6tables -I OUTPUT 1 -m mark ! --mark ${CoreConstants.CORE_ROUTING_MARK} -j DROP 2>/dev/null || true")
        )
    }

    // ---------- iptables teardown: every chain created must be removed ----------

    @Test
    fun `iptables teardown removes every chain and jump created by setup`() {
        // WHITELIST with uids creates all four chains
        val setup = iptablesSetup(perAppMode = PerAppMode.WHITELIST, appUids = setOf(10100), ipv6Enabled = false)
        val chains = Regex("-N (MIHOMET_\\w+)").findAll(setup.joinToString("\n"))
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(
            setOf("MIHOMET_BYPASS", "MIHOMET_OUTPUT", "MIHOMET_UIDS", "MIHOMET_PREROUTING"),
            chains
        )

        val teardown = IptablesEngine.buildTeardownCommands().joinToString("\n")
        for (chain in chains) {
            assertTrue("teardown must flush $chain", teardown.contains("-F $chain"))
            assertTrue("teardown must delete $chain", teardown.contains("-X $chain"))
        }
        // every jump attached with -I must be detached with -D
        assertTrue(teardown.contains("-D OUTPUT -p tcp -j MIHOMET_OUTPUT"))
        assertTrue(teardown.contains("-D OUTPUT -p udp -j MIHOMET_OUTPUT"))
        assertTrue(teardown.contains("-D PREROUTING -p tcp -j MIHOMET_PREROUTING"))
        assertTrue(teardown.contains("-D PREROUTING -p udp -j MIHOMET_PREROUTING"))
        assertTrue(teardown.contains("ip6tables -D OUTPUT"))
        // stale-rule purge and local route cleanup
        assertTrue(teardown.contains("while ip rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN}"))
        assertTrue(teardown.contains("while ip -6 rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN}"))
        assertTrue(teardown.contains("ip route del local default dev lo table ${CoreConstants.POLICY_ROUTING_TABLE}"))

        assertNoForeignNumbers(teardown, "iptables teardown commands")
    }

    // ---------- nftables ----------

    @Test
    fun `nftables routing commands carry core constants and purge stale rules`() {
        val routing = NftablesEngine.buildRoutingCommands(ipv6Enabled = true, defaultTable = "wlan0")

        assertTrue(
            "routing batch must purge stale fwmark rules with a while-loop",
            Regex("while ip rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN} 2>/dev/null; do true; done").containsMatchIn(routing)
        )
        assertTrue(routing.contains("ip rule add fwmark ${CoreConstants.CORE_ROUTING_MARK} lookup wlan0 pref ${CoreConstants.POLICY_RULE_PREF_MAIN}"))
        assertTrue(routing.contains("ip rule add fwmark ${CoreConstants.TPROXY_MARK_IPV4} table ${CoreConstants.POLICY_ROUTING_TABLE} pref ${CoreConstants.POLICY_RULE_PREF_LOCAL}"))
        assertTrue(routing.contains("ip -6 rule add fwmark ${CoreConstants.TPROXY_MARK_IPV6}"))
        assertTrue(routing.contains("ip route add local 0.0.0.0/0 dev lo table ${CoreConstants.POLICY_ROUTING_TABLE}"))
        // purge precedes re-adding
        assertTrue(
            routing.indexOf("while ip rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN}") <
                routing.indexOf("ip rule add fwmark ${CoreConstants.CORE_ROUTING_MARK}")
        )

        val off = NftablesEngine.buildRoutingCommands(ipv6Enabled = false, defaultTable = "wlan0")
        assertFalse(off.contains("ip -6"))

        assertNoForeignNumbers(routing, "nftables routing commands")
    }

    @Test
    fun `nftables script bypasses core owner and intercepts dns via tproxy`() {
        val script = NftablesEngine.buildNftScript(
            tproxyPort = CoreConstants.DEFAULT_TPROXY_PORT,
            perAppMode = PerAppMode.ALL,
            appUids = emptySet(),
            ipv6Enabled = true
        )

        // owner bypass: root:net_admin
        assertTrue(script.contains("meta skuid 0 meta skgid 3005 accept"))
        // mark-666 bypass in both hooks
        assertEquals(2, Regex("meta mark ${CoreConstants.CORE_ROUTING_MARK} accept").findAll(script).count())
        // dns must not be bypassed from private ranges
        assertTrue(script.contains("udp dport != 53 accept"))
        assertTrue(script.contains("meta l4proto != udp accept"))
        // tproxy target
        assertTrue(script.contains("tproxy to :${CoreConstants.DEFAULT_TPROXY_PORT} accept"))
        // output hook marks remaining traffic
        assertTrue(script.contains("meta mark set ${CoreConstants.TPROXY_MARK_IPV4}"))
        // no ipv6 block when ipv6 is enabled
        assertFalse(script.contains("ip6 daddr"))

        assertNoForeignNumbers(script, "nftables script")
    }

    @Test
    fun `nftables per-app rules follow the mode`() {
        val uids = setOf(10100, 10101)

        val whitelist = NftablesEngine.buildNftScript(CoreConstants.DEFAULT_TPROXY_PORT, PerAppMode.WHITELIST, uids, ipv6Enabled = true)
        assertTrue(whitelist.contains("meta skuid != { 10100, 10101 } return"))

        val whitelistEmpty = NftablesEngine.buildNftScript(CoreConstants.DEFAULT_TPROXY_PORT, PerAppMode.WHITELIST, emptySet(), ipv6Enabled = true)
        assertTrue(Regex("^\\s*return$", RegexOption.MULTILINE).containsMatchIn(whitelistEmpty))
        assertFalse(whitelistEmpty.contains("skuid !="))

        val blacklist = NftablesEngine.buildNftScript(CoreConstants.DEFAULT_TPROXY_PORT, PerAppMode.BLACKLIST, uids, ipv6Enabled = true)
        assertTrue(blacklist.contains("meta skuid { 10100, 10101 } return"))
        assertFalse("blacklist must not use negated skuid", blacklist.contains("skuid !="))

        val all = NftablesEngine.buildNftScript(CoreConstants.DEFAULT_TPROXY_PORT, PerAppMode.ALL, uids, ipv6Enabled = true)
        // only the core-owner bypass (skuid 0) may appear
        assertEquals(1, Regex("meta skuid 0 meta skgid 3005 accept").findAll(all).count())
        assertFalse(all.contains("skuid {"))
        assertFalse(all.contains("skuid !="))
        assertFalse(Regex("^\\s*return$", RegexOption.MULTILINE).containsMatchIn(all))
    }

    @Test
    fun `nftables ipv6 block appears only when ipv6 is disabled`() {
        val off = NftablesEngine.buildNftScript(CoreConstants.DEFAULT_TPROXY_PORT, PerAppMode.ALL, emptySet(), ipv6Enabled = false)
        assertTrue(off.contains("ip6 daddr != ::1 drop"))

        val on = NftablesEngine.buildNftScript(CoreConstants.DEFAULT_TPROXY_PORT, PerAppMode.ALL, emptySet(), ipv6Enabled = true)
        assertFalse(on.contains("ip6 daddr"))
    }

    @Test
    fun `nftables teardown deletes the whole table and purges stale rules`() {
        val teardown = NftablesEngine.buildTeardownCommands().joinToString("\n")

        assertTrue(teardown.contains("while ip rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN}"))
        assertTrue(teardown.contains("while ip -6 rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN}"))
        assertTrue(teardown.contains("nft delete table inet mihomet"))
        assertTrue(teardown.contains("ip rule del fwmark ${CoreConstants.TPROXY_MARK_IPV4} table ${CoreConstants.POLICY_ROUTING_TABLE} pref ${CoreConstants.POLICY_RULE_PREF_LOCAL}"))
        assertTrue(teardown.contains("ip -6 rule del fwmark ${CoreConstants.TPROXY_MARK_IPV6} table ${CoreConstants.POLICY_ROUTING_TABLE} pref ${CoreConstants.POLICY_RULE_PREF_LOCAL}"))
        assertTrue(teardown.contains("ip route del local default dev lo table ${CoreConstants.POLICY_ROUTING_TABLE}"))

        assertNoForeignNumbers(teardown, "nftables teardown commands")
    }

    // ---------- root daemon shell ----------

    @Test
    fun `stopDaemon terminates via pid file and restores private dns without broad pkill`() {
        val cmds = RootShellManager.buildStopDaemonCommands()
        val joined = cmds.joinToString("\n")

        val pidBlock = cmds.first { "kill -15" in it }
        assertTrue(pidBlock.contains("if [ -f ${CoreConstants.ROOT_PID_FILE} ]"))
        assertTrue(pidBlock.contains("PID=\$(cat ${CoreConstants.ROOT_PID_FILE})"))
        assertTrue(pidBlock.contains("kill -15 \$PID 2>/dev/null"))
        assertTrue(pidBlock.contains("kill -9 \$PID 2>/dev/null"))
        assertTrue(pidBlock.contains("rm -f ${CoreConstants.ROOT_PID_FILE}"))

        // restore Private DNS saved by startDaemon
        val dnsBlock = cmds.first { "private_dns_mode" in it }
        assertTrue(dnsBlock.contains("settings put global private_dns_mode"))
        assertTrue(dnsBlock.contains("rm -f ${CoreConstants.ROOT_RUN_DIR}/.prev_dns"))

        // Regression: a broad pattern kill would murder unrelated processes
        assertFalse("stopDaemon must never use 'pkill -9 -f'", joined.contains("pkill -9 -f"))
        assertFalse(joined.contains("pkill"))
        // targeted cleanup of leftover core processes stays
        assertTrue(joined.contains("killall mihomo"))
        assertTrue(joined.contains("killall libmihomo.so"))

        val foreign = allInts(joined).filter { it !in setOf(0, 1, 2, 9, 15, 3005) }
        assertTrue("stop commands must not contain numeric magic, found: $foreign", foreign.isEmpty())
    }

    @Test
    fun `startDaemon spawns mihomo via setuidgid with config and pid capture`() {
        val cmds = RootShellManager.buildStartDaemonCommands(CoreConstants.ROOT_CONFIG_PATH)

        assertEquals(3, cmds.size)
        assertEquals("mkdir -p ${CoreConstants.ROOT_RUN_DIR}", cmds[0])

        val start = cmds[1]
        assertTrue(start.contains("setuidgid 0:3005"))
        assertTrue(start.contains("nohup \$EXEC_PREFIX ${CoreConstants.ROOT_BIN_PATH}"))
        assertTrue(start.contains("-d ${CoreConstants.ROOT_RUN_DIR} -f \"${CoreConstants.ROOT_CONFIG_PATH}\""))
        assertTrue(start.contains("> ${CoreConstants.ROOT_LOG_FILE} 2>&1 & echo \$! > ${CoreConstants.ROOT_PID_FILE}"))
        // busybox fallback when no setuidgid-capable busybox is found
        assertTrue(start.contains("else EXEC_PREFIX=\"\""))

        assertEquals("cat ${CoreConstants.ROOT_PID_FILE}", cmds[2])

        val foreign = allInts(cmds.joinToString("\n")).filter { it !in setOf(0, 1, 2, 3005) }
        assertTrue("start commands must not contain numeric magic, found: $foreign", foreign.isEmpty())
    }
}
