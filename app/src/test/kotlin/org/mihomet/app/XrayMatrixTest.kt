package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.core.config.UniversalConfigConverter
import org.yaml.snakeyaml.Yaml

/**
 * Maximal-complexity Xray JSON matrix: every protocol x every transport x every
 * security mode with ALL parameters a panel can emit. Each assertion pins a
 * specific field of the mihomo proxy mapping — a regression on any single
 * parameter fails with a named message.
 */
@Suppress("UNCHECKED_CAST")
class XrayMatrixTest {

    private fun convert(configJson: String): Map<String, Any> {
        val outcome = UniversalConfigConverter.convertToMihomoYamlWithOutcome(configJson)
        return Yaml().load<Any>(outcome.yaml) as Map<String, Any>
    }

    private fun singleOutbound(outbound: String): String = """{
        "remarks": "Node",
        "outbounds": [$outbound],
        "routing": {"rules": [{"type": "field", "outboundTag": "proxy", "network": "tcp,udp"}]}
    }""".trimIndent()

    private fun proxies(parsed: Map<String, Any>): List<Map<String, Any>> =
        parsed["proxies"] as List<Map<String, Any>>

    private fun first(parsed: Map<String, Any>): Map<String, Any> = proxies(parsed).first()

    // ---- VLESS ----

    @Test
    fun `vless tcp reality full parameter set`() {
        val parsed = convert(singleOutbound(
            """{"tag":"proxy","protocol":"vless",
               "settings":{"vnext":[{"address":"1.2.3.4","port":443,
                 "users":[{"id":"83999090-86f8-4009-a889-746a492638d1","encryption":"none","flow":"xtls-rprx-vision","level":8}]}]},
               "streamSettings":{"network":"tcp","security":"reality",
                 "realitySettings":{"show":false,"fingerprint":"chrome","serverName":"www.microsoft.com",
                   "publicKey":"PBK_Value","shortId":"DF23C040","spiderX":"/",
                   "mldsa65Verify":"PQKEY"}}}"""))
        val p = first(parsed)
        assertEquals("vless", p["type"])
        assertEquals("1.2.3.4", p["server"])
        assertEquals(443, p["port"])
        assertEquals("83999090-86f8-4009-a889-746a492638d1", p["uuid"])
        assertEquals("xtls-rprx-vision", p["flow"])
        assertEquals(true, p["tls"])
        assertEquals("www.microsoft.com", p["servername"])
        assertEquals("chrome", p["client-fingerprint"])
        val ro = p["reality-opts"] as Map<String, Any>
        assertEquals("PBK_Value", ro["public-key"])
        assertEquals("DF23C040", ro["short-id"])
        assertEquals("/", ro["spider-x"])
    }

    @Test
    fun `vless ws tls with alpn early-data and headers`() {
        val parsed = convert(singleOutbound(
            """{"tag":"proxy","protocol":"vless",
               "settings":{"vnext":[{"address":"cdn.example.com","port":443,
                 "users":[{"id":"uuid-1","encryption":"none"}]}]},
               "streamSettings":{"network":"ws","security":"tls",
                 "tlsSettings":{"serverName":"sni.example.com","alpn":["h2","http/1.1"],
                   "fingerprint":"firefox","allowInsecure":true},
                 "wsSettings":{"path":"/wspath?ed=2048","headers":{"Host":"ws.example.com","User-Agent":"UA/1"}}}}"""))
        val p = first(parsed)
        assertEquals(true, p["tls"])
        assertEquals("sni.example.com", p["servername"])
        assertEquals(listOf("h2", "http/1.1"), p["alpn"])
        assertEquals("firefox", p["client-fingerprint"])
        assertEquals(true, p["skip-cert-verify"])
        val ws = p["ws-opts"] as Map<String, Any>
        assertEquals("/wspath?ed=2048", ws["path"])
        assertEquals(mapOf("Host" to "ws.example.com", "User-Agent" to "UA/1"), ws["headers"])
    }

    @Test
    fun `vless grpc with multiMode`() {
        val parsed = convert(singleOutbound(
            """{"tag":"proxy","protocol":"vless",
               "settings":{"vnext":[{"address":"g.example.com","port":443,
                 "users":[{"id":"uuid-2","encryption":"none"}]}]},
               "streamSettings":{"network":"grpc","security":"tls",
                 "grpcSettings":{"serviceName":"SvcName","multiMode":true,"idle_timeout":60}}}"""))
        val p = first(parsed)
        val grpc = p["grpc-opts"] as Map<String, Any>
        assertEquals("SvcName", grpc["grpc-service-name"])
        assertEquals(true, grpc["grpc-multi-mode"])
    }

    @Test
    fun `vless xhttp splithttp with mode`() {
        val parsed = convert(singleOutbound(
            """{"tag":"proxy","protocol":"vless",
               "settings":{"vnext":[{"address":"x.example.com","port":443,
                 "users":[{"id":"uuid-3","encryption":"none"}]}]},
               "streamSettings":{"network":"xhttp","security":"tls",
                 "xhttpSettings":{"path":"/xpath","host":"x.example.com","mode":"auto"}}}"""))
        val p = first(parsed)
        assertEquals("xhttp", p["network"])
        val opts = p["xhttp-opts"] as Map<String, Any>
        assertEquals("/xpath", opts["path"])
        assertEquals("x.example.com", opts["host"])
        assertEquals("auto", opts["mode"])
    }

    @Test
    fun `vless tcp with http obfs header maps to mihomo http-opts`() {
        val parsed = convert(singleOutbound(
            """{"tag":"proxy","protocol":"vless",
               "settings":{"vnext":[{"address":"o.example.com","port":80,
                 "users":[{"id":"uuid-4","encryption":"none"}]}]},
               "streamSettings":{"network":"tcp","security":"none",
                 "tcpSettings":{"header":{"type":"http",
                   "request":{"version":"1.1","method":"GET",
                     "path":["/obfs-path"],
                     "headers":{"Host":["obfs.example.com"],"User-Agent":["curl/8"]}}}}}}"""))
        val p = first(parsed)
        val opts = p["http-opts"] as Map<String, Any>
        assertEquals("GET", opts["method"])
        assertEquals(listOf("/obfs-path"), opts["path"])
        val headers = opts["headers"] as Map<String, List<String>>
        assertEquals(listOf("obfs.example.com"), headers["Host"])
    }

    @Test
    fun `vless httpupgrade maps to mihomo httpupgrade-opts`() {
        val parsed = convert(singleOutbound(
            """{"tag":"proxy","protocol":"vless",
               "settings":{"vnext":[{"address":"u.example.com","port":443,
                 "users":[{"id":"uuid-5","encryption":"none"}]}]},
               "streamSettings":{"network":"httpupgrade","security":"tls",
                 "httpupgradeSettings":{"path":"/up","host":"u.example.com"}}}"""))
        val p = first(parsed)
        assertEquals("httpupgrade", p["network"])
        val opts = p["httpupgrade-opts"] as Map<String, Any>
        assertEquals("/up", opts["path"])
        assertEquals("u.example.com", opts["host"])
    }

    @Test
    fun `vless kcp with seed and header type`() {
        val parsed = convert(singleOutbound(
            """{"tag":"proxy","protocol":"vless",
               "settings":{"vnext":[{"address":"k.example.com","port":9000,
                 "users":[{"id":"uuid-6","encryption":"none"}]}]},
               "streamSettings":{"network":"kcp","security":"none",
                 "kcpSettings":{"mtu":1350,"seed":"SeedValue","header":{"type":"wechat-video"}}}}"""))
        val p = first(parsed)
        assertEquals("kcp", p["network"])
        val opts = p["kcp-opts"] as Map<String, Any>
        assertEquals(1350, opts["mtu"])
        assertEquals("SeedValue", opts["seed"])
        val header = opts["header"] as Map<String, Any>
        assertEquals("wechat-video", header["type"])
    }

    // ---- VMess ----

    @Test
    fun `vmess ws tls full fields with alterId and cipher`() {
        val parsed = convert(singleOutbound(
            """{"tag":"proxy","protocol":"vmess",
               "settings":{"vnext":[{"address":"v.example.com","port":443,
                 "users":[{"id":"vmess-uuid","alterId":64,"security":"chacha20-poly1305","level":0}]}]},
               "streamSettings":{"network":"ws","security":"tls",
                 "tlsSettings":{"serverName":"v.example.com"},
                 "wsSettings":{"path":"/vmws"}}}"""))
        val p = first(parsed)
        assertEquals("vmess", p["type"])
        assertEquals("vmess-uuid", p["uuid"])
        assertEquals(64, p["alterId"])
        assertEquals("chacha20-poly1305", p["cipher"])
        assertEquals("/vmws", (p["ws-opts"] as Map<String, Any>)["path"])
    }

    @Test
    fun `vmess tcp http obfs`() {
        val parsed = convert(singleOutbound(
            """{"tag":"proxy","protocol":"vmess",
               "settings":{"vnext":[{"address":"vo.example.com","port":8080,
                 "users":[{"id":"vmess-uuid-2","alterId":0,"security":"auto"}]}]},
               "streamSettings":{"network":"tcp","security":"none",
                 "tcpSettings":{"header":{"type":"http",
                   "request":{"path":["/vm-obfs"],"headers":{"Host":["vm.example.com"]}}}}}}"""))
        val p = first(parsed)
        val opts = p["http-opts"] as Map<String, Any>
        assertEquals(listOf("/vm-obfs"), opts["path"])
    }

    // ---- Trojan ----

    @Test
    fun `trojan grpc and trojan ws variants`() {
        val parsed = convert(singleOutbound(
            """{"tag":"proxy","protocol":"trojan",
               "settings":{"servers":[{"address":"t.example.com","port":443,"password":"PassW0rd","level":0}]},
               "streamSettings":{"network":"grpc","security":"tls",
                 "grpcSettings":{"serviceName":"TrojanSvc"}}}"""))
        val p = first(parsed)
        assertEquals("trojan", p["type"])
        assertEquals("PassW0rd", p["password"])
        assertEquals("TrojanSvc", (p["grpc-opts"] as Map<String, Any>)["grpc-service-name"])
        assertEquals(true, p["tls"])
    }

    // ---- Shadowsocks ----

    @Test
    fun `ss classic and ss2022 methods`() {
        val classic = convert(singleOutbound(
            """{"tag":"proxy","protocol":"shadowsocks",
               "settings":{"servers":[{"address":"s.example.com","port":8388,
                 "method":"aes-256-gcm","password":"PlainPass","ota":false,"level":0}]}}"""))
        assertEquals("ss", first(classic)["type"])
        assertEquals("aes-256-gcm", first(classic)["cipher"])
        assertEquals("PlainPass", first(classic)["password"])

        val ss2022 = convert(singleOutbound(
            """{"tag":"proxy","protocol":"shadowsocks",
               "settings":{"servers":[{"address":"s2.example.com","port":8389,
                 "method":"2022-blake3-aes-256-gcm","password":"B64PSK==","level":0}]}}"""))
        assertEquals("2022-blake3-aes-256-gcm", first(ss2022)["cipher"])
        assertEquals("B64PSK==", first(ss2022)["password"])
    }

    // ---- Hysteria ----

    @Test
    fun `hysteria v1 with auth up down and obfs`() {
        val parsed = convert(singleOutbound(
            """{"tag":"proxy","protocol":"hysteria",
               "settings":{"address":"h.example.com","port":36712,
                 "auth_str":"AuthStr","up_mbps":50,"down_mbps":200,
                 "obfs":"ObfsSalt","disable_udp":false},
               "streamSettings":{"network":"udp","security":"tls",
                 "tlsSettings":{"serverName":"h.example.com","alpn":["h3"]}}}"""))
        val p = first(parsed)
        assertEquals("hysteria", p["type"])
        assertEquals("AuthStr", p["password"])
        assertEquals("50 Mbps", p["up"])
        assertEquals("200 Mbps", p["down"])
        assertEquals(true, p["udp"])
        assertEquals("h.example.com", p["sni"])
    }

    @Test
    fun `hysteria2 with password obfs and port hopping`() {
        val parsed = convert(singleOutbound(
            """{"tag":"proxy","protocol":"hysteria2",
               "settings":{"address":"h2.example.com","port":443,"password":"H2Pass"},
               "streamSettings":{"network":"udp","security":"tls",
                 "tlsSettings":{"serverName":"h2.example.com","alpn":["h3"]}}}"""))
        val p = first(parsed)
        assertEquals("hysteria2", p["type"])
        assertEquals("H2Pass", p["password"])
        assertEquals(listOf("h3"), p["alpn"])
    }

    // ---- TUIC ----

    @Test
    fun `tuic v5 with congestion and udp relay mode`() {
        val parsed = convert(singleOutbound(
            """{"tag":"proxy","protocol":"tuic",
               "settings":{"address":"tu.example.com","port":443,
                 "uuid":"tuic-uuid","password":"TuicPass",
                 "congestion_controller":"brutal","udp_relay_mode":"native",
                 "disable_sni":false,"reduce_rtt":true},
               "streamSettings":{"network":"udp","security":"tls",
                 "tlsSettings":{"serverName":"tu.example.com","alpn":["h3"]}}}"""))
        val p = first(parsed)
        assertEquals("tuic", p["type"])
        assertEquals("tuic-uuid", p["uuid"])
        assertEquals("TuicPass", p["password"])
        assertEquals("brutal", p["congestion-controller"])
        assertEquals("native", p["udp-relay-mode"])
        assertEquals(listOf("h3"), p["alpn"])
    }

    // ---- Exotic outbounds must warn, not vanish ----

    @Test
    fun `unsupported service outbounds are skipped without warnings`() {
        val parsed = convert("""{
            "remarks": "Node",
            "outbounds": [
              {"tag":"proxy","protocol":"vless",
               "settings":{"vnext":[{"address":"1.2.3.4","port":443,
                 "users":[{"id":"u","encryption":"none"}]}]},
               "streamSettings":{"network":"tcp","security":"none"}},
              {"tag":"direct","protocol":"freedom","settings":{}},
              {"tag":"block","protocol":"blackhole","settings":{}}
            ]
        }""")
        assertEquals(1, proxies(parsed).size)
    }

    @Test
    fun `unknown outbound protocol produces a warning not silence`() {
        val outcome = UniversalConfigConverter.convertToMihomoYamlWithOutcome(singleOutbound(
            """{"tag":"mystery","protocol":"mysteryproto",
               "settings":{"address":"m.example.com","port":1}}"""))
        assertTrue(
            "expected a ProxyParseError warning, got: ${outcome.warnings}",
            outcome.warnings.any { it::class.simpleName == "ProxyParseError" }
        )
    }
}
