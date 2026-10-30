package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.core.CoreConstants

/**
 * Strict consistency tests for [CoreConstants] — the single source of truth for
 * core integration. The values pin the documented scheme:
 *
 *  - every listener port is pairwise distinct (mixed / tproxy / redir / dns /
 *    controller / probe-controller can never collide on one host);
 *  - policy routing table 2025, TProxy marks 20 (IPv4) / 25 (IPv6), both distinct
 *    from the core routing mark 666;
 *  - TUN lives at 198.18.0.1/30 with DNS at 198.18.0.2 — the /30 intentionally sits
 *    inside the fake-ip-range 198.18.0.1/16 configured by ConfigGenerator
 *    (same scheme Clash Meta for Android uses);
 *  - root-mode paths are all rooted under ROOT_BASE_DIR;
 *  - generateRandomHexSecret emits exactly N lowercase hex chars, unique per call.
 */
class CoreConstantsConsistencyStrictTest {

    @Test
    fun `test listener ports are pairwise distinct`() {
        val ports = mapOf(
            "mixed" to CoreConstants.DEFAULT_MIXED_PORT,
            "tproxy" to CoreConstants.DEFAULT_TPROXY_PORT,
            "redir" to CoreConstants.DEFAULT_REDIR_PORT,
            "dns" to CoreConstants.DEFAULT_DNS_PORT,
            "controller" to CoreConstants.DEFAULT_CONTROLLER_PORT,
            // The temporary probe instance binds its own external controller — it must not
            // collide with any permanent listener either.
            "probe-controller" to CoreConstants.PROBE_PORT,
        )
        assertEquals(
            "listener ports must be pairwise distinct, got: $ports",
            ports.size,
            ports.values.toSet().size
        )
    }

    @Test
    fun `test policy routing table matches documented value 2025`() {
        assertEquals(2025, CoreConstants.POLICY_ROUTING_TABLE)
    }

    @Test
    fun `test tproxy marks are 20 and 25, distinct from each other and from core mark 666`() {
        assertEquals(20, CoreConstants.TPROXY_MARK_IPV4)
        assertEquals(25, CoreConstants.TPROXY_MARK_IPV6)
        assertNotEquals(
            "IPv4 and IPv6 tproxy marks must differ",
            CoreConstants.TPROXY_MARK_IPV4,
            CoreConstants.TPROXY_MARK_IPV6
        )
        assertNotEquals(
            "tproxy mark must not equal the core SO_MARK",
            CoreConstants.CORE_ROUTING_MARK,
            CoreConstants.TPROXY_MARK_IPV4
        )
        assertNotEquals(
            "tproxy mark must not equal the core SO_MARK",
            CoreConstants.CORE_ROUTING_MARK,
            CoreConstants.TPROXY_MARK_IPV6
        )
        assertEquals(666, CoreConstants.CORE_ROUTING_MARK)
    }

    @Test
    fun `test all root paths are rooted under ROOT_BASE_DIR`() {
        val rootPaths = listOf(
            CoreConstants.ROOT_BIN_DIR,
            CoreConstants.ROOT_BIN_PATH,
            CoreConstants.ROOT_RUN_DIR,
            CoreConstants.ROOT_PID_FILE,
            CoreConstants.ROOT_LOG_FILE,
            CoreConstants.ROOT_CONFIG_PATH,
            CoreConstants.ROOT_CORE_VERSION_FILE,
        )
        rootPaths.forEach { path ->
            assertTrue(
                "root path must live under ROOT_BASE_DIR (${CoreConstants.ROOT_BASE_DIR}): $path",
                path.startsWith(CoreConstants.ROOT_BASE_DIR)
            )
        }
        assertTrue(
            "binary path must live under the bin dir",
            CoreConstants.ROOT_BIN_PATH.startsWith(CoreConstants.ROOT_BIN_DIR)
        )
    }

    @Test
    fun `test TUN IPv4 scheme matches documented fake-ip overlap`() {
        // Documented scheme: TUN addr 198.18.0.1/30, DNS 198.18.0.2. The /30 intentionally
        // overlaps the fake-ip-range "198.18.0.1/16" from ConfigGenerator (CMFA parity),
        // so pin the values instead of asserting mutual exclusion.
        assertEquals("198.18.0.1", CoreConstants.TUN_IPV4_ADDR)
        assertEquals(30, CoreConstants.TUN_IPV4_PREFIX)
        assertEquals("198.18.0.2", CoreConstants.TUN_IPV4_DNS)

        // Both TUN addresses sit inside the fake-ip /16 network (198.18.0.0/16).
        assertTrue(
            "TUN addr must stay inside the 198.18.0.0/16 fake-ip range",
            CoreConstants.TUN_IPV4_ADDR.startsWith("198.18.")
        )
        assertTrue(
            "TUN DNS must stay inside the 198.18.0.0/16 fake-ip range",
            CoreConstants.TUN_IPV4_DNS.startsWith("198.18.")
        )

        // Within the /30 (198.18.0.0/30) the usable hosts are .1 and .2:
        // addr takes .1, DNS takes .2 — same subnet, distinct hosts.
        val addr = CoreConstants.TUN_IPV4_ADDR.split(".").map { it.toInt() }
        val dns = CoreConstants.TUN_IPV4_DNS.split(".").map { it.toInt() }
        assertEquals("TUN addr and DNS must share the /24 visible subnet", addr.dropLast(1), dns.dropLast(1))
        assertTrue("TUN addr host octet must be a /30 host (.1)", addr.last() in 1..2)
        assertTrue("TUN DNS host octet must be a /30 host (.2)", dns.last() in 1..2)
        assertNotEquals("TUN addr and DNS must be distinct hosts", addr, dns)
    }

    @Test
    fun `test TUN IPv6 scheme is internally consistent`() {
        assertEquals("fdfe:dcba:9876::1", CoreConstants.TUN_IPV6_ADDR)
        assertEquals(126, CoreConstants.TUN_IPV6_PREFIX)
        assertEquals("fdfe:dcba:9876::2", CoreConstants.TUN_IPV6_DNS)
        assertNotEquals(CoreConstants.TUN_IPV6_ADDR, CoreConstants.TUN_IPV6_DNS)
    }

    @Test
    fun `test generateRandomHexSecret 32 produces lowercase hex and unique values`() {
        val first = CoreConstants.generateRandomHexSecret(32)
        val second = CoreConstants.generateRandomHexSecret(32)
        assertEquals(32, first.length)
        assertEquals(32, second.length)
        assertTrue("must be lowercase hex: $first", first.matches(Regex("^[0-9a-f]{32}$")))
        assertTrue("must be lowercase hex: $second", second.matches(Regex("^[0-9a-f]{32}$")))
        assertNotEquals("secrets must differ between calls", first, second)
    }

    @Test
    fun `test generateRandomHexSecret honours custom and default lengths`() {
        assertEquals(32, CoreConstants.generateRandomHexSecret().length) // default = 32
        val short = CoreConstants.generateRandomHexSecret(10)
        assertEquals(10, short.length)
        assertTrue("must be lowercase hex: $short", short.matches(Regex("^[0-9a-f]{10}$")))
    }
}
