package org.mihomet.app

import io.ktor.client.request.get
import org.junit.Assert.*
import org.junit.Test
import org.mihomet.app.core.config.ConfigGenerator
import org.mihomet.app.data.preferences.AppSettings
import org.yaml.snakeyaml.Yaml

class SubscriptionTest {


    @Test
    @Suppress("UNCHECKED_CAST")
    fun `subscription yaml converts and generates a hardened runtime config`() {
        // Offline fixture in the shape of a Clash YAML subscription (the real
        // panel payload shape); no live network in the unit suite.
        val yamlContent = """
            mixed-port: 7890
            allow-lan: false
            mode: rule
            proxies:
            - {name: NodeA, type: ss, server: a.example.com, port: 443, cipher: aes-128-gcm, password: pw}
            - {name: NodeB, type: ss, server: b.example.com, port: 443, cipher: aes-128-gcm, password: pw}
            proxy-groups:
            - {name: PanelMain, type: select, proxies: [NodeA, NodeB]}
            rules: [MATCH,PanelMain]
        """.trimIndent()

        val yaml = Yaml()
        val parsed = yaml.load<Map<String, Any>>(yamlContent)
        assertNotNull(parsed["proxies"])
        val proxies = parsed["proxies"] as List<Map<String, Any>>
        assertTrue("Expected proxy nodes", proxies.isNotEmpty())

        val settings = AppSettings(
            tproxyPort = 7893,
            mixedPort = 7890,
            dnsPort = 1053,
            controllerPort = 9090
        )
        val runtimeConfig = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = yamlContent,
            settings = settings,
            tunFd = null
        )
        val runtimeParsed = yaml.load<Map<String, Any>>(runtimeConfig)
        assertEquals(666, runtimeParsed["routing-mark"])
        assertEquals(7893, runtimeParsed["tproxy-port"])
        val runtimeProxies = runtimeParsed["proxies"] as List<Map<String, Any>>
        assertEquals(proxies.size, runtimeProxies.size)
    }

}
