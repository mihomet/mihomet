package org.mihomet.app

import org.junit.Assert.*
import org.junit.Test
import org.mihomet.app.core.config.ConfigGenerator
import org.mihomet.app.data.preferences.AppSettings
import org.mihomet.app.data.preferences.ProxyMode
import org.yaml.snakeyaml.Yaml

class ConfigGeneratorTest {

    private val sampleYaml = """
        port: 7890
        mode: rule
        proxies:
          - name: "TestNode"
            type: ss
            server: 1.2.3.4
            port: 8388
            cipher: aes-256-gcm
            password: "password"
        proxy-groups:
          - name: "PROXY"
            type: select
            proxies:
              - "TestNode"
        rules:
          - MATCH,PROXY
    """.trimIndent()

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test config generator injects routing-mark and tproxy port`() {
        val settings = AppSettings(
            tproxyPort = 7893,
            mixedPort = 7890,
            dnsPort = 1053,
            controllerPort = 9090,
            proxyMode = ProxyMode.RULE,
            controllerSecret = "test_secret"
        )

        val output = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = sampleYaml,
            settings = settings,
            tunFd = null
        )

        val yaml = Yaml()
        val parsed = yaml.load<Map<String, Any>>(output)

        assertEquals(666, parsed["routing-mark"])
        assertEquals(7893, parsed["tproxy-port"])
        assertEquals(7890, parsed["mixed-port"])
        assertEquals("127.0.0.1:9090", parsed["external-controller"])
        assertEquals("test_secret", parsed["secret"])

        val dns = parsed["dns"] as Map<String, Any>
        assertEquals(true, dns["enable"])
        assertEquals("127.0.0.1:1053", dns["listen"])

        val proxies = parsed["proxies"] as List<Map<String, Any>>
        assertEquals(1, proxies.size)
        assertTrue(proxies.any { it["name"] == "TestNode" })
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test config generator injects tun file-descriptor in vpn mode`() {
        val settings = AppSettings()
        val tunFd = 42

        val output = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = sampleYaml,
            settings = settings,
            tunFd = tunFd
        )

        val yaml = Yaml()
        val parsed = yaml.load<Map<String, Any>>(output)

        assertNotNull(parsed["tun"])
        val tun = parsed["tun"] as Map<String, Any>
        assertEquals(true, tun["enable"])
        assertEquals(42, tun["file-descriptor"])
        assertEquals("gvisor", tun["stack"])
        assertEquals(listOf("any:53", "tcp://any:53"), tun["dns-hijack"])
        assertEquals(false, tun["auto-detect-interface"])

        val dns = parsed["dns"] as Map<String, Any>
        assertEquals(listOf("77.88.8.8", "77.88.8.1", "system"), dns["direct-nameserver"])

        val proxies = parsed["proxies"] as List<Map<String, Any>>
        assertEquals(true, proxies[0]["udp"])

        val groups = parsed["proxy-groups"] as List<Map<String, Any>>
        assertEquals(true, groups[0]["udp"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test config generator preserves subscription proxy groups without injecting phantom groups`() {
        val subscriptionYaml = """
            proxies:
              - name: "Node-1"
                type: vmess
                server: 1.1.1.1
                port: 443
              - name: "Node-2"
                type: vmess
                server: 2.2.2.2
                port: 443
            proxy-groups:
              - name: "AUTO"
                type: url-test
                url: http://www.gstatic.com/generate_204
                interval: 300
                proxies:
                  - "Node-1"
                  - "Node-2"
              - name: "PROXY"
                type: select
                proxies:
                  - "AUTO"
                  - "Node-1"
                  - "Node-2"
            rules:
              - MATCH,PROXY
        """.trimIndent()

        val output = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = subscriptionYaml,
            settings = AppSettings(),
            tunFd = null
        )

        val yaml = Yaml()
        val parsed = yaml.load<Map<String, Any>>(output)
        val groups = parsed["proxy-groups"] as List<Map<String, Any>>
        assertEquals(2, groups.size)
        assertEquals("AUTO", groups[0]["name"])
        assertEquals("PROXY", groups[1]["name"])

        val proxyGroupProxies = groups[1]["proxies"] as List<String>
        assertEquals(listOf("AUTO", "Node-1", "Node-2"), proxyGroupProxies)
        assertFalse(proxyGroupProxies.contains("⚡ Автовыбор"))
    }
}
