package org.mihomet.app

import org.junit.Assert.*
import org.junit.Test
import org.mihomet.app.core.config.ConfigGenerator
import org.mihomet.app.data.preferences.AppSettings
import org.mihomet.app.data.preferences.ProxyMode
import org.yaml.snakeyaml.Yaml

class ConfigGeneratorStrictTest {

    private val sampleProfile = """
        proxies:
          - name: "US-Node-1"
            type: vmess
            server: 1.2.3.4
            port: 443
            uuid: "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d"
            alterId: 0
            cipher: auto
            udp: false
          - name: "EU-Node-2"
            type: vless
            server: 5.6.7.8
            port: 443
            uuid: "b2c3d4e5-f6a7-8b9c-0d1e-2f3a4b5c6d7e"
        proxy-groups:
          - name: "AUTO-SELECT"
            type: url-test
            proxies: ["US-Node-1", "EU-Node-2"]
            url: "http://www.gstatic.com/generate_204"
            interval: 300
    """.trimIndent()

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test root mode generates tproxy port and routing mark`() {
        val settings = AppSettings(
            tproxyPort = 7893,
            mixedPort = 7890,
            dnsPort = 1053,
            controllerPort = 9090,
            proxyMode = ProxyMode.RULE
        )

        val configYaml = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = sampleProfile,
            settings = settings,
            tunFd = null
        )

        val yaml = Yaml()
        val parsed = yaml.load<Map<String, Any>>(configYaml)

        // Strict assertions on Root Mode
        assertEquals("TProxy port must be 7893", 7893, parsed["tproxy-port"])
        assertEquals("Redir port must be 7892", 7892, parsed["redir-port"])
        assertEquals("Routing mark must be 666", 666, parsed["routing-mark"])
        assertNull("TUN must not be present in root mode", parsed["tun"])

        // Common settings
        assertEquals("Mixed port must be 7890", 7890, parsed["mixed-port"])
        assertEquals("127.0.0.1:9090", parsed["external-controller"])
        assertEquals("rule", parsed["mode"])
        assertEquals(true, parsed["allow-lan"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test vpn mode generates tun configuration and strips root marks`() {
        val settings = AppSettings(
            tproxyPort = 7893,
            mixedPort = 7890,
            dnsPort = 1053,
            controllerPort = 9090,
            proxyMode = ProxyMode.GLOBAL
        )

        val configYaml = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = sampleProfile,
            settings = settings,
            tunFd = 37
        )

        val yaml = Yaml()
        val parsed = yaml.load<Map<String, Any>>(configYaml)

        // Strict assertions on Non-Root VPN Mode
        assertNull("TProxy port must be stripped in VPN mode", parsed["tproxy-port"])
        assertNull("Redir port must be stripped in VPN mode", parsed["redir-port"])
        assertNull("Routing mark must be stripped in VPN mode", parsed["routing-mark"])

        val tun = parsed["tun"] as Map<String, Any>
        assertNotNull("TUN block must exist", tun)
        assertEquals(true, tun["enable"])
        assertEquals(37, tun["file-descriptor"])
        assertEquals("gvisor", tun["stack"])
        assertEquals(false, tun["auto-route"])
        assertEquals(false, tun["auto-detect-interface"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test udp is strictly forced on all proxies and proxy groups`() {
        val settings = AppSettings()
        val configYaml = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = sampleProfile,
            settings = settings,
            tunFd = null
        )

        val yaml = Yaml()
        val parsed = yaml.load<Map<String, Any>>(configYaml)

        val proxies = parsed["proxies"] as List<Map<String, Any>>
        assertTrue("Expected proxies", proxies.isNotEmpty())
        for (proxy in proxies) {
            assertEquals("Proxy ${proxy["name"]} must have UDP forced", true, proxy["udp"])
        }

        val groups = parsed["proxy-groups"] as List<Map<String, Any>>
        assertTrue("Expected groups", groups.isNotEmpty())
        for (group in groups) {
            assertEquals("Group ${group["name"]} must have UDP forced", true, group["udp"])
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test dns and sniffer are strictly configured`() {
        val settings = AppSettings(dnsPort = 1053)
        val configYaml = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = sampleProfile,
            settings = settings,
            tunFd = null
        )

        val yaml = Yaml()
        val parsed = yaml.load<Map<String, Any>>(configYaml)

        val dns = parsed["dns"] as Map<String, Any>
        assertEquals(true, dns["enable"])
        assertEquals("0.0.0.0:1053", dns["listen"])
        assertEquals("fake-ip", dns["enhanced-mode"])

        val proxyServerDns = dns["proxy-server-nameserver"] as List<String>
        assertTrue("Must contain 77.88.8.8", proxyServerDns.contains("77.88.8.8"))
        assertTrue("Must contain system", proxyServerDns.contains("system"))
        assertFalse("Must not contain blocked 1.1.1.1", proxyServerDns.contains("1.1.1.1"))

        val defaultDns = dns["default-nameserver"] as List<String>
        assertTrue("Must contain 77.88.8.8", defaultDns.contains("77.88.8.8"))
        assertFalse("Must not contain blocked 1.1.1.1", defaultDns.contains("1.1.1.1"))

        val sniffer = parsed["sniffer"] as Map<String, Any>
        assertEquals(true, sniffer["enable"])
        assertNotNull(sniffer["sniff"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test custom dns is prepended at top priority in nameserver`() {
        val customDnsUrl = "https://dns.adguard-dns.com/dns-query"
        val settings = AppSettings(customDns = customDnsUrl)
        val configYaml = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = sampleProfile,
            settings = settings,
            tunFd = null
        )

        val yaml = Yaml()
        val parsed = yaml.load<Map<String, Any>>(configYaml)

        val dns = parsed["dns"] as Map<String, Any>
        val nameserver = dns["nameserver"] as List<String>
        assertTrue("Nameserver must not be empty", nameserver.isNotEmpty())
        assertEquals("Custom DNS must be the first (highest priority) nameserver", customDnsUrl, nameserver[0])
    }
}
