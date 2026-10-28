package org.mihomet.app

import org.junit.Assert.*
import org.junit.Test
import org.mihomet.app.core.config.ConfigGenerator
import org.mihomet.app.data.preferences.AppSettings
import org.yaml.snakeyaml.Yaml

/**
 * P7: per-profile override patch — a small user-authored YAML fragment deep-merged
 * into the runtime config BEFORE the mandatory app-managed keys are written, so
 * security/ports/DNS settings from the app always keep priority.
 */
@Suppress("UNCHECKED_CAST")
class ConfigGeneratorOverridePatchTest {

    private val settings = AppSettings(mixedPort = 7890, controllerSecret = "test_secret")

    private val sampleProfile = """
        keep-alive-interval: 30
        global-client-fingerprint: chrome
        hosts:
          "a.example.com": "1.2.3.4"
        profile:
          store-selected: true
        tunnels:
          - "1.1.1.1:53/tcp"
        proxies:
          - name: "TestNode"
            type: ss
            server: 1.2.3.4
            port: 8388
            cipher: aes-256-gcm
            password: "password"
        rules:
          - MATCH,PROXY
    """.trimIndent()

    private fun generate(profileYaml: String, patch: String): Map<String, Any> {
        val output = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = profileYaml,
            settings = settings,
            tunFd = null,
            overridePatch = patch
        )
        return Yaml().load<Map<String, Any>>(output)
    }

    @Test
    fun `patch deep merges nested maps, replaces scalars and adds new keys`() {
        val patch = """
            keep-alive-interval: 15
            geodata-loader: memconservative
            hosts:
              "b.example.com": "5.6.7.8"
            profile:
              store-fake-ip: true
        """.trimIndent()

        val parsed = generate(sampleProfile, patch)

        // Scalar from the patch replaces the profile value
        assertEquals(15, parsed["keep-alive-interval"])
        // New root key from the patch is added
        assertEquals("memconservative", parsed["geodata-loader"])
        // Nested Map + Map merge: keys from BOTH sides survive (not a replacement)
        val hosts = parsed["hosts"] as Map<String, Any>
        assertEquals("1.2.3.4", hosts["a.example.com"])
        assertEquals("5.6.7.8", hosts["b.example.com"])
        val profile = parsed["profile"] as Map<String, Any>
        assertEquals(true, profile["store-selected"])
        assertEquals(true, profile["store-fake-ip"])
    }

    @Test
    fun `patch replaces lists wholesale instead of concatenating`() {
        val patch = """
            tunnels:
              - "8.8.8.8:53/udp"
              - "9.9.9.9:53/tcp"
        """.trimIndent()

        val parsed = generate(sampleProfile, patch)

        val tunnels = parsed["tunnels"] as List<String>
        assertEquals(listOf("8.8.8.8:53/udp", "9.9.9.9:53/tcp"), tunnels)
        assertFalse(tunnels.contains("1.1.1.1:53/tcp"))
    }

    @Test
    fun `patch cannot enable allow-lan or override security and port settings`() {
        val patch = """
            allow-lan: true
            bind-address: "0.0.0.0"
            mixed-port: 99999
            external-controller: "0.0.0.0:11111"
            dns:
              nameserver:
                - "1.1.1.1"
        """.trimIndent()

        val parsed = generate(sampleProfile, patch)

        // Mandatory app-managed keys are written AFTER the merge → they always win
        assertEquals(false, parsed["allow-lan"])
        assertEquals("127.0.0.1", parsed["bind-address"])
        assertEquals(settings.mixedPort, parsed["mixed-port"])
        assertEquals("127.0.0.1:${settings.controllerPort}", parsed["external-controller"])
        val dns = parsed["dns"] as Map<String, Any>
        assertFalse((dns["nameserver"] as List<*>).contains("1.1.1.1"))
    }

    @Test
    fun `invalid patch yaml is ignored and config still generates`() {
        val withoutPatch = generate(sampleProfile, "")
        val withBrokenPatch = generate(sampleProfile, "keep-alive-interval: [unclosed")

        assertEquals(withoutPatch, withBrokenPatch)
    }

    @Test
    fun `empty patch leaves the profile config untouched`() {
        val parsed = generate(sampleProfile, "")

        assertEquals(30, parsed["keep-alive-interval"])
        assertEquals("chrome", parsed["global-client-fingerprint"])
        assertEquals(listOf("1.1.1.1:53/tcp"), parsed["tunnels"])
    }
}
