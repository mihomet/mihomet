package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.core.config.ConfigGenerator
import org.mihomet.app.data.preferences.AppSettings
import org.yaml.snakeyaml.Yaml

/**
 * External audit F13 — consistency of the root-mode last-good fallback.
 *
 * RootTProxyService, when the selected profile is broken, starts the daemon from
 * `config.lastgood.yaml` — but the firewall rules are built from the CURRENT
 * settings. The service therefore re-runs the last-good YAML through
 * [ConfigGenerator.generateRuntimeConfig] (tunFd = null → root mode) before
 * writing it, so every app-managed mandatory key is rebuilt on top of it from
 * the current settings and the core listeners can never disagree with the
 * netfilter rules on ports.
 *
 * These tests pin that contract exactly as the service uses it: a config that
 * was generated under OLD settings (the last-good file) must, when regenerated
 * under NEW settings, carry the NEW app-managed keys while keeping the last
 * good routing content (proxies / groups / rules).
 */
class RootLastGoodFallbackTest {

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
        proxy-groups:
          - name: "AUTO-SELECT"
            type: url-test
            proxies: ["US-Node-1"]
            url: "http://www.gstatic.com/generate_204"
            interval: 300
    """.trimIndent()

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `last-good config regenerated under new settings uses current tproxy and dns ports`() {
        // Session N: config started fine and was persisted as config.lastgood.yaml.
        val oldSettings = AppSettings(
            tproxyPort = 7893,
            dnsPort = 1053,
            controllerPort = 9090,
            controllerSecret = "old-secret-hex"
        )
        val lastGoodYaml = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = sampleProfile,
            settings = oldSettings,
            tunFd = null
        )
        // Sanity: the stale values really are inside the last-good file, i.e. the
        // test fails if the fallback ever skips the regeneration step.
        val stale = Yaml().load<Map<String, Any>>(lastGoodYaml)
        assertEquals(7893, stale["tproxy-port"])
        assertEquals("127.0.0.1:1053", (stale["dns"] as Map<String, Any>)["listen"])

        // Session N+1: the user changed the ports, then the profile broke — the
        // service falls back to the last-good file and regenerates it with the
        // CURRENT settings (exactly the RootTProxyService fallback path).
        val newSettings = AppSettings(
            tproxyPort = 7999,
            dnsPort = 2053,
            controllerPort = 9099,
            controllerSecret = "new-secret-hex"
        )
        val fallbackYaml = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = lastGoodYaml,
            settings = newSettings,
            tunFd = null
        )

        val parsed = Yaml().load<Map<String, Any>>(fallbackYaml)
        // Ports the firewall is built from → the core MUST listen on the same ones.
        assertEquals(
            "tproxy-port must match the CURRENT settings the firewall rules are built from",
            7999,
            parsed["tproxy-port"]
        )
        assertEquals(
            "dns.listen must match the CURRENT dns port (redirect target)",
            "127.0.0.1:2053",
            (parsed["dns"] as Map<String, Any>)["listen"]
        )
        // Controller + secret come from the current settings too (apiClientProvider parity).
        assertEquals("127.0.0.1:9099", parsed["external-controller"])
        assertEquals("new-secret-hex", parsed["secret"])
        // Root-mode mandatory keys stay aligned.
        assertEquals(7892, parsed["redir-port"])
        assertEquals(666, parsed["routing-mark"])
        assertEquals(false, parsed["allow-lan"])
        assertEquals("127.0.0.1", parsed["bind-address"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `regenerated last-good keeps the routing content of the previous session`() {
        val oldSettings = AppSettings(tproxyPort = 7893, dnsPort = 1053)
        val lastGoodYaml = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = sampleProfile,
            settings = oldSettings,
            tunFd = null
        )

        val fallbackYaml = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = lastGoodYaml,
            settings = AppSettings(tproxyPort = 7999, dnsPort = 2053),
            tunFd = null
        )

        val parsed = Yaml().load<Map<String, Any>>(fallbackYaml)
        val proxies = parsed["proxies"] as List<Map<String, Any>>
        assertTrue("Last-good proxies must survive the regeneration", proxies.isNotEmpty())
        assertEquals("US-Node-1", proxies[0]["name"])
        assertTrue("UDP must stay forced on the regenerated proxies", proxies[0]["udp"] == true)

        val groups = parsed["proxy-groups"] as List<Map<String, Any>>
        assertTrue("Last-good proxy-groups must survive the regeneration", groups.isNotEmpty())
        assertEquals("AUTO-SELECT", groups[0]["name"])

        val rules = parsed["rules"] as List<String>
        assertTrue("MATCH rule must survive the regeneration", rules.any { it.startsWith("MATCH,") })
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `regenerated last-good never resurrects a local listener or weak security keys`() {
        // Defensive: even if the last-good file was tampered with (or written by an
        // older app version) and carries a local listener / permissive keys, the
        // root-mode regeneration must sanitize them exactly like the normal start
        // path does. (A profile-owned `tun:` block is preserved by the generator in
        // root mode — pre-existing parity for both paths, out of F13 scope.)
        val tamperedLastGood = """
            mixed-port: 7890
            port: 8080
            socks-port: 7891
            allow-lan: true
            bind-address: 0.0.0.0
            external-controller: 0.0.0.0:9999
            secret: tampered
        """.trimIndent() + "\n" + sampleProfile

        val fallbackYaml = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = tamperedLastGood,
            settings = AppSettings(tproxyPort = 7999, dnsPort = 2053, controllerPort = 9099),
            tunFd = null
        )
        val parsed = Yaml().load<Map<String, Any>>(fallbackYaml)
        assertNull("Root mode must strip mixed-port from the fallback config", parsed["mixed-port"])
        assertNull("Root mode must strip port from the fallback config", parsed["port"])
        assertNull("Root mode must strip socks-port from the fallback config", parsed["socks-port"])
        assertEquals("allow-lan must be forced back to false", false, parsed["allow-lan"])
        assertEquals("bind-address must be forced back to loopback", "127.0.0.1", parsed["bind-address"])
        assertEquals("external-controller must be rebuilt from settings", "127.0.0.1:9099", parsed["external-controller"])
        org.junit.Assert.assertNotEquals("secret must be rebuilt from settings", "tampered", parsed["secret"])
        assertEquals("tproxy-port must be rebuilt from settings", 7999, parsed["tproxy-port"])
        assertTrue("Routing content must survive the regeneration", (parsed["proxies"] as List<*>).isNotEmpty())
    }
}
