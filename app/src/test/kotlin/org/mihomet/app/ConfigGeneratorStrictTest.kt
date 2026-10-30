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
        // Security: no unauthenticated local proxy listener in the runtime config
        assertNull("mixed-port must not be exposed on 127.0.0.1", parsed["mixed-port"])
        assertNull("legacy port must not be exposed", parsed["port"])
        assertNull("socks-port must not be exposed", parsed["socks-port"])
        assertEquals("127.0.0.1:9090", parsed["external-controller"])
        assertEquals("rule", parsed["mode"])
        assertEquals(false, parsed["allow-lan"])
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
        assertEquals("127.0.0.1:1053", dns["listen"])
        assertEquals("fake-ip", dns["enhanced-mode"])

        val proxyServerDns = dns["proxy-server-nameserver"] as List<String>
        assertEquals(
            "proxy-server-nameserver must be plain Russian resolvers plus system (iptables loop escape in root mode)",
            listOf("77.88.8.8", "77.88.8.1", "system"),
            proxyServerDns
        )
        assertFalse("Must not contain blocked 1.1.1.1", proxyServerDns.contains("1.1.1.1"))
        assertFalse("Must not contain 8.8.8.8", proxyServerDns.contains("8.8.8.8"))
        assertFalse("Must not contain encrypted upstreams", proxyServerDns.any { it.startsWith("https://") })

        val defaultDns = dns["default-nameserver"] as List<String>
        assertEquals(
            "default-nameserver must be exactly the Russian plain bootstrap",
            listOf("77.88.8.8", "77.88.8.1"),
            defaultDns
        )
        assertFalse("Must not contain blocked 1.1.1.1", defaultDns.contains("1.1.1.1"))

        val sniffer = parsed["sniffer"] as Map<String, Any>
        assertEquals(true, sniffer["enable"])
        assertNotNull(sniffer["sniff"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test default nameserver is plain russian resolver when custom dns is empty`() {
        val settings = AppSettings(customDns = "")
        val configYaml = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = sampleProfile,
            settings = settings,
            tunFd = null
        )

        val yaml = Yaml()
        val parsed = yaml.load<Map<String, Any>>(configYaml)

        val dns = parsed["dns"] as Map<String, Any>
        assertEquals(
            "Empty customDns must yield the plain Russian default only",
            listOf("77.88.8.8"),
            dns["nameserver"]
        )
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test global encrypted dns choice keeps fallback russian resolver second`() {
        val globalDoh = "https://dns.google/dns-query"
        val settings = AppSettings(customDns = globalDoh)
        val configYaml = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = sampleProfile,
            settings = settings,
            tunFd = null
        )

        val yaml = Yaml()
        val parsed = yaml.load<Map<String, Any>>(configYaml)

        val dns = parsed["dns"] as Map<String, Any>
        val nameserver = dns["nameserver"] as List<String>
        assertEquals(
            "GLOBAL DoH must be first with plain Russian fallback second",
            listOf(globalDoh, "77.88.8.8"),
            nameserver
        )
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test russian encrypted dns choice is used alone without global fallback`() {
        val ruDoh = "https://dns.comss.one/dns-query"
        val settings = AppSettings(customDns = ruDoh)
        val configYaml = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = sampleProfile,
            settings = settings,
            tunFd = null
        )

        val yaml = Yaml()
        val parsed = yaml.load<Map<String, Any>>(configYaml)

        val dns = parsed["dns"] as Map<String, Any>
        assertEquals(
            "ENCRYPTED_RU choice must be the only nameserver",
            listOf(ruDoh),
            dns["nameserver"]
        )
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test plain russian dns choice is used alone`() {
        val settings = AppSettings(customDns = "77.88.8.1")
        val configYaml = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = sampleProfile,
            settings = settings,
            tunFd = null
        )

        val yaml = Yaml()
        val parsed = yaml.load<Map<String, Any>>(configYaml)

        val dns = parsed["dns"] as Map<String, Any>
        assertEquals(
            "PLAINTEXT_RU choice must be the only nameserver",
            listOf("77.88.8.1"),
            dns["nameserver"]
        )
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test bootstrap and proxy-server-nameserver never contain global doh regardless of custom dns`() {
        val settings = AppSettings(customDns = "https://dns.quad9.net/dns-query")
        val configYaml = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = sampleProfile,
            settings = settings,
            tunFd = null
        )

        val yaml = Yaml()
        val parsed = yaml.load<Map<String, Any>>(configYaml)

        val dns = parsed["dns"] as Map<String, Any>
        assertEquals(
            "default-nameserver must always be the plain bootstrap",
            listOf("77.88.8.8", "77.88.8.1"),
            dns["default-nameserver"]
        )
        assertEquals(
            "proxy-server-nameserver must never contain GLOBAL/DoH entries",
            listOf("77.88.8.8", "77.88.8.1", "system"),
            dns["proxy-server-nameserver"]
        )
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
        // AdGuard is classified GLOBAL: plain Russian fallback must follow as second entry.
        assertEquals("GLOBAL preset must keep the plain Russian fallback second", "77.88.8.8", nameserver[1])
    }

    // ---------------------------------------------------------------------
    // Typed profile override layer (unified-delay / tcp-concurrent / log-level / sniffer)
    // ---------------------------------------------------------------------

    private val overriddenProfile = """
        unified-delay: false
        tcp-concurrent: false
        log-level: silent
        sniffer:
          enable: false
          sniff:
            TLS:
              ports: [443]
        proxies:
          - name: "US-Node-1"
            type: vmess
            server: 1.2.3.4
            port: 443
            uuid: "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d"
            alterId: 0
            cipher: auto
            udp: false
    """.trimIndent()

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test performance overrides set keys and overwrite profile values`() {
        // Profile ships unified-delay: false / tcp-concurrent: false; the override
        // layer must win and rewrite both to true.
        val settings = AppSettings(
            overrideUnifiedDelay = true,
            overrideTcpConcurrent = true
        )
        val configYaml = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = overriddenProfile,
            settings = settings,
            tunFd = null
        )

        val parsed = Yaml().load<Map<String, Any>>(configYaml)

        assertEquals(
            "unified-delay override must win over the profile's own false",
            true,
            parsed["unified-delay"]
        )
        assertEquals(
            "tcp-concurrent override must win over the profile's own false",
            true,
            parsed["tcp-concurrent"]
        )

        // Defaults must keep writing the same keys: unified-delay off, tcp-concurrent on.
        val defaultYaml = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = overriddenProfile,
            settings = AppSettings(),
            tunFd = null
        )
        val defaultParsed = Yaml().load<Map<String, Any>>(defaultYaml)
        assertEquals("Default unified-delay override is off", false, defaultParsed["unified-delay"])
        assertEquals("Default tcp-concurrent override is on", true, defaultParsed["tcp-concurrent"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test log level override replaces profile log level`() {
        // Profile says silent, the app-level choice must take priority.
        val settings = AppSettings(overrideLogLevel = "warning")
        val configYaml = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = overriddenProfile,
            settings = settings,
            tunFd = null
        )

        val parsed = Yaml().load<Map<String, Any>>(configYaml)
        assertEquals(
            "log-level override must replace the profile's own silent",
            "warning",
            parsed["log-level"]
        )

        // Invalid values must never leak into the runtime config: fall back to info.
        val fallbackYaml = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = overriddenProfile,
            settings = AppSettings(overrideLogLevel = "verbose"),
            tunFd = null
        )
        val fallbackParsed = Yaml().load<Map<String, Any>>(fallbackYaml)
        assertEquals("Invalid log-level override must fall back to info", "info", fallbackParsed["log-level"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test sniffer can be disabled by override and enabled by default`() {
        // Profile has sniffer.enable: false; the default override turns it back on.
        val enabledYaml = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = overriddenProfile,
            settings = AppSettings(),
            tunFd = null
        )
        val enabledParsed = Yaml().load<Map<String, Any>>(enabledYaml)
        val enabledSniffer = enabledParsed["sniffer"] as Map<String, Any>
        assertEquals("Sniffer override must win over the profile's own false", true, enabledSniffer["enable"])
        assertNotNull("Sniff block must stay configured", enabledSniffer["sniff"])

        // And the toggle off must disable it regardless of the profile.
        val disabledYaml = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = overriddenProfile,
            settings = AppSettings(overrideSnifferEnabled = false),
            tunFd = null
        )
        val disabledParsed = Yaml().load<Map<String, Any>>(disabledYaml)
        val disabledSniffer = disabledParsed["sniffer"] as Map<String, Any>
        assertEquals("Sniffer must be disabled by the override", false, disabledSniffer["enable"])
    }

    // ---------------------------------------------------------------------
    // Fail-closed profile parsing (P0): broken or blank YAML must NEVER degrade
    // into an empty runtime config (no proxies/rules) — the core would start and
    // route ALL traffic to DIRECT while the UI shows "connected". The generator
    // must throw IllegalStateException so the services refuse the core start
    // (or fall back to the last known-good runtime config).
    // ---------------------------------------------------------------------

    private fun assertFailsClosed(input: String) {
        try {
            ConfigGenerator.generateRuntimeConfig(
                profileYamlContent = input,
                settings = AppSettings(),
                tunFd = null
            )
            fail("Broken profile must be rejected, got a runtime config for: $input")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Message must name the broken YAML, was: ${e.message}",
                e.message?.contains("Профиль содержит некорректный YAML") == true
            )
        }
    }

    @Test
    fun `test broken profile yaml fails closed instead of producing empty config`() {
        assertFailsClosed("proxies: [unclosed")                     // unterminated flow sequence
        assertFailsClosed("proxies:\n\t- name: tab-indent-broken")  // tab indentation is illegal in YAML
        assertFailsClosed("just-a-scalar")                          // valid YAML but not a mapping
        assertFailsClosed("- a\n- b")                               // a sequence is not a config mapping
    }

    @Test
    fun `test blank profile yaml fails closed`() {
        assertFailsClosed("")
        assertFailsClosed("   \n\t ")
    }

    @Test
    fun `test valid profile still generates after fail-closed change`() {
        val configYaml = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = sampleProfile,
            settings = AppSettings(),
            tunFd = null
        )
        val parsed = Yaml().load<Map<String, Any>>(configYaml)
        assertTrue("Valid profile must keep its proxies", (parsed["proxies"] as List<*>).isNotEmpty())
    }

    // ---------------------------------------------------------------------
    // TUN settings (Advanced section): stack/mtu flow from AppSettings into the
    // tun block, with sanitization at the generator boundary.
    // ---------------------------------------------------------------------

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test tun stack and mtu are taken from settings`() {
        val settings = AppSettings(tunStack = "system", tunMtu = 1500)
        val configYaml = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = sampleProfile,
            settings = settings,
            tunFd = 37
        )

        val tun = Yaml().load<Map<String, Any>>(configYaml)["tun"] as Map<String, Any>
        assertEquals("Valid stack choice must be written as-is", "system", tun["stack"])
        assertEquals("Valid MTU choice must be written as-is", 1500, tun["mtu"])
        assertEquals(37, tun["file-descriptor"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test garbage tun stack falls back to gvisor`() {
        val settings = AppSettings(tunStack = "bogus-stack")
        val configYaml = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = sampleProfile,
            settings = settings,
            tunFd = 37
        )

        val tun = Yaml().load<Map<String, Any>>(configYaml)["tun"] as Map<String, Any>
        assertEquals("Unknown stack must degrade to gvisor", "gvisor", tun["stack"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test tun mtu out of range is clamped not replaced`() {
        // Below 1280 (IPv6 minimum link MTU) → clamped up, NOT swapped for the default
        val lowYaml = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = sampleProfile,
            settings = AppSettings(tunMtu = 100),
            tunFd = 37
        )
        val lowTun = Yaml().load<Map<String, Any>>(lowYaml)["tun"] as Map<String, Any>
        assertEquals("Too-small MTU must clamp to 1280", 1280, lowTun["mtu"])

        // Above the TUN device maximum → clamped down
        val highYaml = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = sampleProfile,
            settings = AppSettings(tunMtu = 999_999),
            tunFd = 37
        )
        val highTun = Yaml().load<Map<String, Any>>(highYaml)["tun"] as Map<String, Any>
        assertEquals("Too-large MTU must clamp to 65535", 65535, highTun["mtu"])

        // Defaults unchanged: 9000 (CMFA standard)
        val defaultTun = Yaml().load<Map<String, Any>>(
            ConfigGenerator.generateRuntimeConfig(
                profileYamlContent = sampleProfile,
                settings = AppSettings(),
                tunFd = 37
            )
        )["tun"] as Map<String, Any>
        assertEquals("Default MTU must stay 9000", 9000, defaultTun["mtu"])
        assertEquals("Default stack must stay gvisor", "gvisor", defaultTun["stack"])
    }

    @Test
    fun `test tun settings are ignored in root mode`() {
        val configYaml = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = sampleProfile,
            settings = AppSettings(tunStack = "system", tunMtu = 1500),
            tunFd = null
        )
        val parsed = Yaml().load<Map<String, Any>>(configYaml)
        assertNull("Root mode must never contain a tun block", parsed["tun"])
    }

    @Test
    fun `test TunOptions sanitization contract`() {
        org.junit.Assert.assertEquals(
            "Only gvisor/system/mixed are valid",
            listOf("gvisor", "system", "mixed"),
            org.mihomet.app.data.preferences.TunOptions.STACKS
        )
        assertEquals(
            "Null stack falls back to gvisor",
            "gvisor",
            org.mihomet.app.data.preferences.TunOptions.sanitizeStack(null)
        )
        assertEquals(
            "Null MTU falls back to 9000",
            9000,
            org.mihomet.app.data.preferences.TunOptions.sanitizeMtu(null)
        )
        // Boundary values pass through untouched
        assertEquals(1280, org.mihomet.app.data.preferences.TunOptions.sanitizeMtu(1280))
        assertEquals(65535, org.mihomet.app.data.preferences.TunOptions.sanitizeMtu(65535))
    }
}
