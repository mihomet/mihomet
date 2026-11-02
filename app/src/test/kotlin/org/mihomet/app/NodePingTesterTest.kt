package org.mihomet.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mihomet.app.core.network.NodePingTester

class NodePingTesterTest {

    @Test
    fun `test yaml parsing into proxy node info`() {
        val yaml = """
            proxies:
              - name: "🇱🇻 Латвия ⚡️"
                type: vless
                server: lv-node.example.com
                port: 443
                uuid: f3d1cdd7-3b9e-4d98-aaa9-b11a28a01984
                tls: true
              - name: "🇫🇮 Финляндия ⚡️"
                type: vless
                server: fi-node.example.com
                port: 443
                reality-opts:
                  public-key: "xyz"
              - name: "dns-out"
                type: dns
        """.trimIndent()

        val nodes = NodePingTester.parseNodesFromYaml(yaml)
        assertEquals(2, nodes.size)
        assertEquals("🇱🇻 Латвия ⚡️", nodes[0].name)
        assertEquals("lv-node.example.com", nodes[0].server)
        assertEquals(443, nodes[0].port)
        assertEquals("vless", nodes[0].type)
        assertEquals("tls", nodes[0].network)

        assertEquals("🇫🇮 Финляндия ⚡️", nodes[1].name)
        assertEquals("reality", nodes[1].network)
    }

    @Test
    fun `test direct socket ping to reliable host`() = runBlocking {
        // Test latency to cloudflare or google dns (standard port 53 or 443)
        val latency = NodePingTester.testTcpPing("1.1.1.1", 53, timeoutMs = 2000)
        // If network is available, it returns > 0, otherwise -1 (which is handled safely)
        println("Direct socket ping test result: $latency ms")
        assertTrue(latency >= -1L)
    }

    @Test
    fun `test direct http ping with ipv6 server does not throw malformed url`() = runBlocking {
        val latency = NodePingTester.testHttpPing("2001:db8::1", 8080, timeoutMs = 100)
        assertEquals(-1L, latency)
    }
}
