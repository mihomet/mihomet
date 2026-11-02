package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.core.config.UriListParser
import org.mihomet.app.core.config.ConversionWarning

/**
 * Maximal-complexity URI matrix: every share-link scheme with ALL parameters
 * panels emit (v2rayNG/Happ/NekoBox conventions). Each assertion pins one mapped
 * mihomo field.
 */
class UriMatrixTest {

    private fun parse(uri: String): Map<String, Any>? =
        UriListParser.parseUriToMihomoProxy(uri, mutableListOf())

    private fun warnings(uri: String): List<ConversionWarning> {
        val w = mutableListOf<ConversionWarning>()
        UriListParser.parseUriToMihomoProxy(uri, w)
        return w
    }

    // ---- VLESS ----

    @Test
    fun `vless xhttp uri with path host and mode`() {
        val p = parse("vless://uuid-1@x.example.com:443?encryption=none&security=tls" +
                "&sni=x.example.com&type=xhttp&path=%2Fxpath&host=x.example.com&mode=auto#XNode")
        assertNotNull(p)
        assertEquals("xhttp", p!!["network"])
        val opts = p["xhttp-opts"] as Map<String, Any>
        assertEquals("/xpath", opts["path"])
        assertEquals("x.example.com", opts["host"])
        assertEquals("auto", opts["mode"])
    }

    @Test
    fun `vless httpupgrade uri`() {
        val p = parse("vless://uuid-2@u.example.com:443?encryption=none&security=tls" +
                "&type=httpupgrade&path=%2Fup&host=u.example.com#UNode")
        assertEquals("httpupgrade", p!!["network"])
        assertEquals("/up", (p["httpupgrade-opts"] as Map<String, Any>)["path"])
    }

    @Test
    fun `vless kcp uri with seed and header`() {
        val p = parse("vless://uuid-3@k.example.com:9000?encryption=none" +
                "&type=kcp&seed=KcpSeed&headerType=wechat-video#KNode")
        assertEquals("kcp", p!!["network"])
        val opts = p["kcp-opts"] as Map<String, Any>
        assertEquals("KcpSeed", opts["seed"])
        assertEquals("wechat-video", (opts["header"] as Map<String, Any>)["type"])
    }

    @Test
    fun `vless tcp with headerType http obfs`() {
        val p = parse("vless://uuid-4@o.example.com:80?encryption=none&security=none" +
                "&type=tcp&headerType=http&path=%2Fobfs&host=obfs.example.com#ONode")
        val opts = p!!["http-opts"] as Map<String, Any>
        assertEquals(listOf("/obfs"), opts["path"])
        val headers = opts["headers"] as Map<String, List<String>>
        assertEquals(listOf("obfs.example.com"), headers["Host"])
    }

    @Test
    fun `vless grpc uri with serviceName`() {
        val p = parse("vless://uuid-5@g.example.com:443?encryption=none&security=tls" +
                "&type=grpc&serviceName=GrpcSvc#GNode")
        assertEquals("GrpcSvc", (p!!["grpc-opts"] as Map<String, Any>)["grpc-service-name"])
    }

    @Test
    fun `vless ws uri with ed and eh early data`() {
        val p = parse("vless://uuid-6@w.example.com:443?encryption=none&security=tls" +
                "&sni=w.example.com&type=ws&path=%2Fws%3Fed%3D2048&host=w.example.com" +
                "&ed=2048&eh=Sec-WebSocket-Protocol#WNode")
        val ws = p!!["ws-opts"] as Map<String, Any>
        assertEquals(2048, ws["max-early-data"])
        assertEquals("Sec-WebSocket-Protocol", ws["early-data-header-name"])
    }

    // ---- VMess ----

    @Test
    fun `vmess base64 uri with ws tls sni and cipher`() {
        val json = """{"v":"2","ps":"VMess Node","add":"vm.example.com","port":"443",
            "id":"83999090-86f8-4009-a889-746a492638d1","aid":"8","scy":"aes-128-gcm",
            "net":"ws","type":"none","host":"vm.example.com","path":"/vmpath",
            "tls":"tls","sni":"sni.example.com"}"""
        val uri = "vmess://" + java.util.Base64.getEncoder().encodeToString(json.toByteArray())
        val p = parse(uri)
        assertNotNull(p)
        assertEquals("VMess Node", p!!["name"])
        assertEquals("aes-128-gcm", p["cipher"])
        assertEquals(8, p["alterId"])
        assertEquals("/vmpath", (p["ws-opts"] as Map<String, Any>)["path"])
        assertEquals("sni.example.com", p["servername"])
    }

    // ---- Shadowsocks ----

    @Test
    fun `ss2022 sip002 uri with base64 userinfo`() {
        val userinfo = java.util.Base64.getEncoder().encodeToString(
            "2022-blake3-aes-256-gcm:PSK1:PSK2".toByteArray())
        val p = parse("ss://$userinfo@s2.example.com:8389#SS22")
        assertNotNull(p)
        assertEquals("ss", p!!["type"])
        assertEquals("2022-blake3-aes-256-gcm", p["cipher"])
        assertEquals("PSK1:PSK2", p["password"])
    }

    @Test
    fun `ss classic plain userinfo uri`() {
        val p = parse("ss://aes-256-gcm:PlainPass@s1.example.com:8388#SSClassic")
        assertNotNull(p)
        assertEquals("aes-256-gcm", p!!["cipher"])
        assertEquals("PlainPass", p["password"])
    }

    // ---- Hysteria2 ----

    @Test
    fun `hysteria2 uri with mport obfs and insecure`() {
        val p = parse("hysteria2://H2Pass@h2.example.com:443?mport=20000-30000" +
                "&obfs=salamander&obfs-password=ObfsPass&sni=h2.example.com&insecure=1#H2")
        assertNotNull(p)
        assertEquals("20000-30000", p!!["ports"])
        assertEquals("salamander", p["obfs"])
        assertEquals("ObfsPass", p["obfs-password"])
        assertEquals(true, p["skip-cert-verify"])
    }

    @Test
    fun `hy2 short scheme with port range in authority`() {
        val p = parse("hy2://H2Pass@h2.example.com:20000-30000?sni=h2.example.com#H2R")
        assertEquals("20000-30000", p!!["ports"])
        assertEquals(20000, p["port"])
    }

    // ---- TUIC ----

    @Test
    fun `tuic v5 uri full params`() {
        val p = parse("tuic://tu-uuid:TuPass@tu.example.com:443?" +
                "congestion_control=brutal&udp_relay_mode=native&alpn=h3" +
                "&sni=tu.example.com&allow_insecure=1#TUIC")
        assertNotNull(p)
        assertEquals("brutal", p!!["congestion-controller"])
        assertEquals("native", p["udp-relay-mode"])
        assertEquals(listOf("h3"), p["alpn"])
        assertEquals(true, p["skip-cert-verify"])
    }

    // ---- Snell ----

    @Test
    fun `snell uri with psk version and obfs`() {
        val p = parse("snell://psk-value@sn.example.com:28862?psk=RealPsk&version=4&obfs=http&obfs-host=sn.example.com#SN")
        assertNotNull(p)
        assertEquals("snell", p!!["type"])
        assertEquals("RealPsk", p["psk"])
        assertEquals("4", p["version"])
        assertEquals(mapOf("mode" to "http", "host" to "sn.example.com"), p["obfs-opts"])
    }

    // ---- Unrecognized schemes warn, never vanish ----

    @Test
    fun `unknown scheme produces a warning`() {
        val w = warnings("mystery://whatever@example.com:1#M")
        assertTrue("expected an unrecognized-node warning, got: $w", w.isNotEmpty())
    }

    @Test
    fun `malformed vless produces a warning`() {
        val w = warnings("vless://@:0#broken")
        assertTrue(w.isNotEmpty())
        assertNull(parse("vless://@:0#broken"))
    }
}
