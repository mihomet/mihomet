package org.mihomet.app

import org.junit.Assert.*
import org.junit.Test
import org.mihomet.app.core.config.UniversalConfigConverter
import org.yaml.snakeyaml.Yaml

class UniversalConfigConverterTest {

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test Xray JSON array from Happ Incy converts to Mihomo YAML with dynamic balancer`() {
        val xrayJson = """
            [
              {
                "remarks": "🚀 Dynamic Best Server",
                "burstObservatory": {
                  "pingConfig": {
                    "destination": "http://www.gstatic.com/generate_204",
                    "interval": "600s",
                    "timeout": "6s"
                  }
                },
                "routing": {
                  "balancers": [
                    {
                      "tag": "BALANCER-MAIN",
                      "selector": ["TIER1", "TIER2"]
                    }
                  ],
                  "rules": [
                    {
                      "type": "field",
                      "ip": ["geoip:private"],
                      "outboundTag": "direct"
                    },
                    {
                      "type": "field",
                      "domain": ["domain:google.com", "regexp:.*\\.ru$"],
                      "outboundTag": "direct"
                    },
                    {
                      "type": "field",
                      "ip": ["91.108.4.0/22"],
                      "balancerTag": "BALANCER-MAIN"
                    }
                  ]
                },
                "outbounds": [
                  {
                    "tag": "TIER1",
                    "protocol": "vless",
                    "settings": {
                      "vnext": [{ "address": "lv.meow.ac", "port": 443, "users": [{ "id": "uuid1", "flow": "xtls-rprx-vision" }] }]
                    },
                    "streamSettings": {
                      "network": "tcp",
                      "security": "tls",
                      "tlsSettings": { "serverName": "lv.meow.ac", "fingerprint": "firefox" }
                    }
                  },
                  {
                    "tag": "TIER2",
                    "protocol": "vless",
                    "settings": {
                      "vnext": [{ "address": "de.meow.ac", "port": 443, "users": [{ "id": "uuid2" }] }]
                    },
                    "streamSettings": {
                      "network": "ws",
                      "wsSettings": { "path": "/ws-path", "headers": { "Host": "de.meow.ac" } },
                      "security": "reality",
                      "realitySettings": { "serverName": "de.meow.ac", "publicKey": "pubkey", "shortId": "sid" }
                    }
                  }
                ]
              },
              {
                "remarks": "🇱🇻 Node Latvia",
                "outbounds": [
                  {
                    "tag": "proxy",
                    "protocol": "vless",
                    "settings": {
                      "vnext": [{ "address": "lv.meow.ac", "port": 443, "users": [{ "id": "uuid1" }] }]
                    }
                  }
                ]
              },
              {
                "remarks": "🇩🇪 Node Germany",
                "outbounds": [
                  {
                    "tag": "proxy",
                    "protocol": "vless",
                    "settings": {
                      "vnext": [{ "address": "de.meow.ac", "port": 443, "users": [{ "id": "uuid2" }] }]
                    },
                    "streamSettings": {
                      "network": "ws",
                      "wsSettings": { "path": "/ws-path", "headers": { "Host": "de.meow.ac" } },
                      "security": "reality",
                      "realitySettings": { "serverName": "de.meow.ac", "publicKey": "pubkey", "shortId": "sid" }
                    }
                  }
                ]
              }
            ]
        """.trimIndent()

        val yamlStr = UniversalConfigConverter.convertToMihomoYaml(xrayJson)
        val yaml = Yaml()
        val parsed = yaml.load<Map<String, Any>>(yamlStr)

        // Proxies check
        val proxies = parsed["proxies"] as List<Map<String, Any>>
        assertEquals(2, proxies.size)
        assertEquals("🇱🇻 Node Latvia", proxies[0]["name"])
        assertEquals("vless", proxies[0]["type"])
        assertEquals("lv.meow.ac", proxies[0]["server"])

        assertEquals("🇩🇪 Node Germany", proxies[1]["name"])
        assertEquals("vless", proxies[1]["type"])
        assertEquals("ws", proxies[1]["network"])
        val wsOpts = proxies[1]["ws-opts"] as Map<String, Any>
        assertEquals("/ws-path", wsOpts["path"])

        // Dynamic Balancer check (taken from remarks, NOT hardcoded!)
        val groups = parsed["proxy-groups"] as List<Map<String, Any>>
        val balancerGroup = groups.first { it["type"] == "url-test" }
        assertEquals("🚀 Dynamic Best Server", balancerGroup["name"])
        assertEquals("http://www.gstatic.com/generate_204", balancerGroup["url"])
        assertEquals(600, balancerGroup["interval"])
        val balancerProxies = balancerGroup["proxies"] as List<String>
        assertTrue(balancerProxies.contains("🇱🇻 Node Latvia"))
        assertTrue(balancerProxies.contains("🇩🇪 Node Germany"))

        // Selector group check
        val selectorGroup = groups.first { it["type"] == "select" }
        assertEquals("PROXY", selectorGroup["name"])
        val selProxies = selectorGroup["proxies"] as List<String>
        assertEquals("🚀 Dynamic Best Server", selProxies[0])

        // Rules check
        val rules = parsed["rules"] as List<String>
        assertTrue(rules.any { it.contains("GEOIP,PRIVATE,DIRECT") })
        assertTrue(rules.any { it.contains("DOMAIN-SUFFIX,google.com,DIRECT") })
        assertTrue(rules.any { it.contains("DOMAIN-SUFFIX,ru,DIRECT") })
        assertTrue(rules.any { it.contains("IP-CIDR,91.108.4.0/22,PROXY,no-resolve") })
        assertTrue(rules.any { it.contains("MATCH,PROXY") })
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test Sing-Box JSON converts to Mihomo YAML with urltest and selector`() {
        val singBoxJson = """
            {
              "outbounds": [
                {
                  "type": "urltest",
                  "tag": "⚡ SingBox Auto",
                  "url": "http://cp.cloudflare.com/generate_204",
                  "interval": "5m",
                  "tolerance": 40,
                  "outbounds": ["Node Alpha", "Node Beta"]
                },
                {
                  "type": "selector",
                  "tag": "My Selector",
                  "outbounds": ["⚡ SingBox Auto", "Node Alpha", "Node Beta"]
                },
                {
                  "type": "vless",
                  "tag": "Node Alpha",
                  "server": "alpha.example.com",
                  "server_port": 443,
                  "uuid": "alpha-uuid",
                  "flow": "xtls-rprx-vision",
                  "tls": {
                    "enabled": true,
                    "server_name": "alpha.example.com",
                    "reality": {
                      "enabled": true,
                      "public_key": "alphapbk",
                      "short_id": "alphasid"
                    }
                  }
                },
                {
                  "type": "hysteria2",
                  "tag": "Node Beta",
                  "server": "beta.example.com",
                  "server_port": 8443,
                  "password": "betapassword",
                  "tls": {
                    "enabled": true,
                    "server_name": "beta.example.com"
                  }
                }
              ],
              "route": {
                "rules": [
                  {
                    "geoip": ["private"],
                    "outbound": "direct"
                  },
                  {
                    "geosite": ["category-ads-all"],
                    "outbound": "block"
                  }
                ]
              }
            }
        """.trimIndent()

        val yamlStr = UniversalConfigConverter.convertToMihomoYaml(singBoxJson)
        val yaml = Yaml()
        val parsed = yaml.load<Map<String, Any>>(yamlStr)

        val proxies = parsed["proxies"] as List<Map<String, Any>>
        assertEquals(2, proxies.size)
        assertEquals("Node Alpha", proxies[0]["name"])
        assertEquals("vless", proxies[0]["type"])
        assertEquals("alpha.example.com", proxies[0]["server"])
        val realityOpts = proxies[0]["reality-opts"] as Map<String, Any>
        assertEquals("alphapbk", realityOpts["public-key"])

        assertEquals("Node Beta", proxies[1]["name"])
        assertEquals("hysteria2", proxies[1]["type"])
        assertEquals(8443, proxies[1]["port"])

        val groups = parsed["proxy-groups"] as List<Map<String, Any>>
        val autoGroup = groups.first { it["type"] == "url-test" }
        assertEquals("⚡ SingBox Auto", autoGroup["name"])
        assertEquals("http://cp.cloudflare.com/generate_204", autoGroup["url"])
        assertEquals(300, autoGroup["interval"])
        assertEquals(40, autoGroup["tolerance"])

        val selGroup = groups.first { it["type"] == "select" }
        assertEquals("My Selector", selGroup["name"])

        val rules = parsed["rules"] as List<String>
        assertTrue(rules.contains("GEOIP,PRIVATE,DIRECT"))
        assertTrue(rules.contains("GEOSITE,category-ads-all,REJECT"))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test URI link list with vless, trojan, hysteria2, ss converts to Mihomo YAML`() {
        val uriList = """
            vless://uuid-test@example.com:443?security=reality&sni=example.com&pbk=key&sid=id#Server1
            hysteria2://password@example2.com:443?sni=example2.com#Server2
            trojan://trojanpass@example3.com:443?sni=example3.com#Server3
            ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ=@example4.com:8388#Server4
        """.trimIndent()

        val yamlStr = UniversalConfigConverter.convertToMihomoYaml(uriList)
        val yaml = Yaml()
        val parsed = yaml.load<Map<String, Any>>(yamlStr)

        val proxies = parsed["proxies"] as List<Map<String, Any>>
        assertEquals(4, proxies.size)
        assertEquals("Server1", proxies[0]["name"])
        assertEquals("vless", proxies[0]["type"])
        assertEquals("Server2", proxies[1]["name"])
        assertEquals("hysteria2", proxies[1]["type"])
        assertEquals("Server3", proxies[2]["name"])
        assertEquals("trojan", proxies[2]["type"])
        assertEquals("Server4", proxies[3]["name"])
        assertEquals("ss", proxies[3]["type"])

        val groups = parsed["proxy-groups"] as List<Map<String, Any>>
        assertEquals(1, groups.size)
        assertEquals("PROXY", groups[0]["name"])
        assertEquals("select", groups[0]["type"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test Happ routing profile converts to Mihomo YAML rules`() {
        val happJson = """
            {
              "name": "Happ AntiBlock",
              "globalProxy": true,
              "directSites": ["geosite:category-ru", "yandex.ru", "domain:gosuslugi.ru"],
              "directIp": ["geoip:ru", "192.168.1.0/24"],
              "proxySites": ["geosite:netflix", "instagram.com"],
              "blockSites": ["geosite:category-ads-all"]
            }
        """.trimIndent()

        val yamlStr = UniversalConfigConverter.convertToMihomoYaml(happJson)
        val yaml = Yaml()
        val parsed = yaml.load<Map<String, Any>>(yamlStr)

        val rules = parsed["rules"] as List<String>
        assertTrue(rules.contains("GEOSITE,CATEGORY-RU,DIRECT"))
        assertTrue(rules.contains("DOMAIN-SUFFIX,yandex.ru,DIRECT"))
        assertTrue(rules.contains("DOMAIN,gosuslugi.ru,DIRECT"))
        assertTrue(rules.contains("GEOIP,RU,DIRECT"))
        assertTrue(rules.contains("IP-CIDR,192.168.1.0/24,DIRECT,no-resolve"))
        assertTrue(rules.contains("GEOSITE,NETFLIX,GLOBAL"))
        assertTrue(rules.contains("DOMAIN-SUFFIX,instagram.com,GLOBAL"))
        assertTrue(rules.contains("GEOSITE,CATEGORY-ADS-ALL,REJECT"))
        assertTrue(rules.contains("MATCH,GLOBAL"))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test Hysteria2 URI with insecure and port hopping converts with skip-cert-verify and ports`() {
        val uri = "hysteria2://my_pass@hy2.example.com:20000-30000?insecure=1&sni=custom.sni.com&alpn=h3#Hy2Node"
        val yamlStr = UniversalConfigConverter.convertToMihomoYaml(uri)
        val yaml = Yaml()
        val parsed = yaml.load<Map<String, Any>>(yamlStr)

        val proxies = parsed["proxies"] as List<Map<String, Any>>
        assertEquals(1, proxies.size)
        val node = proxies[0]
        assertEquals("Hy2Node", node["name"])
        assertEquals("hysteria2", node["type"])
        assertEquals("hy2.example.com", node["server"])
        assertEquals("my_pass", node["password"])
        assertEquals(20000, node["port"])
        assertEquals("20000-30000", node["ports"])
        assertEquals("custom.sni.com", node["sni"])
        assertEquals(true, node["skip-cert-verify"])
        assertEquals(true, node["udp"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test SS2022 plain userinfo with iPSK and query params parses`() {
        // SS2022 panels emit raw percent-encoded cipher:psk[:ipsk] userinfo (not base64)
        val uri = "ss://2022-blake3-aes-128-gcm:A6beDiWfjeiG%2Fv48qQ1IZw%3D%3D:pwPJQHEVtilCyqP79n1kpg%3D%3D" +
                "@localhost:10012?type=tcp#Mihomet%20SS2022"
        val yamlStr = UniversalConfigConverter.convertToMihomoYaml(uri)
        val parsed = Yaml().load<Map<String, Any>>(yamlStr)

        val proxies = parsed["proxies"] as List<Map<String, Any>>
        assertEquals(1, proxies.size)
        val node = proxies[0]
        assertEquals("ss", node["type"])
        assertEquals("2022-blake3-aes-128-gcm", node["cipher"])
        assertEquals("A6beDiWfjeiG/v48qQ1IZw==:pwPJQHEVtilCyqP79n1kpg==", node["password"])
        assertEquals("localhost", node["server"])
        assertEquals(10012, node["port"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test TUIC URI converts to tuic proxy with all parameters`() {
        val uri = "tuic://uuid-abcd:my_secret@tuic.example.com:8443?" +
                "congestion_control=bbr&alpn=h3&sni=tuic.sni.com&udp_relay_mode=native&allow_insecure=1#TuicNode"
        val yamlStr = UniversalConfigConverter.convertToMihomoYaml(uri)
        val yaml = Yaml()
        val parsed = yaml.load<Map<String, Any>>(yamlStr)

        val proxies = parsed["proxies"] as List<Map<String, Any>>
        assertEquals(1, proxies.size)
        val node = proxies[0]
        assertEquals("TuicNode", node["name"])
        assertEquals("tuic", node["type"])
        assertEquals("tuic.example.com", node["server"])
        assertEquals(8443, node["port"])
        assertEquals("uuid-abcd", node["uuid"])
        assertEquals("my_secret", node["password"])
        assertEquals("bbr", node["congestion-controller"])
        assertEquals("tuic.sni.com", node["sni"])
        assertEquals("native", node["udp-relay-mode"])
        assertEquals(listOf("h3"), node["alpn"])
        assertEquals(true, node["skip-cert-verify"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test Hysteria v1 URI converts to hysteria proxy with auth and bandwidth`() {
        val uri = "hysteria://auth_str_value@hy1.example.com:443?" +
                "upmbps=100&downmbps=500&peer=hy1.sni.com&insecure=1&obfs=xplus&alpn=h3#Hy1Node"
        val yamlStr = UniversalConfigConverter.convertToMihomoYaml(uri)
        val yaml = Yaml()
        val parsed = yaml.load<Map<String, Any>>(yamlStr)

        val proxies = parsed["proxies"] as List<Map<String, Any>>
        assertEquals(1, proxies.size)
        val node = proxies[0]
        assertEquals("Hy1Node", node["name"])
        assertEquals("hysteria", node["type"])
        assertEquals("hy1.example.com", node["server"])
        assertEquals(443, node["port"])
        assertEquals("auth_str_value", node["auth-str"])
        assertEquals(100, node["up"])
        assertEquals(500, node["down"])
        assertEquals("hy1.sni.com", node["sni"])
        assertEquals(true, node["skip-cert-verify"])
        assertEquals("xplus", node["obfs"])
        assertEquals(listOf("h3"), node["alpn"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test IPv6 hosts in bracket and plain forms are parsed`() {
        val uriList = """
            vless://uuid-v6@[2001:db8::1]:443?security=tls#VlessV6
            trojan://tjpass@[::1]:9443#TrojanV6
        """.trimIndent()

        val yamlStr = UniversalConfigConverter.convertToMihomoYaml(uriList)
        val yaml = Yaml()
        val parsed = yaml.load<Map<String, Any>>(yamlStr)

        val proxies = parsed["proxies"] as List<Map<String, Any>>
        assertEquals(2, proxies.size)
        assertEquals("2001:db8::1", proxies[0]["server"])
        assertEquals(443, proxies[0]["port"])
        assertEquals("::1", proxies[1]["server"])
        assertEquals(9443, proxies[1]["port"])
    }

    @Test
    fun `test unrecognized lines produce warnings in outcome without breaking valid nodes`() {
        val uriList = """
            vless://uuid-ok@example.com:443#ValidNode
            junk-protocol://something@nowhere:1#Broken
        """.trimIndent()

        val outcome = UniversalConfigConverter.convertToMihomoYamlWithOutcome(uriList)

        assertTrue(outcome.warnings.any { it.contains("Не удалось распознать узел") })
        val yaml = Yaml()
        val parsed = yaml.load<Map<String, Any>>(outcome.yaml)
        val proxies = parsed["proxies"] as List<Map<String, Any>>
        assertEquals(1, proxies.size)
        assertEquals("ValidNode", proxies[0]["name"])
    }

    @Test
    fun `test plain convertToMihomoYaml keeps working and drops unparsable nodes silently`() {
        val uriList = """
            vless://uuid-ok@example.com:443#ValidNode
            junk-protocol://something@nowhere:1#Broken
        """.trimIndent()

        val yamlStr = UniversalConfigConverter.convertToMihomoYaml(uriList)
        val parsed = Yaml().load<Map<String, Any>>(yamlStr)
        val proxies = parsed["proxies"] as List<Map<String, Any>>
        assertEquals(1, proxies.size)
    }
}
