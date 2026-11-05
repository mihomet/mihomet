package org.mihomet.app

import org.junit.Assert.*
import org.junit.Test
import org.mihomet.app.core.config.ConfigGenerator
import org.mihomet.app.core.config.UniversalConfigConverter
import org.mihomet.app.core.config.WireGuardConfParser
import org.mihomet.app.data.preferences.AppSettings
import org.mihomet.app.data.subs.ProfileValidation
import org.yaml.snakeyaml.Yaml

/**
 * Full-path regression for local WireGuard/AmneziaWG .conf import:
 * the converter must emit a "proxies: [ ... ]" config (a bare proxy map
 * fails ProfileValidation and was silently dropped by the import gate —
 * the "file not added" bug), and the result must survive the runtime
 * generator with the AmneziaWG obfuscation fields intact.
 */
class WireGuardConfFileTest {

    private val awgConf = """
        [Interface]
        PrivateKey = qPe2xU8YQmJPJrGdOcVbF7aHsKlLzXwCvBnMqWeRtY0=
        Address = 10.7.0.2/32
        DNS = 1.1.1.1
        MTU = 1420
        Jc = 4
        Jmin = 40
        Jmax = 70
        S1 = 15
        S2 = 16
        H1 = 1234567
        H2 = 2345678
        H3 = 3456789
        H4 = 4567890

        [Peer]
        PublicKey = bXlQdWJsaWNLZXlGb3JUZXN0Tm9kZUFhYmJjY2RkZGU9
        PresharedKey = cHJlc2hhcmVkS2V5Rm9yVGVzdDEyMzQ1Njc4OTA=
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = 203.0.113.10:51820
    """.trimIndent()

    @Test
    fun `conf converts to a config whose proxies pass ProfileValidation`() {
        val outcome = UniversalConfigConverter.convertToMihomoYamlWithOutcome(awgConf)
        assertTrue(
            "wireguard conf conversion must yield a valid importable config",
            ProfileValidation.isValid(outcome.yaml)
        )
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `converted conf keeps wireguard node and amnezia fields`() {
        val outcome = UniversalConfigConverter.convertToMihomoYamlWithOutcome(awgConf)
        val parsed = Yaml().load<Map<String, Any>>(outcome.yaml)
        val proxies = parsed["proxies"] as List<Map<String, Any>>
        assertEquals(1, proxies.size)
        val proxy = proxies.first()
        assertEquals("wireguard", proxy["type"])
        assertEquals("203.0.113.10", proxy["server"])
        assertEquals(51820, proxy["port"])
        assertEquals("bXlQdWJsaWNLZXlGb3JUZXN0Tm9kZUFhYmJjY2RkZGU9", proxy["public-key"])
        assertEquals(4, proxy["junk-packet-count"])
        // SnakeYAML round-trip may reload small longs as Integer
        assertEquals(1234567L, (proxy["init-packet-magic-header"] as Number).toLong())
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `runtime generation from converted conf yields proxies and groups`() {
        val outcome = UniversalConfigConverter.convertToMihomoYamlWithOutcome(awgConf)
        val settings = AppSettings(controllerSecret = "s", autoBalancerEnabled = false)
        val runtime = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = outcome.yaml,
            settings = settings,
            tunFd = null
        )
        val parsed = Yaml().load<Map<String, Any>>(runtime)
        val proxies = parsed["proxies"] as List<*>
        assertEquals(1, proxies.size)
        assertNotNull(parsed["proxy-groups"])
    }

    @Test
    fun `parser detection still matches ini shapes`() {
        assertTrue(WireGuardConfParser.isWireGuardConf(awgConf))
        assertTrue(WireGuardConfParser.isWireGuardConf(awgConf.lowercase()))
        assertFalse(WireGuardConfParser.isWireGuardConf("proxies:\n  - name: x"))
    }
}
