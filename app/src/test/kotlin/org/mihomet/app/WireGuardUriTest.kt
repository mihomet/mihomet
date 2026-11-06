package org.mihomet.app

import org.junit.Assert.*
import org.junit.Test
import org.mihomet.app.core.config.ConfigGenerator
import org.mihomet.app.core.config.UniversalConfigConverter
import org.mihomet.app.data.preferences.AppSettings
import org.yaml.snakeyaml.Yaml

/**
 * wg:// and awg:// share-link verification (user request: "проверь что WG и AWG
 * работают"). Covers the URI parsers through the public converter facade plus
 * the full path to a runtime config with the AmneziaWG obfuscation fields.
 */
class WireGuardUriTest {

    private val wgUri =
        "wg://203.0.113.10:51820?pk=qPe2xU8YQmJPJrGdOcVbF7aHsKlLzXwCvBnMqWeRtY0%3D" +
            "&peer_pk=bXlQdWJsaWNLZXlGb3JUZXN0Tm9kZUFhYmJjY2RkZGU9" +
            "&preshared_key=cHJlc2hhcmVkS2V5Rm9yVGVzdDEyMzQ1Njc4OTA%3D" +
            "&local_address=10.7.0.2%2F32&dns=1.1.1.1&mtu=1420"

    private val awgUri = wgUri.replace("wg://", "awg://") +
        "&jc=4&jmin=40&jmax=70&s1=15&s2=16&h1=1234567&h2=2345678&h3=3456789&h4=4567890"

    @Test
    fun `wg uri converts to a wireguard outbound`() {
        val outcome = UniversalConfigConverter.convertToMihomoYamlWithOutcome(wgUri)
        val parsed = Yaml().load<Map<String, Any>>(outcome.yaml)
        val proxies = parsed["proxies"] as List<Map<String, Any>>
        assertEquals(1, proxies.size)
        val proxy = proxies.first()
        assertEquals("wireguard", proxy["type"])
        assertEquals("203.0.113.10", proxy["server"])
        assertEquals(51820, proxy["port"])
        assertEquals(
            "qPe2xU8YQmJPJrGdOcVbF7aHsKlLzXwCvBnMqWeRtY0=",
            proxy["private-key"]
        )
        assertEquals(
            "bXlQdWJsaWNLZXlGb3JUZXN0Tm9kZUFhYmJjY2RkZGU9",
            proxy["public-key"]
        )
        assertEquals("10.7.0.2", proxy["ip"]) // bare ip — mihomo netip cannot parse CIDR
        assertEquals(1420, proxy["mtu"])
    }

    @Test
    fun `awg uri keeps the wireguard base and adds obfuscation fields`() {
        val outcome = UniversalConfigConverter.convertToMihomoYamlWithOutcome(awgUri)
        val parsed = Yaml().load<Map<String, Any>>(outcome.yaml)
        val proxy = (parsed["proxies"] as List<Map<String, Any>>).first()

        assertEquals("wireguard", proxy["type"])
        assertEquals("203.0.113.10", proxy["server"])
        // SnakeYAML round-trip may reload small longs as Integer
        assertEquals(4, proxy["junk-packet-count"])
        assertEquals(40, proxy["junk-packet-min-size"])
        assertEquals(70, proxy["junk-packet-max-size"])
        assertEquals(15, proxy["init-packet-junk-size"])
        assertEquals(16, proxy["response-packet-junk-size"])
        assertEquals(1234567L, (proxy["init-packet-magic-header"] as Number).toLong())
        assertEquals(2345678L, (proxy["response-packet-magic-header"] as Number).toLong())
        assertEquals(3456789L, (proxy["transport-packet-magic-header"] as Number).toLong())
        assertEquals(4567890L, (proxy["under-load-packet-magic-header"] as Number).toLong())
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `awg uri survives the runtime generator with obfuscation intact`() {
        val outcome = UniversalConfigConverter.convertToMihomoYamlWithOutcome(awgUri)
        val runtime = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = outcome.yaml,
            settings = AppSettings(controllerSecret = "s", autoBalancerEnabled = false),
            tunFd = null
        )
        val parsed = Yaml().load<Map<String, Any>>(runtime)
        val proxies = parsed["proxies"] as List<Map<String, Any>>
        val proxy = proxies.first()
        assertEquals("wireguard", proxy["type"])
        assertEquals(4, proxy["junk-packet-count"])
        assertEquals(1234567L, (proxy["init-packet-magic-header"] as Number).toLong())
        assertNotNull(parsed["proxy-groups"])
    }

    @Test
    fun `wg uri without a private key is rejected`() {
        val outcome = UniversalConfigConverter.convertToMihomoYamlWithOutcome(
            "wg://203.0.113.10:51820?peer_pk=abc"
        )
        // Unknown-format path keeps content and flags it — must not crash or emit a node
        assertFalse(
            outcome.yaml.contains("private-key")
        )
    }
}
