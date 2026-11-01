package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.core.network.NodePingTester
import org.mihomet.app.core.network.NodePingTester.IcmpResult

/**
 * Pure-logic tests for the `/system/bin/ping` output parser (audit P2.8).
 * The ICMP probe must never misreport a device capability gap ("unavailable")
 * as a node timeout, and the RTT summary line of toybox/iputils/busybox
 * formats must yield min/avg/max.
 */
class NodePingTesterIcmpParseTest {

    // --- success paths -------------------------------------------------------

    @Test
    fun `toybox rtt line with mdev parses min avg max`() {
        val output = """
            PING 1.1.1.1 (1.1.1.1) 56(84) bytes of data.
            64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=12.3 ms

            --- 1.1.1.1 ping statistics ---
            1 packets transmitted, 1 received, 0% packet loss, time 0ms
            rtt min/avg/max/mdev = 12.100/12.300/12.500/0.200 ms
        """.trimIndent()
        val result = NodePingTester.parseIcmpPingOutput(exitCode = 0, output = output)
        assertEquals(IcmpResult.Success(latencyMs = 12, minMs = 12, maxMs = 12), result)
    }

    @Test
    fun `busybox round-trip line parses avg`() {
        val output = """
            PING example.com (93.184.216.34): 56 data bytes
            64 bytes from 93.184.216.34: seq=0 ttl=51 time=45.678 ms

            --- example.com ping statistics ---
            1 packets transmitted, 1 packets received, 0% packet loss
            round-trip min/avg/max = 45.678/45.678/45.678 ms
        """.trimIndent()
        val result = NodePingTester.parseIcmpPingOutput(exitCode = 0, output = output)
        assertEquals(IcmpResult.Success(latencyMs = 45, minMs = 45, maxMs = 45), result)
    }

    @Test
    fun `sub-millisecond rtt coerces to at least 1 ms`() {
        val output = """
            1 packets transmitted, 1 received, 0% packet loss, time 0ms
            rtt min/avg/max/mdev = 0.039/0.039/0.039/0.000 ms
        """.trimIndent()
        val result = NodePingTester.parseIcmpPingOutput(exitCode = 0, output = output)
        assertEquals(IcmpResult.Success(latencyMs = 1, minMs = 0, maxMs = 0), result)
    }

    @Test
    fun `received without stats line still counts as success`() {
        val output = "1 packets transmitted, 1 received, 0% packet loss"
        val result = NodePingTester.parseIcmpPingOutput(exitCode = 0, output = output)
        assertEquals(IcmpResult.Success(latencyMs = 1, minMs = 1, maxMs = 1), result)
    }

    // --- node-level failures (NOT device capability gaps) --------------------

    @Test
    fun `zero received is unreachable`() {
        val output = """
            PING 10.255.255.1 (10.255.255.1) 56(84) bytes of data.

            --- 10.255.255.1 ping statistics ---
            1 packets transmitted, 0 received, 100% packet loss, time 0ms
        """.trimIndent()
        val result = NodePingTester.parseIcmpPingOutput(exitCode = 1, output = output)
        assertEquals(IcmpResult.Unreachable, result)
    }

    @Test
    fun `busybox zero received without the word packets is unreachable`() {
        val output = "1 packets transmitted, 0 received, 0% packet loss"
        val result = NodePingTester.parseIcmpPingOutput(exitCode = 1, output = output)
        assertEquals(IcmpResult.Unreachable, result)
    }

    @Test
    fun `unknown host is unreachable`() {
        val result = NodePingTester.parseIcmpPingOutput(
            exitCode = 2,
            output = "ping: unknown host nope.invalid"
        )
        assertEquals(IcmpResult.Unreachable, result)
    }

    @Test
    fun `getaddrinfo failure is unreachable`() {
        val result = NodePingTester.parseIcmpPingOutput(
            exitCode = 2,
            output = "ping: no. Such host is known: Name or service not known"
        )
        assertEquals(IcmpResult.Unreachable, result)
    }

    @Test
    fun `empty or garbage output fails closed to unreachable`() {
        assertEquals(IcmpResult.Unreachable, NodePingTester.parseIcmpPingOutput(0, ""))
        assertEquals(
            IcmpResult.Unreachable,
            NodePingTester.parseIcmpPingOutput(1, "some unrelated crash output")
        )
    }

    // --- device capability gaps ----------------------------------------------

    @Test
    fun `operation not permitted is unavailable not a timeout`() {
        val result = NodePingTester.parseIcmpPingOutput(
            exitCode = 1,
            output = "PING 8.8.8.8 (8.8.8.8): 56 data bytes\nping: permission denied (are you root?)"
        )
        assertEquals(IcmpResult.Unavailable, result)
    }

    @Test
    fun `unavailable sentinel is distinct from ordinary failure`() {
        assertNotEquals(NodePingTester.ICMP_UNAVAILABLE, -1L)
        assertEquals(-2L, NodePingTester.ICMP_UNAVAILABLE)
    }

    // --- feature detection -----------------------------------------------------

    @Test
    fun `icmp binary detection is safe on hosts without the platform paths`() {
        val path = NodePingTester.icmpBinaryPath()
        // On a Linux dev host /system/bin/ping does not exist; on-device it must.
        assertTrue(path == null || path.startsWith("/system/"))
    }
}
