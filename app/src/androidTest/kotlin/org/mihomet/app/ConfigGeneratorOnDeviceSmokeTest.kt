package org.mihomet.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mihomet.app.core.config.ConfigGenerator
import org.mihomet.app.data.preferences.AppSettings
import org.yaml.snakeyaml.Yaml

/**
 * On-device smoke test of the profile-key whitelist (audit P1.3; mirrors the
 * JVM ConfigGeneratorWhitelistStrictTest). Proves the same sanitize path holds
 * on an Android runtime — where the strict mode, YAML parser and codecs differ
 * in subtle ways — before the config ever reaches the core process.
 */
@Suppress("UNCHECKED_CAST")
@RunWith(AndroidJUnit4::class)
class ConfigGeneratorOnDeviceSmokeTest {

    /** Hostile profile: every audited key family, plus a benign node. */
    private val hostileProfile = """
        listeners:
          - name: socks-in
            type: socks
            port: 1080
            listen: 0.0.0.0
          - name: http-in
            type: http
            port: 8080
            listen: 0.0.0.0
        tunnels:
          - "0.0.0.0:5353/udp"
        external-ui: /data/adb/mihomet/ui
        external-controller-cors: "*"
        geox-url:
          geoip: "https://evil.example/geoip.dat"
          geosite: "https://evil.example/geosite.dat"
        interface-name: wlan1
        secret: "attacker-known-secret"
        allow-lan: true
        bind-address: "0.0.0.0"
        authentication:
          - "user:pass"
        port: 7890
        mixed-port: 7892
        socks-port: 7893
        proxies:
          - name: "Evil-Node"
            type: ss
            server: 6.6.6.6
            port: 8388
            cipher: aes-256-gcm
            password: "password"
        rules:
          - MATCH,Evil-Node
    """.trimIndent()

    private fun generate(profileYaml: String): Map<String, Any> {
        val output = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = profileYaml,
            settings = AppSettings(controllerSecret = "app_secret"),
            tunFd = null
        )
        return Yaml().load<Map<String, Any>>(output)
    }

    @Test
    fun hostileProfileLosesListenersTunnelsAndControllerExposure() {
        val parsed = generate(hostileProfile)

        assertNull("listeners must never survive into the runtime config", parsed["listeners"])
        assertNull("tunnels must never survive into the runtime config", parsed["tunnels"])
        assertNull("external-ui must never survive into the runtime config", parsed["external-ui"])
        assertNull("geox-url must never survive into the runtime config", parsed["geox-url"])
        assertNull("interface-name must never survive into the runtime config", parsed["interface-name"])
        assertNull("authentication must never survive into the runtime config", parsed["authentication"])
        assertNull("legacy port key must not survive", parsed["port"])
        assertNull("mixed-port override must not survive", parsed["mixed-port"])
        assertNull("socks-port override must not survive", parsed["socks-port"])
    }

    @Test
    fun hostileProfileCannotWeakenControllerSecurity() {
        val parsed = generate(hostileProfile)

        assertEquals("127.0.0.1:9090", parsed["external-controller"])
        assertEquals("app_secret", parsed["secret"])
        assertEquals(false, parsed["allow-lan"])
        assertEquals("127.0.0.1", parsed["bind-address"])
    }

    @Test
    fun whitelistedCoreKeysSurvive() {
        val parsed = generate(hostileProfile)

        val proxies = parsed["proxies"] as List<Map<String, Any>>
        assertTrue("Whitelisted proxies must survive", proxies.any { it["name"] == "Evil-Node" })
        assertTrue(
            "Whitelisted rules must survive",
            (parsed["rules"] as List<*>).contains("MATCH,Evil-Node")
        )
    }
}
