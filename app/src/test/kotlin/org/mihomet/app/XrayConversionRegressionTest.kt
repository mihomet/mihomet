package org.mihomet.app

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.mihomet.app.core.config.*
import org.yaml.snakeyaml.Yaml

@Suppress("UNCHECKED_CAST")
class XrayConversionRegressionTest {
    private fun node(tag: String = "proxy", user: String = "11111111-2222-4333-8444-555555555555", extra: String = ""): String =
        """{"tag":"$tag","protocol":"vless","settings":{"address":"example.com","port":443,"id":"$user"}$extra}"""
    private fun config(outbounds: String, routing: String = ""): String = """{"outbounds":[$outbounds]$routing}"""
    private fun outcome(source: String) = UniversalConfigConverter.convertToMihomoYamlWithOutcome(source)
    private fun map(source: String) = Yaml().load<Map<String, Any>>(outcome(source).yaml)
    private fun proxies(map: Map<String, Any>) = map["proxies"] as List<Map<String, Any>>

    @Test fun `outbound shape detects xray without routing or streamSettings`() {
        val source = """{"outbounds":[{"tag":"p","protocol":"socks","settings":{"servers":[{"address":"example.com","port":1080,"users":[{"user":"u","pass":"p"}]}]}}]}"""
        val result = outcome(source)
        assertEquals("xray", result.report?.format)
        val proxy = proxies(Yaml().load(result.yaml)).single()
        assertEquals("socks5", proxy["type"])
        assertEquals("u", proxy["username"])
    }

    @Test fun `jsonc comments do not corrupt URLs or quoted slash sequences`() {
        val source = "// client configuration\n" + config(node(extra = """, "streamSettings":{"network":"ws","wsSettings":{"path":"/a//b?url=https://example.com"}}"""))
            .replace("\"outbounds\":[", "\"outbounds\":[/* node */").replace("}]}", "},]}")
        val p = proxies(map(source)).single()
        assertEquals("/a//b?url=https://example.com", (p["ws-opts"] as Map<*, *>)["path"])
    }

    @Test fun `all config items survive with and without remarks`() {
        val source = "[" + config(node()).dropLast(1) + ",\"remarks\":\"Named\"}," + config(node(user = "different-user")) + "]"
        assertEquals(2, proxies(map(source)).size)
    }

    @Test fun `every vnext user is imported and same endpoint never merges different credentials`() {
        val source = """{"outbounds":[{"tag":"proxy","protocol":"vmess","settings":{"vnext":[
            {"address":"example.com","port":443,"users":[{"id":"a"},{"id":"b"}]},
            {"address":"example.net","port":8443,"users":[{"id":"c"}]}]}}]}"""
        val ps = proxies(map(source))
        assertEquals(setOf("a", "b", "c"), ps.map { it["uuid"] }.toSet())
        assertEquals(3, ps.map { it["name"] }.distinct().size)
    }

    @Test fun `duplicate tags in different Happ items resolve in their own namespace`() {
        val routing = """, "routing":{"rules":[{"domain":["full:example.net"],"outboundTag":"proxy"}]}"""
        val source = "[${config(node(user = "a"))},${config(node(user = "b"), routing)}]"
        val output = map(source)
        val second = proxies(output).first { it["uuid"] == "b" }["name"]
        assertTrue((output["rules"] as List<String>).contains("DOMAIN,example.net,$second"))
    }

    @Test fun `field rules keep AND between conditions and OR within lists`() {
        val rule = """, "routing":{"rules":[{"domain":["domain:example.com","full:example.net"],"port":"443","network":"tcp","outboundTag":"proxy"}]}"""
        val output = map(config(node(), rule))
        assertEquals("AND,((OR,((DOMAIN-SUFFIX,example.com),(DOMAIN,example.net))),(DST-PORT,443),(NETWORK,TCP)),proxy", (output["rules"] as List<String>).first())
    }

    @Test fun `unportable condition skips entire rule without broadening it`() {
        val source = config(node(), """, "routing":{"rules":[{"domain":["domain:example.com"],"protocol":["bittorrent"],"outboundTag":"proxy"}]}""")
        val result = outcome(source)
        assertEquals(1, result.report?.skippedRules)
        assertFalse(result.yaml.contains("DOMAIN-SUFFIX"))
        assertTrue(result.report!!.issues.any { it.code == ConversionIssueCode.UNSUPPORTED_RULE })
    }

    @Test fun `alternative config rules are reported separately from selected config rules`() {
        val selectedRules = (0 until 6).map {
            """{"domain":["full:main-$it.example"],"outboundTag":"proxy"}"""
        } + List(4) { """{"protocol":["bittorrent"],"outboundTag":"proxy"}""" }
        val alternativeRules = listOf(
            """{"domain":["full:alt-a.example"],"outboundTag":"alternate"}""",
            """{"domain":["full:alt-b.example"],"outboundTag":"alternate"}"""
        )
        val selected = """{"outbounds":[${node()}],"routing":{"rules":[${selectedRules.joinToString(",")}]}}"""
        val alternative = """{"outbounds":[${node("alternate", "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")}],"routing":{"rules":[${alternativeRules.joinToString(",")}]}}"""

        val result = outcome("[$selected,$alternative]")
        val report = result.report!!

        assertEquals(10, report.sourceRules)
        assertEquals(6, report.importedRules)
        assertEquals(4, report.skippedRules)
        assertEquals(2, report.alternativeRules)
        assertTrue(report.issues.any {
            it.code == ConversionIssueCode.ALTERNATIVE_RULES_NOT_MERGED && it.path == "configs[1].routing"
        })
        assertFalse(result.yaml.contains("alt-a.example"))
        assertFalse(result.yaml.contains("alt-b.example"))
    }

    @Test fun `IPv6 and regex rules remain exact`() {
        val source = config(node(), """, "routing":{"rules":[{"ip":["2001:db8::/32"],"outboundTag":"proxy"},{"domain":["regexp:^news\\.(com|net)$"],"outboundTag":"proxy"}]}""")
        val rules = map(source)["rules"] as List<String>
        assertTrue(rules.contains("IP-CIDR6,2001:db8::/32,proxy"))
        assertTrue(rules.contains("DOMAIN-REGEX,(?-i:^news\\.(com|net)$),proxy"))
    }

    @Test fun `regex quantifiers commas names and literal parentheses survive native logical syntax`() {
        val source = config(node(), """, "routing":{"rules":[
            {"domain":["regexp:^News\\(foo[)]{1,3}$","regexp:^(?P<label>www)\\.example$"],"port":"443","outboundTag":"proxy"}]}""")
        val result = outcome(source)
        assertEquals(1, result.report!!.importedRules)
        assertTrue(result.yaml.contains("\\x28"))
        assertTrue(result.yaml.contains("\\x29"))
        assertTrue(result.yaml.contains("{1,3}"))
        assertTrue(result.yaml.contains("(?<label>"))
        assertTrue(result.yaml.contains("(?-i:"))
    }

    @Test fun `balancer rules target the actual group and selectors match prefixes`() {
        val source = config(node("tier-a") + "," + node("not-tier-b", "b"), """, "routing":{"balancers":[{"tag":"fast","selector":["tier-"],"strategy":{"type":"leastPing"}}],"rules":[{"network":"tcp,udp","balancerTag":"fast"}]}""")
        val output = map(source)
        val groups = output["proxy-groups"] as List<Map<String, Any>>
        assertEquals(listOf("tier-a"), groups.first { it["name"] == "fast" }["proxies"])
        assertTrue((output["rules"] as List<String>).first().endsWith(",fast"))
    }

    @Test fun `invalid transport and cyclic or missing chains cannot produce fake usable nodes`() {
        val badTransport = outcome(config(node(extra = """, "streamSettings":{"network":"kcp"}""")))
        assertEquals("", badTransport.yaml)
        val cycle = outcome(config(node("a", extra = """, "proxySettings":{"tag":"b"}""") + "," + node("b", "b", """, "proxySettings":{"tag":"a"}""")))
        assertEquals("", cycle.yaml)
        assertTrue(cycle.report!!.issues.any { it.code == ConversionIssueCode.REFERENCE_CYCLE })
        assertEquals("", outcome(config(node(extra = """, "proxySettings":{"tag":"missing"}"""))).yaml)
    }

    @Test fun `Xray Mux is not silently translated to a different server protocol`() {
        val result = outcome(config(node(extra = """, "mux":{"enabled":true,"concurrency":8}""")))
        assertFalse(result.yaml.contains("smux"))
        assertTrue(result.report!!.issues.any { it.code == ConversionIssueCode.MUX_UNSUPPORTED })
    }

    @Test fun `first rejecting outbound remains the default instead of silently proxying`() {
        val output = map(config("""{"tag":"block","protocol":"blackhole"},${node()}"""))
        val groups = output["proxy-groups"] as List<Map<String, Any>>
        assertEquals("REJECT", (groups.first()["proxies"] as List<String>).first())
    }

    @Test fun `freedom redirect is reported as a conversion loss`() {
        val result = outcome(config("""${node()},{"tag":"direct","protocol":"freedom","settings":{"redirect":"127.0.0.1:8080"}}"""))
        assertTrue(result.report!!.issues.any { it.path.endsWith(".settings.redirect") })
    }

    @Test fun `untagged outbounds keep the first default even when direct follows it`() {
        val source = """{"outbounds":[{"protocol":"socks","settings":{"address":"example.com","port":1080}},{"protocol":"freedom"}]}"""
        val output = map(source)
        val proxyName = proxies(output).single()["name"]
        assertEquals(proxyName, ((output["proxy-groups"] as List<Map<String, Any>>).first()["proxies"] as List<String>).first())
    }

    @Test fun `commas in tags cannot inject extra native rule parameters`() {
        val source = config(node("proxy,city"), """, "routing":{"rules":[{"network":"tcp","outboundTag":"proxy,city"}]}""")
        val output = map(source)
        assertEquals("proxy，city", proxies(output).single()["name"])
        assertEquals("NETWORK,TCP,proxy，city", (output["rules"] as List<String>).first())
    }

    @Test fun `malformed optional fields in one outbound cannot discard compatible siblings`() {
        val result = outcome(config(node(extra = """, "mux":{"enabled":[]}""") +
            """,{"tag":"direct","protocol":"freedom","settings":{"domainStrategy":{}}},{"tag":"bad","protocol":"vless","settings":{"vnext":[null]}}"""))
        assertEquals(1, result.report!!.importedNodes)
        assertEquals(1, result.report!!.skippedNodes)
        assertTrue(result.report!!.issues.any { it.path.endsWith("mux.enabled") })
    }

    @Test fun `unsupported TLS trust requirements never degrade to ordinary verification`() {
        for (requirement in listOf("\"pinnedPeerCertificateChainSha256\":[\"fixture-pin\"]", "\"disableSystemRoot\":true",
            "\"certificates\":[{\"usage\":\"verify\",\"certificate\":[\"fixture-cert\"]}]", "\"certificates\":{}",
            "\"pinnedPeerCertSha256\":\"fixture-pin\"")) {
            val bad = node("pinned", extra = """, "streamSettings":{"security":"tls","tlsSettings":{$requirement}}""")
            val result = outcome(config(bad + "," + node("compatible")))
            assertEquals(requirement, 1, result.report!!.importedNodes)
            assertEquals(requirement, 1, result.report!!.skippedNodes)
        }
    }

    @Test fun `unused TLS and Reality blocks do not enable encryption by themselves`() {
        val source = config(node(extra = """, "streamSettings":{"security":"none","tlsSettings":{"serverName":"unused.example"},"realitySettings":{"publicKey":"unused"}}"""))
        val proxy = proxies(map(source)).single()
        assertNull(proxy["tls"])
        assertNull(proxy["reality-opts"])
    }

    @Test fun `bare Shadowsocks cannot silently drop an Xray TLS transport wrapper`() {
        val result = outcome(config("""{"protocol":"shadowsocks","settings":{"servers":[{"address":"example.com","port":443,"method":"aes-128-gcm","password":"fixture"}]},"streamSettings":{"security":"tls"}}"""))
        assertEquals("", result.yaml)
        assertTrue(result.report!!.issues.any { it.code == ConversionIssueCode.UNSUPPORTED_TRANSPORT })
    }

    @Test fun `HTTPS proxy keeps explicitly allowed self-signed TLS and SNI`() {
        val result = map(config("""{"protocol":"http","settings":{"address":"203.0.113.1","port":443},"streamSettings":{"security":"tls","tlsSettings":{"serverName":"fixture.invalid","allowInsecure":true}}}"""))
        val proxy = proxies(result).single()
        assertEquals("fixture.invalid", proxy["sni"])
        assertEquals(true, proxy["skip-cert-verify"])
    }

    @Test fun `Base64 wrapped UTF8 BOM is normalized before JSON detection`() {
        val source = java.util.Base64.getEncoder().encodeToString(("\uFEFF" + config(node())).toByteArray())
        assertEquals(1, outcome(source).report!!.importedNodes)
    }

    @Test fun `invalid IPv6 cannot poison a profile but mapped IPv6 is preserved`() {
        val result = outcome(config(node(), """, "routing":{"rules":[{"ip":["2001:db8:bad"],"outboundTag":"proxy"},{"ip":["::ffff:192.0.2.1/128"],"outboundTag":"proxy"}]}"""))
        assertEquals(1, result.report!!.skippedRules)
        assertTrue(result.yaml.contains("IP-CIDR6,::ffff:192.0.2.1/128,proxy"))
    }

    @Test fun `persistent reports never retain source credentials or parser excerpts`() {
        val secret = "SUPER_SECRET_credential_12345"
        val result = outcome(config(node(user = secret, extra = """, "streamSettings":{"network":"tcp","unknownOption":"$secret"}""")))
        val report = result.report!!
        assertFalse(report.toJson().contains(secret))
        assertEquals(report, ConversionReport.fromJson(report.toJson()))
        assertFalse(outcome("""{"outbounds":[{"protocol":"vless","settings":"$secret"}]}""").report!!.toJson().contains(secret))
        assertNull(ConversionReport.fromJson("broken"))
    }

    @Test fun `older conversion reports default alternative rule count to zero`() {
        val legacy = """{"format":"xray","sourceRules":3,"importedRules":2,"issues":[]}"""
        assertEquals(0, ConversionReport.fromJson(legacy)?.alternativeRules)
    }

    @Test fun `official public XTLS fixtures yield supported nodes or an explicit unsupported transport report`() {
        val names = listOf("vless-reality", "vless-xhttp", "vmess-ws", "vless-grpc", "trojan-tls", "vless-kcp-unsupported")
        for (name in names) {
            val source = javaClass.getResourceAsStream("/xray-public/$name.jsonc")!!.bufferedReader().use { it.readText() }
            val result = outcome(source)
            assertEquals(name, "xray", result.report?.format)
            if (name.endsWith("unsupported")) assertTrue(result.report!!.issues.any { it.code == ConversionIssueCode.UNSUPPORTED_TRANSPORT })
            else assertTrue("$name: ${result.warnings}", result.report!!.importedNodes > 0)
        }
    }
    @Test fun `Trojan uses the actual core sni key instead of ignored servername`() {
        val result = map(config("""{"tag":"proxy","protocol":"trojan","settings":{"servers":[{"address":"203.0.113.1","port":443,"password":"fixture"}]},
            "streamSettings":{"security":"tls","tlsSettings":{"serverName":"example.com"}}}"""))
        assertEquals("example.com", proxies(result).single()["sni"])
        assertNull(proxies(result).single()["servername"])
    }

    @Test fun `invalid Reality keys and unsupported extra server verification are not weakened`() {
        val stream = """, "streamSettings":{"security":"reality","realitySettings":{"serverName":"example.com","publicKey":"invalid"}}"""
        assertEquals("", outcome(config(node(extra = stream))).yaml)
        val secure = stream.replace("invalid", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
            .replace("\"serverName\":", "\"mldsa65Verify\":\"signature-key\",\"serverName\":")
        assertEquals("", outcome(config(node(extra = secure))).yaml)
    }

    @Test fun `Xray and URI Reality retain modern key shares and a default client fingerprint`() {
        val key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val json = config(node(extra = """, "streamSettings":{"security":"reality","realitySettings":{"publicKey":"$key","shortId":"ab12"}}"""))
        val uri = "vless://11111111-2222-4333-8444-555555555555@example.com:443?security=reality&pbk=$key&sid=ab12"
        for (source in listOf(json, uri)) {
            val proxy = proxies(map(source)).single()
            assertEquals("chrome", proxy["client-fingerprint"])
            val options = proxy["reality-opts"] as Map<*, *>
            assertEquals(key.trimEnd('='), options["public-key"])
            assertEquals(true, options["support-x25519mlkem768"])
        }
    }

    @Test fun `case insensitive Trojan protocol expands every server`() {
        val source = config("""{"protocol":"Trojan","settings":{"servers":[
            {"address":"a.example","port":443,"password":"a"},{"address":"b.example","port":443,"password":"b"}]}}""")
        assertEquals(setOf("a.example", "b.example"), proxies(map(source)).map { it["server"] }.toSet())
    }

    @Test fun `method overrides network as in Xray and unused transport blocks stay inactive`() {
        val source = config(node(extra = """, "streamSettings":{"network":"kcp","method":"RAW",
            "xhttpSettings":{"extra":{"downloadSettings":false}},"grpcSettings":{"multiMode":[]}}"""))
        val proxy = proxies(map(source)).single()
        assertEquals("tcp", proxy["network"])
        assertNull(proxy["xhttp-opts"])
    }

    @Test fun `XHTTP extra replaces optional fields but retains outer host path and mode`() {
        val source = config(node(extra = """, "streamSettings":{"network":"xhttp","xhttpSettings":{
            "host":"outer.example","path":"/outer","mode":"stream-up","xPaddingBytes":"1-2",
            "extra":{"host":"ignored.example","path":"/ignored","mode":"stream-one","sessionIDKey":"sid","sessionIDPlacement":"header"}}}"""))
        val options = proxies(map(source)).single()["xhttp-opts"] as Map<*, *>
        assertEquals("outer.example", options["host"])
        assertEquals("/outer", options["path"])
        assertEquals("stream-up", options["mode"])
        assertNull(options["x-padding-bytes"])
        assertEquals("sid", options["session-key"])
        assertEquals("header", options["session-placement"])
    }

    private fun downloadConfig(security: String = "none", fields: String = "", network: String = "xhttp"): String =
        config(node(extra = """, "streamSettings":{"network":"xhttp","security":"tls","tlsSettings":{"allowInsecure":true},
            "xhttpSettings":{"path":"/upload","headers":{"X-Upload":"only"},"extra":{"downloadSettings":{
                "address":"download.example","port":8443,"network":"$network","security":"$security"$fields}}}}"""))

    private fun downloadOptions(source: String): Map<*, *> =
        (proxies(map(source)).single()["xhttp-opts"] as Map<*, *>)["download-settings"] as Map<*, *>

    @Test fun `separate XHTTP download never inherits upload TLS headers path or certificate bypass`() {
        val options = downloadOptions(downloadConfig(fields = """, "tlsSettings":{"allowInsecure":true,"certificates":["inactive"]},"realitySettings":{"publicKey":"inactive"}"""))
        assertEquals(false, options["tls"])
        assertEquals(false, options["skip-cert-verify"])
        assertEquals("/", options["path"])
        assertEquals(emptyMap<String, String>(), options["headers"])
        assertEquals(mapOf("public-key" to ""), options["reality-opts"])
        assertEquals(6, (options["reuse-settings"] as Map<*, *>).size)
    }

    @Test fun `separate XHTTP TLS preserves its fingerprint and reports unportable options`() {
        val source = downloadConfig("tls", """, "tlsSettings":{"serverName":"cdn.example","fingerprint":"firefox","alpn":["h2"],"enableSessionResumption":true},
            "xhttpSettings":{"path":"/download","mode":"stream-one"}""")
        val options = downloadOptions(source)
        assertEquals(true, options["tls"])
        assertEquals(false, options["skip-cert-verify"])
        assertEquals("firefox", options["client-fingerprint"])
        assertEquals("cdn.example", options["servername"])
        assertEquals(listOf("h2"), options["alpn"])
        assertEquals("/download", options["path"])
        val issues = outcome(source).report!!.issues
        assertTrue(issues.any { it.path.endsWith("downloadSettings.tlsSettings.enableSessionResumption") })
        assertTrue(issues.any { it.path.endsWith("downloadSettings.xhttpSettings.mode") })
    }

    @Test fun `download transport and trust mismatches reject only the affected node`() {
        val sources = listOf(downloadConfig(network = "ws"), downloadConfig("xtls"),
            downloadConfig("tls", """, "tlsSettings":{"pinnedPeerCertSha256":"fixture-pin"}"""),
            downloadConfig("reality", """, "realitySettings":{"publicKey":"invalid"}"""))
        for (source in sources) {
            val root = Json.parseToJsonElement(source).jsonObject
            val withSibling = JsonObject(root + ("outbounds" to JsonArray(root.getValue("outbounds").jsonArray + Json.parseToJsonElement(node("compatible")))))
            val result = outcome(withSibling.toString())
            assertEquals(1, result.report!!.importedNodes)
            assertEquals(1, result.report!!.skippedNodes)
        }
    }

    @Test fun `Xray WireGuard peers preserve endpoints addresses and the core preshared key spelling`() {
        val source = config("""{"tag":"wg","protocol":"wireguard","settings":{"secretKey":"private-key",
            "address":["10.0.0.2/32","fd00::2/128"],"mtu":1400,
            "peers":[{"endpoint":"[2001:db8::1]:51820","publicKey":"public-key","preSharedKey":"psk","allowedIPs":["0.0.0.0/0"]}]}}""")
        val proxy = proxies(map(source)).single()
        assertEquals("2001:db8::1", proxy["server"])
        assertEquals("10.0.0.2", proxy["ip"])
        assertEquals("fd00::2", proxy["ipv6"])
        assertEquals("psk", proxy["pre-shared-key"])
    }

}
