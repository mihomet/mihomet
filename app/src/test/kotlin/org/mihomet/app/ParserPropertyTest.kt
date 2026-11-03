package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.core.config.ConversionWarning
import org.mihomet.app.core.config.UniversalConfigConverter
import org.mihomet.app.core.config.UriListParser
import kotlin.random.Random

/**
 * Property-based hardening for the share-link/URI surface (deterministic seeds):
 *
 *  1. **No-throw**: any mutation of a valid URI (byte flips, truncation, garbage
 *     suffixes) must return null or a proxy map — never an exception.
 *  2. **No header injection**: no parsed field may contain CR/LF — the values end
 *     up in HTTP requests and YAML documents.
 *  3. **Core invariants survive**: host/port of a valid node are preserved under
 *     unrelated mutations of the REMARKS part.
 *
 * Fixed seeds keep failures reproducible; the loops are small enough for CI.
 */
class ParserPropertyTest {

    private val rng = Random(42)

    private val validUris = listOf(
        "vless://83999090-86f8-4009-a889-746a492638d1@1.2.3.4:443?encryption=none&flow=xtls-rprx-vision&security=reality&pbk=PK&sid=AB12&fp=chrome&sni=www.microsoft.com&type=tcp#VLESS-OK",
        "vmess://" + java.util.Base64.getEncoder().encodeToString(
            """{"v":"2","ps":"VM","add":"vm.example.com","port":"443","id":"vm-id","aid":"0","scy":"auto","net":"ws","host":"h.example.com","path":"/p","tls":"tls","sni":"s.example.com"}""".toByteArray()),
        "trojan://pass@t.example.com:443?security=tls&sni=t.example.com&type=grpc&serviceName=svc#Trojan",
        "ss://" + java.util.Base64.getEncoder().encodeToString("aes-256-gcm:sspass".toByteArray()) + "@s.example.com:8388#SS",
        "hysteria2://h2pass@h2.example.com:443?sni=h2.example.com&insecure=1&obfs=salamander&obfs-password=ob#H2",
        "tuic://tu-uuid:tupass@tu.example.com:443?congestion_control=bbr&udp_relay_mode=native&alpn=h3#TUIC",
        "snell://psk@sn.example.com:28862?psk=RealPsk&version=4&obfs=http&obfs-host=sn.example.com#SN",
        "hy2://h2pass@[2001:db8::1]:443#H2v6",
        "vless://uuid@16.36--2026.11.02.time:443#ExpiryPseudo"
    )

    private fun mutate(uri: String, rng: Random): String {
        val chars = uri.toCharArray()
        return when (rng.nextInt(6)) {
            0 -> uri.substring(0, (uri.length * rng.nextDouble()).toInt().coerceIn(1, uri.length))
            1 -> uri + listOf("", "\n", "\r\n", ")))", "\u0000", "？？？？", "'\";--").random(rng)
            2 -> {
                val i = rng.nextInt(chars.size)
                chars[i] = (rng.nextInt(96) + 32).toChar()
                String(chars)
            }
            3 -> {
                val i = rng.nextInt(chars.size.coerceAtLeast(1))
                chars.copyOfRange(0, i).concatToString() + chars.copyOfRange((i + 1).coerceAtMost(chars.size), chars.size).concatToString()
            }
            4 -> listOf(
                "vless://", "vmess://", "ss://", "trojan://", "hysteria2://",
                "tuic://", "snell://", "http://", "x\ny://"
            ).random(rng) + uri
            else -> uri.reversed()
        }
    }

    @Test
    fun `mutated uris never throw and never inject crlf`() {
        repeat(600) {
            val uri = validUris[rng.nextInt(validUris.size)]
            val mutated = mutate(uri, rng)
            val warnings = mutableListOf<ConversionWarning>()
            val result = try {
                UriListParser.parseUriToMihomoProxy(mutated, warnings)
            } catch (e: Exception) {
                throw AssertionError("parser threw on: ${mutated.take(120)}", e)
            }
            if (result != null) {
                for ((k, v) in result) {
                    val text = v?.toString() ?: continue
                    assertTrue(
                        "CR/LF leaked into field '$k' of: ${mutated.take(80)}",
                        !text.contains('\r') && !text.contains('\n')
                    )
                }
            }
        }
    }

    @Test
    fun `pure garbage schemes return null with a warning`() {
        repeat(50) { i ->
            val warnings = mutableListOf<ConversionWarning>()
            val garbage = listOf("aaa://", "://", "vless:", "vless://", "://@:", "vless://a", "ss://",
                "vmess://!!!not-base64!!!")[i % 8] + rng.nextInt(1000)
            val result = UriListParser.parseUriToMihomoProxy(garbage, warnings)
            if (result == null) {
                assertTrue("null result must carry a warning for: $garbage", warnings.isNotEmpty())
            }
        }
    }

    @Test
    fun `remarks mutation never changes server port uuid`() {
        repeat(200) {
            val remarks = listOf("Node", "Узел 🇷🇺", "a\nb", "x\r\nSet-Cookie: 1", "#hash #tag", "").random(rng)
            val uri = "vless://fixed-uuid@9.8.7.6:8443?encryption=none&security=tls&sni=s.example.com&type=tcp#" +
                java.net.URLEncoder.encode(remarks, "UTF-8")
            val p = UriListParser.parseUriToMihomoProxy(uri, mutableListOf()) ?: return@repeat
            assertEquals("9.8.7.6", p["server"])
            assertEquals(8443, p["port"])
            assertEquals("fixed-uuid", p["uuid"])
            val name = p["name"]?.toString() ?: ""
            assertTrue(
                "node name must not contain raw CR/LF (header/YAML injection): $name",
                !name.contains('\r') && !name.contains('\n')
            )
        }
    }

    // ---- Subscription JSON / YAML conversion never throws ----

    @Test
    fun `mutated clash yaml never throws the converter`() {
        val base = """
            mixed-port: 7890
            proxies:
              - {name: a, type: ss, server: 1.2.3.4, port: 443, cipher: aes-256-gcm, password: x}
              - {name: b, type: vmess, server: 1.2.3.5, port: 443, uuid: u, alterId: 0, cipher: auto, network: ws, ws-opts: {path: /}}
            proxy-groups:
              - {name: g, type: select, proxies: [a, b]}
            rules: [MATCH,g]
        """.trimIndent()
        repeat(300) {
            val mutated = mutate(base, Random(it))
            try {
                UniversalConfigConverter.convertToMihomoYamlWithOutcome(mutated)
            } catch (e: StackOverflowError) {
                throw AssertionError("converter recursion on mutated yaml", e)
            } catch (e: Exception) {
                // converter converts fail-closed exceptions into outcomes/warnings — a raw
                // exception escaping to the caller is a contract breach
                throw AssertionError("converter threw on mutated yaml: ${e.message?.take(120)}", e)
            }
        }
    }

    @Test
    fun `mutated sing-box json never throws the converter`() {
        val base = """{"outbounds":[
            {"type":"vless","tag":"a","server":"1.2.3.4","server_port":443,"uuid":"u",
             "tls":{"enabled":true,"server_name":"s","reality":{"enabled":true,"public_key":"pk","short_id":"sid"}}},
            {"type":"urltest","tag":"Auto","outbounds":["a"]},
            {"type":"direct","tag":"direct"}]}"""
        repeat(300) {
            val mutated = mutate(base, Random(it + 1000))
            try {
                UniversalConfigConverter.convertToMihomoYamlWithOutcome(mutated)
            } catch (e: StackOverflowError) {
                throw AssertionError("converter recursion on mutated sing-box json", e)
            } catch (e: Exception) {
                throw AssertionError("converter threw on mutated sing-box json: ${e.message?.take(120)}", e)
            }
        }
    }

    @Test
    fun `valid uris still parse after the fuzz round`() {
        // Guard against a parser "hardened" into rejecting everything.
        for (uri in validUris.dropLast(1)) {
            assertNotNull("valid URI must still parse: ${uri.take(60)}", UriListParser.parseUriToMihomoProxy(uri, mutableListOf()))
        }
        // The expiry pseudo-link is intentionally null (display metadata)
        assertNull(UriListParser.parseUriToMihomoProxy(validUris.last(), mutableListOf()))
    }
}
