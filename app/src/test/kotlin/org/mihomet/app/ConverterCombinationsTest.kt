package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.core.config.UniversalConfigConverter
import org.yaml.snakeyaml.Yaml

/**
 * Protocol x transport x security combination matrix for the URI parser,
 * group-type preservation checks, and Base64 resilience (TODO Phase 2.2/2.5).
 */
@Suppress("UNCHECKED_CAST")
class ConverterCombinationsTest {

    private fun convertOne(uri: String): Map<String, Any> {
        val parsed = Yaml().load<Map<String, Any>>(UniversalConfigConverter.convertToMihomoYaml(uri))
        val proxies = parsed["proxies"] as List<Map<String, Any>>
        assertEquals("one URI -> one node", 1, proxies.size)
        return proxies[0]
    }

    @Test
    fun `test vless reality over grpc keeps service name and fingerprint`() {
        val node = convertOne(
            "vless://uuid@g.example.com:443?security=reality&type=grpc&serviceName=GunService" +
                "&fp=chrome&sni=www.example.com&pbk=pbk1&sid=sid1#RealityGRPC"
        )
        assertEquals("vless", node["type"])
        assertEquals("grpc", node["network"])
        assertEquals("GunService", (node["grpc-opts"] as Map<String, Any>)["grpc-service-name"])
        assertEquals("chrome", node["client-fingerprint"])
        assertEquals("pbk1", (node["reality-opts"] as Map<String, Any>)["public-key"])
    }

    @Test
    fun `test vless ws with early data keeps max-early-data and header name`() {
        val node = convertOne(
            "vless://uuid@w.example.com:443?security=tls&type=ws&path=%2Fws&ed=2048&eh=Sec-WebSocket-Protocol#WsEd"
        )
        val wsOpts = node["ws-opts"] as Map<String, Any>
        assertEquals("/ws", wsOpts["path"])
        assertEquals(2048, wsOpts["max-early-data"])
        assertEquals("Sec-WebSocket-Protocol", wsOpts["early-data-header-name"])
    }

    @Test
    fun `test hysteria2 with obfs and mport keeps port hopping`() {
        val node = convertOne(
            "hysteria2://pass@h.example.com:443?obfs=salamander&obfs-password=obfspass" +
                "&mport=20000-40000&sni=h.example.com#Hy2Obfs"
        )
        assertEquals("hysteria2", node["type"])
        assertEquals("salamander", node["obfs"])
        assertEquals("obfspass", node["obfs-password"])
        assertEquals("20000-40000", node["ports"])
    }

    @Test
    fun `test tuic with full alpn list and congestion controller`() {
        val node = convertOne(
            "tuic://uuid:pass@t.example.com:8443?congestion_control=bbr&alpn=h3,h3-29" +
                "&udp_relay_mode=native&sni=t.example.com#TuicFull"
        )
        assertEquals(listOf("h3", "h3-29"), node["alpn"])
        assertEquals("bbr", node["congestion-controller"])
        assertEquals("native", node["udp-relay-mode"])
    }

    @Test
    fun `test ss SIP002 websafe base64 userinfo without padding`() {
        // base64("aes-128-gcm:secret") unpadded, websafe alphabet
        val userinfo = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-128-gcm:secret".toByteArray())
        val node = convertOne("ss://$userinfo@s.example.com:8388#SsB64")
        assertEquals("aes-128-gcm", node["cipher"])
        assertEquals("secret", node["password"])
    }

    @Test
    fun `test clash fallback and load-balance groups survive normalization untouched`() {
        val clashYaml = """
            mixed-port: 7890
            proxies:
              - {name: N1, type: ss, server: a.com, port: 1, cipher: aes-128-gcm, password: p}
              - {name: N2, type: ss, server: b.com, port: 2, cipher: aes-128-gcm, password: p}
            proxy-groups:
              - {name: FB, type: fallback, proxies: [N1, N2], url: 'http://www.gstatic.com/generate_204', interval: 300}
              - {name: LB, type: load-balance, proxies: [N1, N2], strategy: round-robin, url: 'http://www.gstatic.com/generate_204', interval: 300}
            rules: [MATCH,FB]
        """.trimIndent()

        val outcome = UniversalConfigConverter.convertToMihomoYamlWithOutcome(clashYaml)
        assertTrue("clash passthrough must not warn", outcome.warnings.isEmpty())
        val parsed = Yaml().load<Map<String, Any>>(outcome.yaml)
        val groups = parsed["proxy-groups"] as List<Map<String, Any>>
        val fallback = groups.first { it["name"] == "FB" }
        val balance = groups.first { it["name"] == "LB" }
        assertEquals("fallback", fallback["type"])
        assertEquals("round-robin", balance["strategy"])
    }

    @Test
    fun `test base64 payload resilience - unpadded urlsafe and multiline`() {
        val uriList = "vless://uuid@x.example.com:443#Node1\nvmess://dummy-vmess-b64"
        val base = java.util.Base64.getMimeEncoder().encodeToString(uriList.toByteArray())
            .replace("+", "-").replace("/", "_").replace("=", "")

        // The converter must decode unpadded, url-safe, and multi-line payloads alike
        val outcome = UniversalConfigConverter.convertToMihomoYamlWithOutcome(base)
        val parsed = Yaml().load<Map<String, Any>>(outcome.yaml)
        val proxies = parsed["proxies"] as List<Map<String, Any>>
        assertTrue("expected decoded nodes, got ${proxies.size}", proxies.isNotEmpty())
        assertEquals("Node1", proxies[0]["name"])
    }

    @Test
    fun `test singbox wireguard with string local_address no longer crashes`() {
        // Regression: Hiddify emits "local_address": "10.90.0.3/32" as a plain string
        val singBoxJson = """
            {"outbounds":[
              {"type":"wireguard","tag":"WG","server":"w.example.com","server_port":51820,
               "private_key":"priv","peer_public_key":"pub","local_address":"10.90.0.3/32"}
            ]}
        """.trimIndent()
        val outcome = UniversalConfigConverter.convertToMihomoYamlWithOutcome(singBoxJson)
        assertTrue("wireguard conversion must not fail: ${outcome.warnings}", outcome.warnings.isEmpty())
        val parsed = Yaml().load<Map<String, Any>>(outcome.yaml)
        val proxies = parsed["proxies"] as List<Map<String, Any>>
        val wg = proxies.first { it["type"] == "wireguard" }
        assertEquals("10.90.0.3/32", wg["ip"])
        assertEquals("pub", wg["public-key"])
    }

    @Test
    fun `test all uri schemes produce structurally valid nodes`() {
        val schemes = mapOf(
            "vless" to "vless://u@h1.example.com:443#A",
            "vmess" to "vmess://eyJhZGQiOiJoMi5leGFtcGxlLmNvbSIsInBvcnQiOiI0NDMiLCJpZCI6InV1aWQiLCJhaWQiOiIwIiwicHMiOiJCIiwibmV0IjoidGNwIn0=",
            "trojan" to "trojan://pw@h3.example.com:443#C",
            "ss" to "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ=@h4.example.com:8388#D",
            "hysteria2" to "hysteria2://pw@h5.example.com:443#E",
            "hysteria" to "hysteria://h6.example.com:443?auth=secret#F",
            "tuic" to "tuic://uuid:pw@h7.example.com:443#G",
            "ssh" to "ssh://user@h8.example.com:22#H",
            "snell" to "snell://h9.example.com:6160?psk=psk1&version=4#I"
        )
        for ((scheme, uri) in schemes) {
            val node = convertOne(uri)
            assertEquals(scheme, node["type"])
            for (field in listOf("name", "type", "server", "port")) {
                assertNotNull("$scheme node missing '$field'", node[field])
            }
        }
    }
}
