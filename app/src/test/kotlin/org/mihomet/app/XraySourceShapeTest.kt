package org.mihomet.app

import org.junit.Assert.*
import org.junit.Test
import org.mihomet.app.core.config.ConversionIssueCode
import org.mihomet.app.core.config.UniversalConfigConverter
import org.yaml.snakeyaml.Yaml

@Suppress("UNCHECKED_CAST")
class XraySourceShapeTest {
    private val good = """{"tag":"good","protocol":"vless","settings":{"address":"example.com","port":443,"id":"11111111-2222-4333-8444-555555555555"}}"""
    private fun convert(source: String) = UniversalConfigConverter.convertToMihomoYamlWithOutcome(source)
    private fun config(nodes: String) = """{"outbounds":[$nodes]}"""
    private fun proxies(source: String): List<Map<String, Any>> = Yaml().load<Map<String, Any>>(convert(source).yaml)["proxies"] as List<Map<String, Any>>

    @Test fun `modern flat protocols preserve credentials and HTTP headers`() {
        for (protocol in listOf("http", "socks", "trojan", "shadowsocks")) {
            val source = config("""{"protocol":"$protocol","settings":{"address":"example.com","port":443,
                "user":"alice","pass":"secret","password":"secret","method":"aes-128-gcm","headers":{"X-Fixture":"retained"}}}""")
            val proxy = proxies(source).single()
            assertEquals(protocol, "example.com", proxy["server"])
            assertEquals(protocol, "secret", proxy["password"])
            if (protocol in setOf("http", "socks")) assertEquals("alice", proxy["username"])
            if (protocol == "http") assertEquals(mapOf("X-Fixture" to "retained"), proxy["headers"])
        }
    }

    @Test fun `WS host overrides legacy case insensitive Host header and keeps other headers`() {
        val source = good.dropLast(1) + """, "streamSettings":{"network":"ws","wsSettings":{
            "host":"cdn.example","path":"/path?ed=2048","headers":{"host":"legacy.example","X-Fixture":"kept"}}}}"""
        val opts = proxies(config(source)).single()["ws-opts"] as Map<*, *>
        assertEquals(mapOf("Host" to "cdn.example", "X-Fixture" to "kept"), opts["headers"])
        assertEquals("/path?ed=2048", opts["path"])
    }

    @Test fun `gRPC custom user agent maps even without a service name`() {
        val source = good.dropLast(1) + """, "streamSettings":{"network":"grpc","grpcSettings":{"user_agent":"fixture-agent"}}}"""
        val opts = proxies(config(source)).single()["grpc-opts"] as Map<*, *>
        assertEquals("fixture-agent", opts["grpc-user-agent"])
    }

    @Test fun `malformed servers users and outbounds are counted without discarding siblings`() {
        val source = config("""null,{"protocol":"vmess","settings":{"vnext":[null,
            {"address":"example.com","port":443,"users":[null,{"id":"a"},{"id":"b"}]}]}}""")
        val report = convert(source).report!!
        assertEquals(5, report.sourceNodes)
        assertEquals(2, report.importedNodes)
        assertEquals(3, report.skippedNodes)
        assertEquals(3, report.issues.count { it.code == ConversionIssueCode.INVALID_NODE })
    }

    @Test fun `malformed active stream shapes do not silently become unencrypted TCP`() {
        for (stream in listOf("[]", """{"network":[]}""", """{"network":"ws","wsSettings":[]}""",
            """{"security":"tls","tlsSettings":[]}""")) {
            val bad = good.dropLast(1) + """, "streamSettings":$stream}"""
            val report = convert(config("$good,$bad")).report!!
            assertEquals(stream, 1, report.importedNodes)
            assertEquals(stream, 1, report.skippedNodes)
        }
    }

    @Test fun `malformed Happ config entries and outbound array entries remain visible`() {
        for (source in listOf("[$good,null]", "[${config(good)},null]")) {
            val report = convert(source).report!!
            assertEquals(1, report.importedNodes)
            assertEquals(1, report.skippedNodes)
        }
    }

    @Test fun `unmapped settings and active transport parameters have credential free reports`() {
        val secret = "SECRET_KEY_MUST_NOT_BE_STORED"
        val source = good.replace("\"id\":", "\"$secret\":\"$secret\",\"id\":").dropLast(1) +
            """, "streamSettings":{"network":"grpc","$secret":"$secret","grpcSettings":{"authority":"$secret"}}}"""
        val report = convert(config(source)).report!!
        assertEquals(1, report.importedNodes)
        assertTrue(report.issues.any { it.path.endsWith("settings.unmapped") })
        assertTrue(report.issues.any { it.path.endsWith("grpcSettings.unmapped") })
        assertFalse(report.toJson().contains(secret))
    }

    @Test fun `malformed rule lists and empty domain conditions cannot poison the native config`() {
        val source = config(good).dropLast(1) + """, "routing":{"rules":[
            {"domain":["full:example.com",null],"outboundTag":"good"},
            {"domain":["full:"],"outboundTag":"good"},
            {"domain":["full:bad(host"],"outboundTag":"good"},
            {"domain":true,"outboundTag":"good"},
            {"port":443,"outboundTag":"good"}]}}"""
        val result = convert(source)
        assertEquals(4, result.report!!.skippedRules)
        assertEquals(1, result.report.importedRules)
        assertTrue(result.yaml.contains("DST-PORT,443,good"))
    }

    @Test fun `flat HTTP user arrays preserve all valid identities and report malformed identities`() {
        val source = config("""{"protocol":"http","settings":{"address":"example.com","port":443,
            "users":[{"user":"alice","pass":"a"},null,{"user":"bob","pass":"b"}]}}""")
        assertEquals(setOf("alice", "bob"), proxies(source).map { it["username"] }.toSet())
        assertEquals(1, convert(source).report!!.skippedNodes)
    }
}
