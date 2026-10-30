package org.mihomet.app

import java.net.InetAddress
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
 * Semantic command-builder tests for the root-mode firewall and daemon shell.
 *
 * The iptables/ip6tables rule builders are validated with a small packet-routing
 * simulator: synthetic packets (proto/uid/gid/destination/dport/mark) are walked
 * through the generated MIHOMET_OUTPUT + MIHOMET_BYPASS rules exactly like the
 * kernel would (MARK does not terminate traversal, ACCEPT does; the catch-all
 * ACCEPT keeps an already-set mark), and the final mark decides the outcome —
 * tproxy mark -> diverted into the local routing table -> TPROXY, unmarked ->
 * direct. This pins down the resulting packet routing rather than the mere
 * presence of command strings. Historical regressions guarded here:
 *  - the whitelist inversion (RETURN for selected uids + an unconditional ACCEPT
 *    made the MARK rules unreachable: whitelisted and non-whitelisted apps both
 *    went direct and per-app mode was dead);
 *  - the IPv6 mark mismatch (the `ip -6` policy rules wait for fwmark
 *    [CoreConstants.TPROXY_MARK_IPV6] while output marked everything with
 *    [CoreConstants.TPROXY_MARK_IPV4], so IPv6 silently stayed unproxied).
 *
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
                127, 8, 10, 12, 16, 172, 192, 168, 224, 255, 32, // bypass CIDR octets
                7, 80, 128 // IPv6 bypass CIDR components (::1/128, fc00::/7, fe80::/10, ff00::/8)
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

    // ---------- packet-routing simulator ----------

    /** A synthetic packet traversing the mangle OUTPUT chain. */
    private data class Packet(
        val proto: String, // "tcp" | "udp" | "icmp"
        val uid: Int,
        val gid: Int = 0,
        val dest: String,
        val dport: Int = 443,
        var mark: Int = 0
    )

    private fun pkt(proto: String, uid: Int, dest: String, dport: Int = 443, mark: Int = 0, gid: Int = 0) =
        Packet(proto, uid, gid, dest, dport, mark)

    /** Rules ("-A <chain> ...") of one chain, for one address family, in generation order. */
    private fun chainRules(cmds: List<String>, tool: String, chain: String): List<String> =
        cmds.filter { it.startsWith("$tool -t mangle -A $chain ") }
            .map { it.removePrefix("$tool -t mangle -A $chain ") }

    private fun inSubnet(cidr: String, dest: String): Boolean {
        val parts = cidr.split("/")
        val addr = InetAddress.getByName(dest)
        val net = InetAddress.getByName(parts[0])
        val a = addr.address
        val b = net.address
        if (a.size != b.size) return false
        var remaining = parts.getOrElse(1) { "${a.size * 8}" }.toInt()
        for (i in a.indices) {
            if (remaining <= 0) break
            val bits = minOf(8, remaining)
            val mask = (0xFF shl (8 - bits)) and 0xFF
            if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
            remaining -= bits
        }
        return true
    }

    private val RX_MARK_ACCEPT = Regex("^-m mark --mark (\\d+) -j ACCEPT$")
    private val RX_OWNER_GID_ACCEPT = Regex("^-m owner --uid-owner (\\d+) --gid-owner (\\d+) -j ACCEPT$")
    private val RX_UID_ACCEPT = Regex("^-m owner --uid-owner (\\d+) -j ACCEPT$")
    private val RX_UID_RETURN = Regex("^-m owner --uid-owner (\\d+) -j RETURN$")
    private val RX_PROTO_UID_MARK = Regex("^-p (tcp|udp) -m owner --uid-owner (\\d+) -j MARK --set-mark (\\d+)$")
    private val RX_PROTO_MARK = Regex("^-p (tcp|udp) -j MARK --set-mark (\\d+)$")
    private val RX_BYPASS_UDP_DNS = Regex("^-d (\\S+) -p udp ! --dport (\\d+) -j ACCEPT$")
    private val RX_BYPASS_NOT_UDP = Regex("^-d (\\S+) ! -p udp -j ACCEPT$")
    private val RX_BYPASS_DEST = Regex("^-d (\\S+) -j ACCEPT$")

    /**
     * Walks [rules] like the kernel would. Recognizes exactly the rule shapes the
     * engines emit — an unrecognized rule fails the test, keeping the simulator in
     * sync with the generators. Returns true when a terminating target (ACCEPT) hit.
     */
    private fun traverse(rules: List<String>, pkt: Packet, chains: Map<String, List<String>>): Boolean {
        for (rule in rules) {
            val markAccept = RX_MARK_ACCEPT.matchEntire(rule)
            if (markAccept != null) {
                if (pkt.mark == markAccept.groupValues[1].toInt()) return true
                continue
            }
            val ownerGidAccept = RX_OWNER_GID_ACCEPT.matchEntire(rule)
            if (ownerGidAccept != null) {
                if (pkt.uid == ownerGidAccept.groupValues[1].toInt() &&
                    pkt.gid == ownerGidAccept.groupValues[2].toInt()
                ) return true
                continue
            }
            if (rule == "-j MIHOMET_BYPASS") {
                val bypass = chains.getValue("MIHOMET_BYPASS")
                if (traverse(bypass, pkt, chains)) return true
                continue
            }
            val protoUidMark = RX_PROTO_UID_MARK.matchEntire(rule)
            if (protoUidMark != null) {
                // MARK does not terminate traversal: the packet keeps its mark and the
                // remaining rules are still evaluated
                if (pkt.proto == protoUidMark.groupValues[1] &&
                    pkt.uid == protoUidMark.groupValues[2].toInt()
                ) {
                    pkt.mark = protoUidMark.groupValues[3].toInt()
                }
                continue
            }
            val uidAccept = RX_UID_ACCEPT.matchEntire(rule)
            if (uidAccept != null) {
                if (pkt.uid == uidAccept.groupValues[1].toInt()) return true
                continue
            }
            val uidReturn = RX_UID_RETURN.matchEntire(rule)
            if (uidReturn != null) {
                // RETURN resumes traversal in the caller chain — a whitelisted uid that
                // RETURNs will NOT be marked by anything after the jump (inversion bug)
                if (pkt.uid == uidReturn.groupValues[1].toInt()) return false
                continue
            }
            if (rule == "-j ACCEPT") return true
            val protoMark = RX_PROTO_MARK.matchEntire(rule)
            if (protoMark != null) {
                if (pkt.proto == protoMark.groupValues[1]) {
                    pkt.mark = protoMark.groupValues[2].toInt()
                }
                continue
            }
            val bypassUdpDns = RX_BYPASS_UDP_DNS.matchEntire(rule)
            if (bypassUdpDns != null) {
                if (inSubnet(bypassUdpDns.groupValues[1], pkt.dest) &&
                    pkt.proto == "udp" &&
                    pkt.dport != bypassUdpDns.groupValues[2].toInt()
                ) return true
                continue
            }
            val bypassNotUdp = RX_BYPASS_NOT_UDP.matchEntire(rule)
            if (bypassNotUdp != null) {
                if (inSubnet(bypassNotUdp.groupValues[1], pkt.dest) && pkt.proto != "udp") return true
                continue
            }
            val bypassDest = RX_BYPASS_DEST.matchEntire(rule)
            if (bypassDest != null) {
                if (inSubnet(bypassDest.groupValues[1], pkt.dest)) return true
                continue
            }
            throw AssertionError("Routing simulator does not know rule: '$rule'")
        }
        return false
    }

    /** Final mark of [pkt] after traversing the generated mangle OUTPUT chain. */
    private fun outputMark(cmds: List<String>, tool: String, pkt: Packet): Int {
        val chains = mapOf(
            "MIHOMET_OUTPUT" to chainRules(cmds, tool, "MIHOMET_OUTPUT"),
            "MIHOMET_BYPASS" to chainRules(cmds, tool, "MIHOMET_BYPASS")
        )
        val p = pkt.copy()
        traverse(chains.getValue("MIHOMET_OUTPUT"), p, chains)
        return p.mark
    }

    private fun assertFinalMark(pkt: Packet, cmds: List<String>, tool: String, expectedMark: Int, message: String) {
        assertEquals(message, expectedMark, outputMark(cmds, tool, pkt))
    }

    private fun assertTproxy(pkt: Packet, cmds: List<String>, tool: String, tproxyMark: Int) {
        assertFinalMark(pkt, cmds, tool, tproxyMark, "packet $pkt must be diverted to tproxy (mark $tproxyMark)")
    }

    private fun assertDirect(pkt: Packet, cmds: List<String>, tool: String) {
        assertFinalMark(pkt, cmds, tool, 0, "packet $pkt must leave unmarked (direct route)")
    }

    /** Lines of one chain of the nft script, in order. */
    private fun nftChain(script: String, name: String): List<String> {
        val lines = script.lines()
        val start = lines.indexOfFirst { it.trim() == "chain $name {" }
        check(start >= 0) { "chain $name not found in nft script" }
        val out = mutableListOf<String>()
        for (i in start + 1 until lines.size) {
            val line = lines[i].trim()
            if (line == "}") break
            if (line.isNotEmpty()) out.add(line)
        }
        return out
    }

    // ---------- ports / marks come from CoreConstants only ----------

    @Test
    fun `iptables setup carries core routing constants and no foreign numeric magic`() {
        val cmds = iptablesSetup(ipv6Enabled = true)
        val joined = cmds.joinToString("\n")

        assertTrue(joined.contains("fwmark ${CoreConstants.CORE_ROUTING_MARK}"))        // 666
        assertTrue(joined.contains("--tproxy-mark ${CoreConstants.TPROXY_MARK_IPV4}"))  // 20
        assertTrue(joined.contains("--tproxy-mark ${CoreConstants.TPROXY_MARK_IPV6}"))  // 25
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
        // tproxy port parameter must flow through to the TPROXY targets (v4 tcp/udp only when ipv6 off)
        val custom = iptablesSetup(ipv6Enabled = false, tproxyPort = 17993)
        val customJoined = custom.joinToString("\n")
        assertEquals(2, Regex("--on-port 17993").findAll(customJoined).count())
    }

    @Test
    fun `iptables setup purges stale pref-100 rules in a loop before adding new ones`() {
        // Regression: a leftover fwmark rule pointing at a dead network (previous
        // session on another connection) blackholed every core DNS query. A single
        // del is not enough — duplicates from repeated crashes must be purged too.
        val cmds = iptablesSetup(ipv6Enabled = true)
        val joined = cmds.joinToString("\n")

        assertTrue(
            "setup must purge stale fwmark rules with a while-loop",
            Regex("while ip rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN} 2>/dev/null; do true; done").containsMatchIn(joined)
        )
        val purgeIndex = cmds.indexOfFirst { it.startsWith("while ip rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN}") }
        val addIndex = cmds.indexOfFirst { it.startsWith("ip rule add fwmark ${CoreConstants.CORE_ROUTING_MARK}") }
        assertTrue("purge loop must run before any fwmark rule is re-added", purgeIndex in 0 until addIndex)

        // IPv6 purge must be symmetric, otherwise a stale v6 rule blackholes core DNS over IPv6
        assertTrue(
            "setup must purge stale IPv6 fwmark rules with a while-loop",
            Regex("while ip -6 rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN} 2>/dev/null; do true; done").containsMatchIn(joined)
        )
        val purge6Index = cmds.indexOfFirst { it.startsWith("while ip -6 rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN}") }
        val add6Index = cmds.indexOfFirst { it.startsWith("ip -6 rule add fwmark ${CoreConstants.CORE_ROUTING_MARK}") }
        assertTrue("ipv6 purge loop must run before any ipv6 fwmark rule is re-added", purge6Index in 0 until add6Index)
    }

    // ---------- iptables loop prevention and DNS interception ----------

    @Test
    fun `iptables bypass rules always come first in the output chain in both families`() {
        for (mode in PerAppMode.entries) {
            val cmds = iptablesSetup(perAppMode = mode, appUids = setOf(1001), ipv6Enabled = true)
            for (tool in listOf("iptables", "ip6tables")) {
                val output = chainRules(cmds, tool, "MIHOMET_OUTPUT")
                assertTrue(
                    "$mode/$tool: mark-666 loop-prevention bypass must be rule #1",
                    output[0] == "-m mark --mark ${CoreConstants.CORE_ROUTING_MARK} -j ACCEPT"
                )
                assertTrue(
                    "$mode/$tool: core owner (root:net_admin) bypass must be rule #2",
                    output[1] == "-m owner --uid-owner 0 --gid-owner 3005 -j ACCEPT"
                )
                assertTrue(
                    "$mode/$tool: private-range bypass jump must be rule #3",
                    output[2] == "-j MIHOMET_BYPASS"
                )
            }
        }
    }

    @Test
    fun `iptables output routing honors loop prevention and the private-range bypass`() {
        val cmds = iptablesSetup(perAppMode = PerAppMode.ALL, ipv6Enabled = false)

        // core packets (fwmark 666) must never be re-marked or looped
        assertFinalMark(
            pkt("tcp", uid = 0, dest = "1.2.3.4", mark = CoreConstants.CORE_ROUTING_MARK),
            cmds, "iptables", CoreConstants.CORE_ROUTING_MARK,
            "core packet (fwmark 666) must bypass the mangle chain untouched"
        )
        // core owner root:net_admin must bypass unmarked
        assertDirect(pkt("tcp", uid = 0, dest = "1.2.3.4", gid = 3005), cmds, "iptables")

        // private/local ranges go direct, multicast/broadcast too
        assertDirect(pkt("tcp", 1234, "192.168.1.5"), cmds, "iptables")
        assertDirect(pkt("udp", 1234, "10.0.0.1"), cmds, "iptables")
        assertDirect(pkt("tcp", 1234, "172.16.5.5"), cmds, "iptables")
        assertDirect(pkt("tcp", 1234, "127.0.0.1", dport = 8080), cmds, "iptables")
        assertDirect(pkt("tcp", 1234, "224.0.0.1"), cmds, "iptables")
        assertDirect(pkt("udp", 1234, "255.255.255.255"), cmds, "iptables")

        // but UDP DNS to those ranges must stay interceptable (marked for tproxy)
        assertTproxy(pkt("udp", 1234, "192.168.1.5", dport = 53), cmds, "iptables", CoreConstants.TPROXY_MARK_IPV4)
        assertTproxy(pkt("udp", 1234, "10.0.0.1", dport = 53), cmds, "iptables", CoreConstants.TPROXY_MARK_IPV4)
        assertTproxy(pkt("udp", 1234, "127.0.0.1", dport = 53), cmds, "iptables", CoreConstants.TPROXY_MARK_IPV4)
        assertTproxy(pkt("udp", 1234, "172.16.0.1", dport = 53), cmds, "iptables", CoreConstants.TPROXY_MARK_IPV4)
    }

    @Test
    fun `iptables setup never bypasses udp dns from private ranges`() {
        // Regression guard: udp dport 53 to private ranges must NOT be accepted by
        // the bypass chain, otherwise the core DNS hijack is silently bypassed.
        val bypassAccepts = iptablesSetup(ipv6Enabled = false).filter { it.contains("-A MIHOMET_BYPASS -d") }

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

    // ---------- iptables prerouting (tproxy interception) ----------

    @Test
    fun `iptables prerouting tproxies the marked packets per family`() {
        val on = iptablesSetup(ipv6Enabled = true).joinToString("\n")
        assertTrue(
            on.contains("iptables -t mangle -A MIHOMET_PREROUTING -p tcp -j TPROXY --on-port ${CoreConstants.DEFAULT_TPROXY_PORT} --tproxy-mark ${CoreConstants.TPROXY_MARK_IPV4}")
        )
        assertTrue(
            on.contains("iptables -t mangle -A MIHOMET_PREROUTING -p udp -j TPROXY --on-port ${CoreConstants.DEFAULT_TPROXY_PORT} --tproxy-mark ${CoreConstants.TPROXY_MARK_IPV4}")
        )
        assertTrue(
            on.contains("ip6tables -t mangle -A MIHOMET_PREROUTING -p tcp -j TPROXY --on-port ${CoreConstants.DEFAULT_TPROXY_PORT} --tproxy-mark ${CoreConstants.TPROXY_MARK_IPV6}")
        )
        assertTrue(
            on.contains("ip6tables -t mangle -A MIHOMET_PREROUTING -p udp -j TPROXY --on-port ${CoreConstants.DEFAULT_TPROXY_PORT} --tproxy-mark ${CoreConstants.TPROXY_MARK_IPV6}")
        )
        // loop prevention must exist in prerouting for both families
        assertTrue(on.contains("iptables -t mangle -A MIHOMET_PREROUTING -m mark --mark ${CoreConstants.CORE_ROUTING_MARK} -j ACCEPT"))
        assertTrue(on.contains("ip6tables -t mangle -A MIHOMET_PREROUTING -m mark --mark ${CoreConstants.CORE_ROUTING_MARK} -j ACCEPT"))
    }

    // ---------- iptables per-app modes: resulting packet routing ----------

    @Test
    fun `iptables whitelist diverts only selected uids to tproxy and leaves the rest direct`() {
        val uids = setOf(1001, 1002)
        val cmds = iptablesSetup(perAppMode = PerAppMode.WHITELIST, appUids = uids, ipv6Enabled = false)

        // selected uids -> tproxy (v4 mark 20)
        assertTproxy(pkt("tcp", 1001, "1.2.3.4"), cmds, "iptables", CoreConstants.TPROXY_MARK_IPV4)
        assertTproxy(pkt("udp", 1001, "8.8.8.8", dport = 5353), cmds, "iptables", CoreConstants.TPROXY_MARK_IPV4)
        assertTproxy(pkt("tcp", 1002, "1.1.1.1"), cmds, "iptables", CoreConstants.TPROXY_MARK_IPV4)
        // everyone else -> direct
        assertDirect(pkt("tcp", 2000, "1.2.3.4"), cmds, "iptables")
        assertDirect(pkt("udp", 2000, "8.8.8.8"), cmds, "iptables")

        // inversion regression guards: no RETURN, no legacy uid chain
        val joined = cmds.joinToString("\n")
        assertFalse("whitelist must not create the legacy uid chain", joined.contains("MIHOMET_UIDS"))
        assertFalse("RETURN for a whitelisted uid would invert the whitelist", joined.contains("-j RETURN"))

        // every per-uid MARK rule must precede the catch-all ACCEPT
        val output = chainRules(cmds, "iptables", "MIHOMET_OUTPUT")
        val catchAll = output.indexOf("-j ACCEPT")
        assertTrue("whitelist output chain must end with a catch-all ACCEPT", catchAll >= 0)
        for (uid in uids) {
            val markIdx = output.indexOfFirst {
                it.contains("-m owner --uid-owner $uid -j MARK --set-mark ${CoreConstants.TPROXY_MARK_IPV4}")
            }
            assertTrue("uid $uid must be MARKed inside the OUTPUT chain", markIdx >= 0)
            assertTrue(
                "uid $uid MARK rule must precede the catch-all ACCEPT (whitelist inversion regression)",
                markIdx < catchAll
            )
        }
    }

    @Test
    fun `iptables whitelist with empty uid set sends everything direct`() {
        val cmds = iptablesSetup(perAppMode = PerAppMode.WHITELIST, appUids = emptySet(), ipv6Enabled = false)

        assertDirect(pkt("tcp", 1234, "1.2.3.4"), cmds, "iptables")
        assertDirect(pkt("udp", 1234, "8.8.8.8"), cmds, "iptables")

        val joined = cmds.joinToString("\n")
        assertFalse(joined.contains("MIHOMET_UIDS"))
        assertFalse("empty whitelist must not mark anything", joined.contains("--set-mark"))
        // only the core-owner bypass (uid 0) may carry --uid-owner
        assertFalse(Regex("--uid-owner (?!0\\s)").containsMatchIn(joined))
    }

    @Test
    fun `iptables blacklist excludes only the selected uids from tproxy`() {
        val cmds = iptablesSetup(perAppMode = PerAppMode.BLACKLIST, appUids = setOf(3001), ipv6Enabled = false)

        assertDirect(pkt("tcp", 3001, "1.2.3.4"), cmds, "iptables")
        assertDirect(pkt("udp", 3001, "8.8.8.8"), cmds, "iptables")
        assertTproxy(pkt("tcp", 9999, "1.2.3.4"), cmds, "iptables", CoreConstants.TPROXY_MARK_IPV4)
        assertTproxy(pkt("udp", 9999, "8.8.8.8"), cmds, "iptables", CoreConstants.TPROXY_MARK_IPV4)
        // the mark rules must follow the per-uid ACCEPTs, otherwise excluded apps would be marked too
        val output = chainRules(cmds, "iptables", "MIHOMET_OUTPUT")
        val uidAcceptIdx = output.indexOf("-m owner --uid-owner 3001 -j ACCEPT")
        val markIdx = output.indexOfFirst { it.contains("-j MARK --set-mark ${CoreConstants.TPROXY_MARK_IPV4}") }
        assertTrue(uidAcceptIdx in 0 until markIdx)
        assertFalse(cmds.joinToString("\n").contains("MIHOMET_UIDS"))
    }

    @Test
    fun `iptables all mode marks every tcp and udp packet but not other protocols`() {
        val cmds = iptablesSetup(perAppMode = PerAppMode.ALL, appUids = setOf(10100), ipv6Enabled = false)

        assertTproxy(pkt("tcp", 10100, "1.2.3.4"), cmds, "iptables", CoreConstants.TPROXY_MARK_IPV4)
        assertTproxy(pkt("udp", 10100, "8.8.8.8"), cmds, "iptables", CoreConstants.TPROXY_MARK_IPV4)
        // only tcp/udp are diverted by TPROXY; everything else must stay unmarked (direct)
        assertDirect(pkt("icmp", 10100, "1.2.3.4"), cmds, "iptables")

        val joined = cmds.joinToString("\n")
        assertFalse(joined.contains("MIHOMET_UIDS"))
        // only the core-owner bypass (uid 0) may carry --uid-owner
        assertFalse(Regex("--uid-owner (?!0\\s)").containsMatchIn(joined))
    }

    // ---------- iptables ipv6 gating ----------

    @Test
    fun `iptables with ipv6 enabled mirrors the pipeline for ip6tables with the v6 tproxy mark`() {
        val cmds = iptablesSetup(perAppMode = PerAppMode.ALL, ipv6Enabled = true)
        val joined = cmds.joinToString("\n")

        // the ip -6 policy rules the marking depends on must be present
        assertTrue(joined.contains("ip -6 rule add fwmark ${CoreConstants.TPROXY_MARK_IPV6} table ${CoreConstants.POLICY_ROUTING_TABLE} pref ${CoreConstants.POLICY_RULE_PREF_LOCAL}"))
        assertTrue(joined.contains("ip -6 route add local ::/0 dev lo table ${CoreConstants.POLICY_ROUTING_TABLE}"))

        // internet-bound v6 traffic must be marked with the v6 tproxy mark (25), never 20
        assertTproxy(pkt("tcp", 1234, "2606:4700::1111"), cmds, "ip6tables", CoreConstants.TPROXY_MARK_IPV6)
        assertTproxy(pkt("udp", 1234, "2606:4700::1111"), cmds, "ip6tables", CoreConstants.TPROXY_MARK_IPV6)
        // ... while v4 keeps its own mark (20)
        assertTproxy(pkt("tcp", 1234, "1.2.3.4"), cmds, "iptables", CoreConstants.TPROXY_MARK_IPV4)

        // v6 bypass mirrors: loopback / ULA / link-local direct, ipv6 multicast direct
        assertDirect(pkt("tcp", 1234, "::1", dport = 8080), cmds, "ip6tables")
        assertDirect(pkt("tcp", 1234, "fc00::1"), cmds, "ip6tables")
        assertDirect(pkt("udp", 1234, "fe80::1"), cmds, "ip6tables")
        assertDirect(pkt("udp", 1234, "ff02::1"), cmds, "ip6tables")
        // ... with the DNS exception preserved
        assertTproxy(pkt("udp", 1234, "fe80::1", dport = 53), cmds, "ip6tables", CoreConstants.TPROXY_MARK_IPV6)
        assertTproxy(pkt("udp", 1234, "fc00::1", dport = 53), cmds, "ip6tables", CoreConstants.TPROXY_MARK_IPV6)

        // loop prevention must be mirrored for the core's own v6 traffic
        assertFinalMark(
            pkt("tcp", uid = 0, dest = "2606:4700::1111", mark = CoreConstants.CORE_ROUTING_MARK),
            cmds, "ip6tables", CoreConstants.CORE_ROUTING_MARK,
            "core ipv6 packet (fwmark 666) must bypass untouched"
        )

        // no v6 OUTPUT block rule while ipv6 is enabled
        assertFalse("ipv6 must not be blocked when ipv6Enabled=true", joined.contains("-I OUTPUT 1 -m mark"))
    }

    @Test
    fun `iptables whitelist marks selected uids with the v6 tproxy mark when ipv6 is enabled`() {
        val cmds = iptablesSetup(perAppMode = PerAppMode.WHITELIST, appUids = setOf(1001), ipv6Enabled = true)

        assertTproxy(pkt("tcp", 1001, "2606:4700::1111"), cmds, "ip6tables", CoreConstants.TPROXY_MARK_IPV6)
        assertDirect(pkt("tcp", 2000, "2606:4700::1111"), cmds, "ip6tables")

        val output6 = chainRules(cmds, "ip6tables", "MIHOMET_OUTPUT")
        val catchAll = output6.indexOf("-j ACCEPT")
        val markIdx = output6.indexOfFirst {
            it.contains("-m owner --uid-owner 1001 -j MARK --set-mark ${CoreConstants.TPROXY_MARK_IPV6}")
        }
        assertTrue(
            "v6 whitelist MARK rule must precede the catch-all ACCEPT (inversion regression)",
            markIdx in 0 until catchAll
        )
    }

    @Test
    fun `iptables with ipv6 disabled has no v6 marking and blocks ipv6 output`() {
        val cmds = iptablesSetup(perAppMode = PerAppMode.ALL, ipv6Enabled = false)
        val joined = cmds.joinToString("\n")

        assertFalse("ipv6 routing rules must not appear when ipv6Enabled=false", joined.contains("ip -6 rule add"))
        assertFalse("no v6 marking when disabled", joined.contains("--set-mark ${CoreConstants.TPROXY_MARK_IPV6}"))
        assertFalse("no v6 mangle chains when disabled", joined.contains("ip6tables -t mangle"))
        assertTrue(
            joined.contains("ip6tables -I OUTPUT 1 -m mark ! --mark ${CoreConstants.CORE_ROUTING_MARK} -j DROP 2>/dev/null || true")
        )
    }

    // ---------- iptables teardown: every chain created must be removed ----------

    @Test
    fun `iptables teardown removes every chain and jump created by setup`() {
        // WHITELIST with uids creates the three chains per family (no legacy UIDS chain anymore)
        val setup = iptablesSetup(perAppMode = PerAppMode.WHITELIST, appUids = setOf(10100), ipv6Enabled = true)
        val joinedSetup = setup.joinToString("\n")
        val created4 = Regex("^iptables -t mangle -N (MIHOMET_\\w+)", RegexOption.MULTILINE)
            .findAll(joinedSetup).map { it.groupValues[1] }.toSet()
        val created6 = Regex("^ip6tables -t mangle -N (MIHOMET_\\w+)", RegexOption.MULTILINE)
            .findAll(joinedSetup).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("MIHOMET_BYPASS", "MIHOMET_OUTPUT", "MIHOMET_PREROUTING"), created4)
        assertEquals("ipv6 chains must mirror the ipv4 chains", created4, created6)

        val teardown = IptablesEngine.buildTeardownCommands()
        val joined = teardown.joinToString("\n")
        // every chain — plus the legacy MIHOMET_UIDS for stale leftovers — flushed and deleted in both families
        for (chain in created4 + "MIHOMET_UIDS") {
            assertTrue("teardown must flush $chain", teardown.contains("iptables -t mangle -F $chain 2>/dev/null || true"))
            assertTrue("teardown must delete $chain", teardown.contains("iptables -t mangle -X $chain 2>/dev/null || true"))
            assertTrue("teardown must flush $chain (ipv6)", teardown.contains("ip6tables -t mangle -F $chain 2>/dev/null || true"))
            assertTrue("teardown must delete $chain (ipv6)", teardown.contains("ip6tables -t mangle -X $chain 2>/dev/null || true"))
        }
        // every jump attached with -I must be detached with -D, in both families
        for (tool in listOf("iptables", "ip6tables")) {
            assertTrue(teardown.contains("$tool -t mangle -D OUTPUT -p tcp -j MIHOMET_OUTPUT 2>/dev/null || true"))
            assertTrue(teardown.contains("$tool -t mangle -D OUTPUT -p udp -j MIHOMET_OUTPUT 2>/dev/null || true"))
            assertTrue(teardown.contains("$tool -t mangle -D PREROUTING -p tcp -j MIHOMET_PREROUTING 2>/dev/null || true"))
            assertTrue(teardown.contains("$tool -t mangle -D PREROUTING -p udp -j MIHOMET_PREROUTING 2>/dev/null || true"))
        }
        assertTrue(teardown.contains("ip6tables -D OUTPUT -m mark ! --mark ${CoreConstants.CORE_ROUTING_MARK} -j DROP 2>/dev/null || true"))
        // stale-rule purge and local route cleanup, both families
        assertTrue(joined.contains("while ip rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN}"))
        assertTrue(joined.contains("while ip -6 rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN}"))
        assertTrue(joined.contains("ip rule del fwmark ${CoreConstants.TPROXY_MARK_IPV4} table ${CoreConstants.POLICY_ROUTING_TABLE} pref ${CoreConstants.POLICY_RULE_PREF_LOCAL}"))
        assertTrue(joined.contains("ip -6 rule del fwmark ${CoreConstants.TPROXY_MARK_IPV6} table ${CoreConstants.POLICY_ROUTING_TABLE} pref ${CoreConstants.POLICY_RULE_PREF_LOCAL}"))
        assertTrue(joined.contains("ip route del local default dev lo table ${CoreConstants.POLICY_ROUTING_TABLE}"))
        assertTrue(joined.contains("ip -6 route del local default dev lo table ${CoreConstants.POLICY_ROUTING_TABLE}"))

        assertNoForeignNumbers(joined, "iptables teardown commands")
    }

    // ---------- nftables routing ----------

    @Test
    fun `nftables routing commands carry core constants and purge stale rules`() {
        val routing = NftablesEngine.buildRoutingCommands(ipv6Enabled = true, defaultTable = "wlan0")

        assertTrue(
            "routing batch must purge stale fwmark rules with a while-loop",
            Regex("while ip rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN} 2>/dev/null; do true; done").containsMatchIn(routing)
        )
        assertTrue(routing.contains("ip rule add fwmark ${CoreConstants.CORE_ROUTING_MARK} lookup wlan0 pref ${CoreConstants.POLICY_RULE_PREF_MAIN}"))
        assertTrue(routing.contains("ip rule add fwmark ${CoreConstants.TPROXY_MARK_IPV4} table ${CoreConstants.POLICY_ROUTING_TABLE} pref ${CoreConstants.POLICY_RULE_PREF_LOCAL}"))
        assertTrue(routing.contains("ip -6 rule add fwmark ${CoreConstants.TPROXY_MARK_IPV6} table ${CoreConstants.POLICY_ROUTING_TABLE} pref ${CoreConstants.POLICY_RULE_PREF_LOCAL}"))
        assertTrue(routing.contains("ip route add local 0.0.0.0/0 dev lo table ${CoreConstants.POLICY_ROUTING_TABLE}"))
        assertTrue(routing.contains("ip -6 route add local ::/0 dev lo table ${CoreConstants.POLICY_ROUTING_TABLE}"))
        // purge precedes re-adding, in both families
        assertTrue(
            routing.indexOf("while ip rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN}") <
                routing.indexOf("ip rule add fwmark ${CoreConstants.CORE_ROUTING_MARK}")
        )
        assertTrue(
            routing.indexOf("while ip -6 rule del pref ${CoreConstants.POLICY_RULE_PREF_MAIN}") <
                routing.indexOf("ip -6 rule add fwmark ${CoreConstants.TPROXY_MARK_IPV6}")
        )

        val off = NftablesEngine.buildRoutingCommands(ipv6Enabled = false, defaultTable = "wlan0")
        assertFalse(off.contains("ip -6"))

        assertNoForeignNumbers(routing, "nftables routing commands")
    }

    // ---------- nftables chains: family-specific marks ----------

    @Test
    fun `nftables output chain marks ipv4 with 20 and ipv6 with 25 in the right order`() {
        val script = NftablesEngine.buildNftScript(
            CoreConstants.DEFAULT_TPROXY_PORT, PerAppMode.ALL, emptySet(), ipv6Enabled = true
        )
        val output = nftChain(script, "output")

        assertEquals("type route hook output priority mangle; policy accept;", output[0])
        // bypass order: mark-666 loop prevention and core owner must come first
        assertEquals("meta mark ${CoreConstants.CORE_ROUTING_MARK} accept", output[1])
        assertEquals("meta skuid 0 meta skgid 3005 accept", output[2])
        assertEquals("jump bypass", output[3])
        // family-specific marking: v4 -> 20, v6 -> 25 (the ip -6 rules wait for 25)
        assertEquals(
            "meta nfproto ipv4 meta l4proto { tcp, udp } meta mark set ${CoreConstants.TPROXY_MARK_IPV4}",
            output[4]
        )
        assertEquals(
            "meta nfproto ipv6 meta l4proto { tcp, udp } meta mark set ${CoreConstants.TPROXY_MARK_IPV6}",
            output[5]
        )
        assertFalse(
            "ipv6 must never be marked with the ipv4 tproxy mark",
            output.any { it.contains("nfproto ipv6") && it.contains("mark set ${CoreConstants.TPROXY_MARK_IPV4}") }
        )

        val off = nftChain(
            NftablesEngine.buildNftScript(CoreConstants.DEFAULT_TPROXY_PORT, PerAppMode.ALL, emptySet(), ipv6Enabled = false),
            "output"
        )
        assertTrue(off.contains("meta nfproto ipv4 meta l4proto { tcp, udp } meta mark set ${CoreConstants.TPROXY_MARK_IPV4}"))
        assertTrue("no ipv6 marking when ipv6 is disabled", off.none { it.contains("nfproto ipv6") })
        assertTrue("ipv6 must be blocked when disabled", off.contains("ip6 daddr != ::1 drop"))
    }

    @Test
    fun `nftables prerouting tproxies tcp and udp per family`() {
        val script = NftablesEngine.buildNftScript(
            CoreConstants.DEFAULT_TPROXY_PORT, PerAppMode.ALL, emptySet(), ipv6Enabled = true
        )
        val prerouting = nftChain(script, "prerouting")

        assertEquals("meta mark ${CoreConstants.CORE_ROUTING_MARK} accept", prerouting[1])
        assertEquals("jump bypass", prerouting[2])
        assertTrue(
            prerouting.contains("meta nfproto ipv4 meta l4proto { tcp, udp } meta mark set ${CoreConstants.TPROXY_MARK_IPV4} tproxy to :${CoreConstants.DEFAULT_TPROXY_PORT} accept")
        )
        assertTrue(
            prerouting.contains("meta nfproto ipv6 meta l4proto { tcp, udp } meta mark set ${CoreConstants.TPROXY_MARK_IPV6} tproxy to :${CoreConstants.DEFAULT_TPROXY_PORT} accept")
        )

        val off = nftChain(
            NftablesEngine.buildNftScript(CoreConstants.DEFAULT_TPROXY_PORT, PerAppMode.ALL, emptySet(), ipv6Enabled = false),
            "prerouting"
        )
        assertTrue(
            off.contains("meta nfproto ipv4 meta l4proto { tcp, udp } meta mark set ${CoreConstants.TPROXY_MARK_IPV4} tproxy to :${CoreConstants.DEFAULT_TPROXY_PORT} accept")
        )
        assertTrue("no ipv6 tproxy when ipv6 is disabled", off.none { it.contains("nfproto ipv6") })
    }

    @Test
    fun `nftables bypass keeps dns interception and mirrors private ranges for ipv6`() {
        val bypass = nftChain(
            NftablesEngine.buildNftScript(CoreConstants.DEFAULT_TPROXY_PORT, PerAppMode.ALL, emptySet(), ipv6Enabled = true),
            "bypass"
        )

        // v4 private ranges: bypass everything except udp dport 53 (DNS interception)
        assertTrue(bypass.contains("ip daddr { 127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16 } udp dport != 53 accept"))
        assertTrue(bypass.contains("ip daddr { 127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16 } meta l4proto != udp accept"))
        // v6 mirror: loopback / ULA / link-local, same DNS exception
        assertTrue(bypass.contains("ip6 daddr { ::1/128, fc00::/7, fe80::/10 } udp dport != 53 accept"))
        assertTrue(bypass.contains("ip6 daddr { ::1/128, fc00::/7, fe80::/10 } meta l4proto != udp accept"))

        // the only unconditional destination accepts must be multicast/broadcast
        val unconditional = bypass.filter {
            it.contains("daddr") && it.endsWith("accept") &&
                "dport != 53" !in it && "l4proto != udp" !in it
        }
        assertEquals(
            listOf(
                "ip daddr { 224.0.0.0/4, 255.255.255.255/32 } accept",
                "ip6 daddr ff00::/8 accept"
            ),
            unconditional
        )

        val off = nftChain(
            NftablesEngine.buildNftScript(CoreConstants.DEFAULT_TPROXY_PORT, PerAppMode.ALL, emptySet(), ipv6Enabled = false),
            "bypass"
        )
        assertTrue("no ipv6 bypass rules when ipv6 is disabled", off.none { it.contains("ip6 daddr") })
    }

    @Test
    fun `nftables per-app rules follow the mode and precede the family marks`() {
        val uids = setOf(10100, 10101)

        val whitelist = nftChain(
            NftablesEngine.buildNftScript(CoreConstants.DEFAULT_TPROXY_PORT, PerAppMode.WHITELIST, uids, ipv6Enabled = true),
            "output"
        )
        val perAppIdx = whitelist.indexOfFirst { it.contains("meta skuid != { 10100, 10101 } return") }
        val v4MarkIdx = whitelist.indexOfFirst { it.contains("meta mark set ${CoreConstants.TPROXY_MARK_IPV4}") }
        assertTrue(
            "whitelist return rule must precede the family mark rules",
            perAppIdx in 0 until v4MarkIdx
        )
        assertFalse("whitelist must not use an inclusive skuid set", whitelist.any { it.contains("skuid {") })

        val whitelistEmpty = nftChain(
            NftablesEngine.buildNftScript(CoreConstants.DEFAULT_TPROXY_PORT, PerAppMode.WHITELIST, emptySet(), ipv6Enabled = true),
            "output"
        )
        assertTrue(whitelistEmpty.contains("return"))
        assertFalse(whitelistEmpty.contains("skuid !="))

        val blacklist = nftChain(
            NftablesEngine.buildNftScript(CoreConstants.DEFAULT_TPROXY_PORT, PerAppMode.BLACKLIST, uids, ipv6Enabled = true),
            "output"
        )
        assertTrue(blacklist.contains("meta skuid { 10100, 10101 } return"))
        assertFalse("blacklist must not use negated skuid", blacklist.contains("skuid !="))

        val all = nftChain(
            NftablesEngine.buildNftScript(CoreConstants.DEFAULT_TPROXY_PORT, PerAppMode.ALL, uids, ipv6Enabled = true),
            "output"
        )
        // only the core-owner bypass (skuid 0) may appear
        assertEquals(1, all.count { it.contains("meta skuid 0 meta skgid 3005 accept") })
        assertFalse(all.any { it.contains("skuid {") })
        assertFalse(all.any { it.contains("skuid !=") })
        assertFalse(all.contains("return"))
    }

    @Test
    fun `nftables script carries no foreign numeric magic in either ipv6 mode`() {
        // empty uid set so the scanner sees only the builders' own numbers
        // (per-app uid literals are covered by the per-app tests)
        for (ipv6 in listOf(true, false)) {
            val script = NftablesEngine.buildNftScript(
                CoreConstants.DEFAULT_TPROXY_PORT, PerAppMode.WHITELIST, emptySet(), ipv6Enabled = ipv6
            )
            assertNoForeignNumbers(script, "nftables script (ipv6Enabled=$ipv6)")
        }
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
