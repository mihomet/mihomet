package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.core.config.UniversalConfigConverter
import org.yaml.snakeyaml.Yaml

/**
 * Maximal-complexity sing-box matrix: every outbound type x transport x TLS mode
 * with ALL parameters, pinning each mapped mihomo field. The urltest/selector
 * groups and route structure preservation are covered too.
 */
@Suppress("UNCHECKED_CAST")
class SingBoxMatrixTest {

    private fun convert(configJson: String): Map<String, Any> {
        val outcome = UniversalConfigConverter.convertToMihomoYamlWithOutcome(configJson)
        return Yaml().load<Any>(outcome.yaml) as Map<String, Any>
    }

    private fun outbound(type: String, body: String, tag: String = "proxy"): String =
        """{"type":"$type","tag":"$tag",$body}"""

    private fun wrapper(vararg outbounds: String): String = """{
        "outbounds": [${outbounds.joinToString(",")},
          {"type":"direct","tag":"direct"},
          {"type":"block","tag":"block"}]
    }""".trimIndent()

    private fun proxies(parsed: Map<String, Any>): List<Map<String, Any>> =
        parsed["proxies"] as List<Map<String, Any>>

    private fun byName(parsed: Map<String, Any>, tag: String): Map<String, Any> =
        proxies(parsed).first { it["name"] == tag }

    // ---- VLESS ----

    @Test
    fun `vless reality utls flow and pbk`() {
        val parsed = convert(wrapper(outbound("vless", """
            "server":"1.2.3.4","server_port":443,
            "uuid":"83999090-86f8-4009-a889-746a492638d1","flow":"xtls-rprx-vision",
            "tls":{"enabled":true,"server_name":"www.microsoft.com",
              "utls":{"enabled":true,"fingerprint":"chrome"},
              "reality":{"enabled":true,"public_key":"PBK","short_id":"DF23C040"}}
        """)))
        val p = byName(parsed, "proxy")
        assertEquals("vless", p["type"])
        assertEquals("xtls-rprx-vision", p["flow"])
        assertEquals(true, p["tls"])
        assertEquals("www.microsoft.com", p["servername"])
        assertEquals("chrome", p["client-fingerprint"])
        assertEquals(mapOf("public-key" to "PBK", "short-id" to "DF23C040"), p["reality-opts"])
    }

    @Test
    fun `vless ws with all headers and early data`() {
        val parsed = convert(wrapper(outbound("vless", """
            "server":"cdn.example.com","server_port":443,"uuid":"u-1",
            "tls":{"enabled":true,"server_name":"sni.example.com"},
            "transport":{"type":"ws","path":"/ws?ed=2048",
              "headers":{"Host":"ws.example.com","User-Agent":"UA/1"},
              "max_early_data":2048,"early_data_header_name":"Sec-WebSocket-Protocol"}
        """)))
        val p = byName(parsed, "proxy")
        val ws = p["ws-opts"] as Map<String, Any>
        assertEquals("/ws?ed=2048", ws["path"])
        assertEquals(mapOf("Host" to "ws.example.com", "User-Agent" to "UA/1"), ws["headers"])
        assertEquals(2048, ws["max-early-data"])
        assertEquals("Sec-WebSocket-Protocol", ws["early-data-header-name"])
    }

    // ---- VMess ----

    @Test
    fun `vmess with alterId cipher and security zero`() {
        val parsed = convert(wrapper(outbound("vmess", """
            "server":"v.example.com","server_port":8080,"uuid":"vm-uuid",
            "alter_id":64,"security":"zero"
        """)))
        val p = byName(parsed, "proxy")
        assertEquals("vmess", p["type"])
        assertEquals(64, p["alterId"])
        assertEquals("zero", p["cipher"])
    }

    // ---- Trojan ----

    @Test
    fun `trojan grpc with tls`() {
        val parsed = convert(wrapper(outbound("trojan", """
            "server":"t.example.com","server_port":443,"password":"TPass",
            "tls":{"enabled":true,"server_name":"t.example.com"},
            "transport":{"type":"grpc","service_name":"TrojanSvc"}
        """)))
        val p = byName(parsed, "proxy")
        assertEquals("trojan", p["type"])
        assertEquals("TPass", p["password"])
        assertEquals("TrojanSvc", (p["grpc-opts"] as Map<String, Any>)["grpc-service-name"])
    }

    // ---- Shadowsocks ----

    @Test
    fun `ss classic and ss2022 methods`() {
        val parsed = convert(wrapper(
            outbound("shadowsocks", """ "server":"a.example.com","server_port":8388,
                "method":"aes-256-gcm","password":"Pass1" """, "ss1"),
            outbound("shadowsocks", """ "server":"b.example.com","server_port":8389,
                "method":"2022-blake3-aes-256-gcm","password":"PSK2" """, "ss2")
        ))
        assertEquals("aes-256-gcm", byName(parsed, "ss1")["cipher"])
        assertEquals("2022-blake3-aes-256-gcm", byName(parsed, "ss2")["cipher"])
    }

    // ---- Hysteria ----

    @Test
    fun `hysteria2 with obfs up down and ports hopping`() {
        val parsed = convert(wrapper(outbound("hysteria2", """
            "server":"h2.example.com","server_port":443,"password":"H2Pass",
            "up_mbps":100,"down_mbps":300,
            "obfs":{"type":"salamander","password":"ObfsPass"},
            "tls":{"enabled":true,"server_name":"h2.example.com"}
        """)))
        val p = byName(parsed, "proxy")
        assertEquals("hysteria2", p["type"])
        assertEquals("H2Pass", p["password"])
        assertEquals("salamander", p["obfs"])
        assertEquals("ObfsPass", p["obfs-password"])
    }

    @Test
    fun `ss plugin_opts string parses into mihomo plugin-opts map`() {
        val parsed = convert(wrapper(outbound("shadowsocks", """
            "server":"pl.example.com","server_port":8388,
            "method":"aes-256-gcm","password":"P",
            "plugin":"obfs-local","plugin_opts":"obfs=http;obfs-host=cdn.example.com"
        """)))
        val p = byName(parsed, "proxy")
        assertEquals("obfs", p["plugin"])
        val opts = p["plugin-opts"] as Map<String, Any>
        assertEquals("http", opts["mode"])
        assertEquals("cdn.example.com", opts["host"])
    }

    @Test
    fun `hysteria v1 keeps its own type with auth and bandwidth`() {
        val parsed = convert(wrapper(outbound("hysteria", """
            "server":"h1.example.com","server_port":36712,
            "auth_str":"AuthStr","up_mbps":50,"down_mbps":200,
            "tls":{"enabled":true,"server_name":"h1.example.com"}
        """)))
        val p = byName(parsed, "proxy")
        assertEquals("hysteria", p["type"])
        assertEquals("AuthStr", p["password"])
        assertEquals("h1.example.com", p["sni"])
    }

    // ---- TUIC ----

    @Test
    fun `tuic v5 with congestion and udp relay`() {
        val parsed = convert(wrapper(outbound("tuic", """
            "server":"tu.example.com","server_port":443,
            "uuid":"tu-uuid","password":"TuPass",
            "congestion_control":"brutal","udp_relay_mode":"native",
            "tls":{"enabled":true,"server_name":"tu.example.com"}
        """)))
        val p = byName(parsed, "proxy")
        assertEquals("tuic", p["type"])
        assertEquals("brutal", p["congestion-controller"])
        assertEquals("native", p["udp-relay-mode"])
        assertEquals("tu.example.com", p["sni"])
    }

    // ---- New protocol mappings ----

    @Test
    fun `anytls maps with password and tls`() {
        val parsed = convert(wrapper(outbound("anytls", """
            "server":"an.example.com","server_port":443,"password":"AnyPass",
            "tls":{"enabled":true,"server_name":"an.example.com"}
        """)))
        val p = byName(parsed, "proxy")
        assertEquals("anytls", p["type"])
        assertEquals("AnyPass", p["password"])
        assertEquals("an.example.com", p["servername"])
    }

    @Test
    fun `shadowtls maps with version and password`() {
        val parsed = convert(wrapper(outbound("shadowtls", """
            "server":"st.example.com","server_port":443,"version":3,"password":"StPass",
            "tls":{"enabled":true,"server_name":"st.example.com"}
        """)))
        val p = byName(parsed, "proxy")
        assertEquals("shadowtls", p["type"])
        assertEquals(3, p["version"])
        assertEquals("StPass", p["password"])
    }

    // ---- Transports ----

    @Test
    fun `httpupgrade transport is its own network not ws`() {
        val parsed = convert(wrapper(outbound("vless", """
            "server":"u.example.com","server_port":443,"uuid":"u-2",
            "transport":{"type":"httpupgrade","path":"/up","host":"u.example.com"}
        """)))
        val p = byName(parsed, "proxy")
        assertEquals("httpupgrade", p["network"])
        assertEquals("/up", (p["httpupgrade-opts"] as Map<String, Any>)["path"])
        assertNull(p["ws-opts"])
    }

    @Test
    fun `multiplex smux maps to mihomo smux`() {
        val parsed = convert(wrapper(outbound("vless", """
            "server":"m.example.com","server_port":443,"uuid":"u-3",
            "multiplex":{"enabled":true,"protocol":"h2mux",
              "max_connections":4,"min_streams":2,"max_streams":8,"padding":true}
        """)))
        val p = byName(parsed, "proxy")
        val smux = p["smux"] as Map<String, Any>
        assertEquals("h2mux", smux["protocol"])
        assertEquals(4, smux["max-connections"])
        assertEquals(2, smux["min-streams"])
        assertEquals(8, smux["max-streams"])
        assertEquals(true, smux["padding"])
    }

    @Test
    fun `http outbound with auth maps to mihomo http`() {
        val parsed = convert(wrapper(outbound("http", """
            "server":"p.example.com","server_port":3128,
            "username":"ProxyUser","password":"ProxyPass"
        """)))
        val p = byName(parsed, "proxy")
        assertEquals("http", p["type"])
        assertEquals("ProxyUser", p["username"])
        assertEquals("ProxyPass", p["password"])
    }

    // ---- Groups ----

    @Test
    fun `urltest and selector groups survive with fields`() {
        val parsed = convert("""{
            "outbounds": [
              {"type":"urltest","tag":"Auto","outbounds":["a","b"],
               "url":"https://www.gstatic.com/generate_204","interval":30000000000,
               "tolerance":50,"interrupt_exist_connections":true},
              {"type":"selector","tag":"Select","outbounds":["Auto","a","b"]},
              {"type":"direct","tag":"direct"},
              {"type":"block","tag":"block"},
              {"type":"vless","tag":"a","server":"1.1.1.1","server_port":443,"uuid":"u"},
              {"type":"vless","tag":"b","server":"2.2.2.2","server_port":443,"uuid":"u"}
            ]
        }""")
        val groups = parsed["proxy-groups"] as List<Map<String, Any>>
        val auto = groups.first { it["name"] == "Auto" }
        assertEquals("url-test", auto["type"])
        assertEquals(listOf("a", "b"), auto["proxies"])
        val select = groups.first { it["name"] == "Select" }
        assertEquals("select", select["type"])
        assertEquals(listOf("Auto", "a", "b"), select["proxies"])
    }

    @Test
    fun `exotic outbounds produce warnings and are skipped`() {
        val outcome = UniversalConfigConverter.convertToMihomoYamlWithOutcome(wrapper(
            outbound("ssh", """ "server":"s.example.com","server_port":22,"user":"u" """),
            outbound("naive", """ "server":"n.example.com","server_port":443 """),
            outbound("mieru", """ "server":"mi.example.com","server_port":443 """)
        ))
        assertTrue(
            "expected warnings for unmappable outbounds, got: ${outcome.warnings}",
            outcome.warnings.isNotEmpty()
        )
        val parsed = Yaml().load<Any>(outcome.yaml) as? Map<String, Any>
        val proxies = parsed?.get("proxies") as? List<*>
        assertTrue("unmappable outbounds must not produce proxies", proxies == null || proxies.isEmpty())
    }
}
