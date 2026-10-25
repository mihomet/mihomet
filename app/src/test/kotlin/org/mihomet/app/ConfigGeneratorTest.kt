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
        assertEquals("0.0.0.0:1053", dns["listen"])

        val proxies = parsed["proxies"] as List<Map<String, Any>>
        assertEquals(1, proxies.size)
        assertEquals("TestNode", proxies[0]["name"])
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
    }
}
