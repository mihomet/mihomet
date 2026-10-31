package org.mihomet.app

import org.junit.Assert.*
import org.junit.Test
import org.mihomet.app.core.config.ConfigGenerator
import org.mihomet.app.core.config.WarningLocalizer
import org.mihomet.app.data.preferences.AppSettings
import org.yaml.snakeyaml.Yaml

/**
 * Strict tests for the profile-key whitelist (audit P1 §2.1).
 *
 * Threat: a malicious or careless panel profile returns top-level keys the app
 * never sanctioned — `listeners` re-opens an unauthenticated local proxy on
 * 0.0.0.0, `tunnels` forwards device ports, `external-ui`/`external-controller`
 * expose the core, `geox-url` points geo-DB downloads at attacker URLs,
 * `interface-name` binds the core to a chosen NIC, `secret`/`allow-lan`/
 * `bind-address`/`authentication` weaken the controller or proxy surface.
 *
 * The old implementation only removed mixed-port/port/socks-port (deny-list);
 * the whitelist must drop EVERYTHING else with a user-visible warning per key.
 */
@Suppress("UNCHECKED_CAST")
class ConfigGeneratorWhitelistStrictTest {

    /** Hostile profile: every audited key family present, plus a benign core. */
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
        geodata-mode: true
        geodata-loader: standard
        interface-name: wlan1
        secret: "attacker-known-secret"
        allow-lan: true
        bind-address: "0.0.0.0"
        authentication:
          - "user:pass"
        skip-auth-prefixes:
          - 0.0.0.0/0
        lan-allowed-ips:
          - 0.0.0.0/0
        port: 7890
        ports:
          - 7891
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

    private fun generate(profileYaml: String, tunFd: Int? = null): Map<String, Any> {
        val output = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = profileYaml,
            settings = AppSettings(controllerSecret = "app_secret"),
            tunFd = tunFd
        )
        return Yaml().load<Map<String, Any>>(output)
    }

    @Test
    fun `hostile profile loses listeners tunnels external-ui and geo overrides`() {
        val parsed = generate(hostileProfile)

        assertNull("listeners must never survive into the runtime config", parsed["listeners"])
        assertNull("tunnels must never survive into the runtime config", parsed["tunnels"])
        assertNull("external-ui must never survive into the runtime config", parsed["external-ui"])
        assertNull("geox-url must never survive into the runtime config", parsed["geox-url"])
        assertNull("geodata-mode must never survive into the runtime config", parsed["geodata-mode"])
        assertNull("geodata-loader must never survive into the runtime config", parsed["geodata-loader"])
        assertNull("interface-name must never survive into the runtime config", parsed["interface-name"])
        assertNull("authentication must never survive into the runtime config", parsed["authentication"])
        assertNull("skip-auth-prefixes must never survive into the runtime config", parsed["skip-auth-prefixes"])
        assertNull("lan-allowed-ips must never survive into the runtime config", parsed["lan-allowed-ips"])
        assertNull("legacy port key must not survive", parsed["port"])
        assertNull("ports key must not survive", parsed["ports"])
    }

    @Test
    fun `hostile profile cannot weaken controller security`() {
        val parsed = generate(hostileProfile)

        // App-managed keys are written AFTER the profile (and patch) — they always win
        assertEquals("127.0.0.1:9090", parsed["external-controller"])
        assertEquals("app_secret", parsed["secret"])
        assertEquals(false, parsed["allow-lan"])
        assertEquals("127.0.0.1", parsed["bind-address"])
    }

    @Test
    fun `whitelisted core keys still survive the hostile profile`() {
        val parsed = generate(hostileProfile)

        val proxies = parsed["proxies"] as List<Map<String, Any>>
        assertTrue("Whitelisted proxies must survive", proxies.any { it["name"] == "Evil-Node" })
        assertTrue(
            "Whitelisted rules must survive",
            (parsed["rules"] as List<*>).contains("MATCH,Evil-Node")
        )
    }

    @Test
    fun `every dropped key family produces a warning`() {
        val outcome = ConfigGenerator.generateRuntimeConfigWithOutcome(
            profileYamlContent = hostileProfile,
            settings = AppSettings(),
            tunFd = null
        )

        val dropped = outcome.warnings
            .filter { it.startsWith("Из профиля удалён ключ вне белого списка: ") }
            .map { it.removePrefix("Из профиля удалён ключ вне белого списка: ") }
            .toSet()

        for (key in listOf(
            "listeners", "tunnels", "external-ui", "geox-url", "geodata-mode",
            "geodata-loader", "interface-name", "secret", "allow-lan", "bind-address",
            "authentication", "skip-auth-prefixes", "lan-allowed-ips", "port", "ports",
            "mixed-port", "socks-port", "external-controller-cors"
        )) {
            assertTrue("dropped key '$key' must produce a warning (got $dropped)", key in dropped)
        }
        // Each warning names exactly one family — no combined lump
        assertTrue(outcome.warnings.all { !it.contains(",") })
        assertTrue(outcome.warnings.isNotEmpty())
    }

    @Test
    fun `whitelist warning literal localizes through WarningLocalizer pattern`() {
        val warning = "Из профиля удалён ключ вне белого списка: listeners"
        val localized = WarningLocalizer.localizePattern(warning)

        assertNotNull("whitelist warning must be recognized by the localizer", localized)
        assertEquals(org.mihomet.app.R.string.conv_warning_profile_key_dropped, localized!!.first)
        assertEquals(listOf("listeners"), localized.second)

        // Unrelated warnings keep passing through untouched
        assertNull(WarningLocalizer.localizePattern("Из профиля удалён ключ: listeners"))
    }

    @Test
    fun `generateRuntimeConfig and WithOutcome produce identical yaml`() {
        // Pin the controller secret: a blank one makes the generator mint a fresh
        // random secret per call, which would legitimately differ between runs.
        val settings = AppSettings(controllerSecret = "pinned_secret")
        val plain = ConfigGenerator.generateRuntimeConfig(hostileProfile, settings, tunFd = null)
        val withOutcome = ConfigGenerator.generateRuntimeConfigWithOutcome(hostileProfile, settings, tunFd = null)
        assertEquals(plain, withOutcome.yaml)
    }

    @Test
    fun `profileKeyWarnings is pure and matches runtime drops`() {
        val warnings = ConfigGenerator.profileKeyWarnings(hostileProfile)
        val dropped = warnings
            .map { it.removePrefix("Из профиля удалён ключ вне белого списка: ") }
            .toSet()

        assertTrue("listeners must be reported at import time", "listeners" in dropped)
        assertTrue("tunnels must be reported at import time", "tunnels" in dropped)
        assertTrue("geox-url must be reported at import time", "geox-url" in dropped)
        assertTrue("interface-name must be reported at import time", "interface-name" in dropped)

        // Benign profile → no warnings
        val benign = """
            proxies:
              - name: "A"
                type: ss
                server: 1.1.1.1
                port: 8388
                cipher: aes-256-gcm
                password: "pw"
            rules:
              - MATCH,A
            dns:
              enable: true
        """.trimIndent()
        assertTrue(ConfigGenerator.profileKeyWarnings(benign).isEmpty())
        // Broken YAML → no crash, no warnings (converter reports it separately)
        assertTrue(ConfigGenerator.profileKeyWarnings("proxies: [unclosed").isEmpty())
        assertTrue(ConfigGenerator.profileKeyWarnings("").isEmpty())
    }

    @Test
    fun `allowlist covers exactly the documented safe keys`() {
        assertEquals(
            setOf(
                "proxies", "proxy-groups", "proxy-providers",
                "rules", "rule-providers", "sub-rules",
                "dns", "sniffer", "tun", "hosts"
            ),
            ConfigGenerator.ALLOWED_PROFILE_KEYS
        )
    }
}
