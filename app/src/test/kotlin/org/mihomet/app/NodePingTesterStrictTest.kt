package org.mihomet.app

import org.junit.Assert.*
import org.junit.Test
import org.mihomet.app.core.network.NodePingTester
import org.mihomet.app.core.network.PingProtocol

class NodePingTesterStrictTest {

    private val mixedNodesYaml = """
        proxies:
          - name: "Node-VLESS"
            type: vless
            server: "1.1.1.1"
            port: 443
          - name: "Node-Hysteria"
            type: hysteria2
            server: "hysteria.example.com"
            port: 8443
          - name: "Node-SS"
            type: ss
            server: "104.26.10.1"
            port: 8388
          - name: "dns-out"
            type: dns
            server: "127.0.0.1"
            port: 53
          - name: "COMPATIBLE"
            type: compatible
            server: "127.0.0.1"
            port: 0
          - name: "Broken-Node-No-Port"
            type: vmess
            server: "1.2.3.4"
          - name: "Broken-Node-No-Server"
            type: vmess
            port: 443
    """.trimIndent()

    @Test
    fun `test parseNodesFromYaml extracts valid proxies and filters internal or malformed`() {
        val nodes = NodePingTester.parseNodesFromYaml(mixedNodesYaml)

        assertEquals("Should parse exactly 3 valid nodes", 3, nodes.size)

        val names = nodes.map { it.name }
        assertTrue(names.contains("Node-VLESS"))
        assertTrue(names.contains("Node-Hysteria"))
        assertTrue(names.contains("Node-SS"))

        assertFalse("dns-out must be excluded", names.contains("dns-out"))
        assertFalse("COMPATIBLE must be excluded", names.contains("COMPATIBLE"))
        assertFalse("Broken-Node-No-Port must be excluded", names.contains("Broken-Node-No-Port"))
        assertFalse("Broken-Node-No-Server must be excluded", names.contains("Broken-Node-No-Server"))

        val vless = nodes.first { it.name == "Node-VLESS" }
        assertEquals("vless", vless.type)
        assertEquals("1.1.1.1", vless.server)
        assertEquals(443, vless.port)
    }

    @Test
    fun `test parseNodesFromYaml handles malformed or empty inputs gracefully`() {
        assertTrue("Empty string returns empty list", NodePingTester.parseNodesFromYaml("").isEmpty())
        assertTrue("Invalid YAML returns empty list", NodePingTester.parseNodesFromYaml(":::invalid:::yaml:::").isEmpty())
        assertTrue("No proxies section returns empty list", NodePingTester.parseNodesFromYaml("mode: rule").isEmpty())
    }

    @Test
    fun `test ping protocol enums have valid display and short names`() {
        for (protocol in PingProtocol.entries) {
            assertTrue("Display name must not be blank", protocol.displayName.isNotBlank())
            assertTrue("Short name must not be blank", protocol.shortName.isNotBlank())
        }

        assertEquals("TCP", PingProtocol.TCP.shortName)
        assertEquals("HTTP", PingProtocol.HTTP_GET.shortName)
        assertEquals("HEAD", PingProtocol.HTTP_HEAD.shortName)
        assertEquals("ICMP", PingProtocol.ICMP.shortName)
    }
}
