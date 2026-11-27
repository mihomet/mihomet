package org.mihomet.app.core.config

import kotlinx.serialization.json.*

internal object XrayOutboundParser {
    fun parse(
        name: String,
        protocol: String,
        settings: JsonObject,
        streamSettings: JsonObject
    ): Map<String, Any>? {
        // Newer Xray (24.x+) renamed network→method and tcp→raw; panels emit both
        val netRaw = XrayTransportOptions.network(streamSettings)
        val net = when (netRaw) {
            "raw" -> "tcp"
            "websocket" -> "ws"
            else -> netRaw
        }
        val security = streamSettings["security"]?.jsonPrimitive?.contentOrNull ?: ""

        return when (protocol.lowercase()) {
            "vless" -> {
                // Standard Xray form: settings.vnext[i] = {address, port, users:[{id, flow}]},
                // plus the flat 3x-ui export form: settings = {address, port, id, flow} directly.
                val vnextItem = (settings["vnext"] as? JsonArray)?.firstOrNull() as? JsonObject
                val record: JsonObject? =
                    (vnextItem?.get("users") as? JsonArray)?.firstOrNull() as? JsonObject
                val address = (vnextItem?.get("address") ?: settings["address"])?.jsonPrimitive?.contentOrNull
                    ?: return null
                val port = (vnextItem?.get("port") ?: settings["port"])?.jsonPrimitive?.intOrNull ?: 443
                val uuid = (record?.get("id") ?: settings["id"])?.jsonPrimitive?.contentOrNull ?: return null
                val flow = (record?.get("flow") ?: settings["flow"])?.jsonPrimitive?.contentOrNull ?: ""

                val map = mutableMapOf<String, Any>(
                    "name" to name,
                    "type" to "vless",
                    "server" to address,
                    "port" to port,
                    "uuid" to uuid,
                    "network" to if (net == "splithttp") "xhttp" else net
                )
                val encryption = (record?.get("encryption") ?: settings["encryption"])?.jsonPrimitive?.contentOrNull
                if (encryption != null && encryption != "none") map["encryption"] = encryption
                if (flow.isNotBlank()) map["flow"] = flow
                applyStreamSecurityAndTransport(map, net, security, streamSettings, address)
                map
            }
            "hysteria" -> {
                // mihomo supports hysteria v1 natively — do NOT mislabel it as hysteria2.
                val address = settings["address"]?.jsonPrimitive?.contentOrNull
                    ?: (settings["servers"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("address")?.jsonPrimitive?.contentOrNull
                    ?: return null
                val port = settings["port"]?.jsonPrimitive?.intOrNull
                    ?: (settings["servers"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("port")?.jsonPrimitive?.intOrNull
                    ?: 443
                val auth = settings["auth"]?.jsonPrimitive?.contentOrNull
                    ?: settings["auth_str"]?.jsonPrimitive?.contentOrNull
                    ?: settings["password"]?.jsonPrimitive?.contentOrNull
                    ?: (settings["servers"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("password")?.jsonPrimitive?.contentOrNull
                    ?: ""
                val up = settings["up"]?.jsonPrimitive?.contentOrNull
                    ?: settings["up_mbps"]?.jsonPrimitive?.intOrNull?.let { "$it Mbps" }
                    ?: "50 Mbps"
                val down = settings["down"]?.jsonPrimitive?.contentOrNull
                    ?: settings["down_mbps"]?.jsonPrimitive?.intOrNull?.let { "$it Mbps" }
                    ?: "100 Mbps"
                val tlsSettings = streamSettings["tlsSettings"] as? JsonObject ?: JsonObject(emptyMap())

                val map = mutableMapOf<String, Any>(
                    "name" to name,
                    "type" to "hysteria",
                    "server" to address,
                    "port" to port,
                    "udp" to true,
                    "up" to up,
                    "down" to down
                )
                if (auth.isNotBlank()) map["password"] = auth
                val sni = tlsSettings["serverName"]?.jsonPrimitive?.contentOrNull ?: address
                if (sni.isNotBlank() && !sni.matches(Regex("^[0-9.]+$"))) map["sni"] = sni
                map["alpn"] = listOf("h3")
                val allowInsecure = tlsSettings["allowInsecure"]?.jsonPrimitive?.booleanOrNull == true
                if (allowInsecure) map["skip-cert-verify"] = true
                map
            }
            "hysteria2" -> {
                val address = settings["address"]?.jsonPrimitive?.contentOrNull
                    ?: (settings["servers"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("address")?.jsonPrimitive?.contentOrNull
                    ?: return null
                val port = settings["port"]?.jsonPrimitive?.intOrNull
                    ?: (settings["servers"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("port")?.jsonPrimitive?.intOrNull
                    ?: 443
                val hysteriaSettings = streamSettings["hysteriaSettings"] as? JsonObject ?: JsonObject(emptyMap())
                val auth = hysteriaSettings["auth"]?.jsonPrimitive?.contentOrNull
                    ?: settings["password"]?.jsonPrimitive?.contentOrNull
                    ?: (settings["servers"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("password")?.jsonPrimitive?.contentOrNull
                    ?: ""
                val tlsSettings = streamSettings["tlsSettings"] as? JsonObject ?: JsonObject(emptyMap())

                val map = mutableMapOf<String, Any>(
                    "name" to name,
                    "type" to "hysteria2",
                    "server" to address,
                    "port" to port,
                    "password" to auth,
                    "udp" to true
                )
                val sni = tlsSettings["serverName"]?.jsonPrimitive?.contentOrNull ?: address
                if (sni.isNotBlank() && !sni.matches(Regex("^[0-9.]+$"))) map["sni"] = sni
                map["alpn"] = listOf("h3")

                val allowInsecure = tlsSettings["allowInsecure"]?.jsonPrimitive?.booleanOrNull == true
                if (allowInsecure) map["skip-cert-verify"] = true
                map
            }
            "vmess" -> {
                // Standard vnext[] form + flat 3x-ui export form (see vless note).
                val vnextItem = (settings["vnext"] as? JsonArray)?.firstOrNull() as? JsonObject
                val record: JsonObject? =
                    (vnextItem?.get("users") as? JsonArray)?.firstOrNull() as? JsonObject
                val address = (vnextItem?.get("address") ?: settings["address"])?.jsonPrimitive?.contentOrNull
                    ?: return null
                val port = (vnextItem?.get("port") ?: settings["port"])?.jsonPrimitive?.intOrNull ?: 443
                val uuid = (record?.get("id") ?: settings["id"])?.jsonPrimitive?.contentOrNull ?: return null
                val alterId = (record?.get("alterId") ?: settings["alterId"])?.jsonPrimitive?.intOrNull ?: 0
                val cipher = (record?.get("security") ?: settings["security"])?.jsonPrimitive?.contentOrNull
                    ?: "auto"

                val map = mutableMapOf<String, Any>(
                    "name" to name,
                    "type" to "vmess",
                    "server" to address,
                    "port" to port,
                    "uuid" to uuid,
                    "alterId" to alterId,
                    "cipher" to cipher,
                    "network" to if (net == "splithttp") "xhttp" else net
                )
                applyStreamSecurityAndTransport(map, net, security, streamSettings, address)
            map
            }
            "shadowsocks" -> {
                val srv = (settings["servers"] as? JsonArray)?.firstOrNull() as? JsonObject ?: settings
                val address = srv["address"]?.jsonPrimitive?.contentOrNull ?: return null
                val port = srv["port"]?.jsonPrimitive?.intOrNull ?: return null
                val method = srv["method"]?.jsonPrimitive?.contentOrNull ?: return null
                val password = srv["password"]?.jsonPrimitive?.contentOrNull ?: return null

                mutableMapOf(
                    "name" to name,
                    "type" to "ss",
                    "server" to address,
                    "port" to port,
                    "cipher" to method,
                    "password" to password
                )
            }
            "trojan" -> {
                val srv = (settings["servers"] as? JsonArray)?.firstOrNull() as? JsonObject ?: settings
                val address = srv["address"]?.jsonPrimitive?.contentOrNull ?: return null
                val port = srv["port"]?.jsonPrimitive?.intOrNull ?: 443
                val password = srv["password"]?.jsonPrimitive?.contentOrNull ?: return null

                val map = mutableMapOf<String, Any>(
                    "name" to name,
                    "type" to "trojan",
                    "server" to address,
                    "port" to port,
                    "password" to password,
                    "network" to if (net == "splithttp") "xhttp" else net
                )
                applyStreamSecurityAndTransport(map, net, security, streamSettings, address)
            map
            }
            "http" -> {
                // Xray http outbound -> mihomo http proxy (with optional basic auth).
                val address = settings["address"]?.jsonPrimitive?.contentOrNull ?: return null
                val port = settings["port"]?.jsonPrimitive?.intOrNull ?: 8080
                val map = mutableMapOf<String, Any>(
                    "name" to name,
                    "type" to "http",
                    "server" to address,
                    "port" to port
                )
                val users = settings["users"] as? JsonArray
                val user = users?.firstOrNull() as? JsonObject ?: settings
                user["user"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }?.let { map["username"] = it }
                user["pass"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }?.let { map["password"] = it }
                val tlsSettings = streamSettings["tlsSettings"] as? JsonObject ?: JsonObject(emptyMap())
                if (security == "tls") {
                    map["tls"] = true
                    val sni = tlsSettings["serverName"]?.jsonPrimitive?.contentOrNull ?: address
                    if (sni.isNotBlank() && !sni.matches(Regex("^[0-9.]+$"))) map["sni"] = sni
                }
                (settings["headers"] as? JsonObject)?.let { headers ->
                    map["headers"] = headers.mapValues { it.value.jsonPrimitive.content }
                }
                map
            }
            "socks" -> {
                // Xray socks outbound -> mihomo socks5.
                val address = settings["address"]?.jsonPrimitive?.contentOrNull ?: return null
                val port = settings["port"]?.jsonPrimitive?.intOrNull ?: 1080
                val map = mutableMapOf<String, Any>(
                    "name" to name,
                    "type" to "socks5",
                    "server" to address,
                    "port" to port
                )
                val users = settings["users"] as? JsonArray
                val user = users?.firstOrNull() as? JsonObject ?: settings
                user["user"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }?.let { map["username"] = it }
                user["pass"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }?.let { map["password"] = it }
                map
            }
            "tuic" -> {
                val address = settings["address"]?.jsonPrimitive?.contentOrNull ?: return null
                val port = settings["port"]?.jsonPrimitive?.intOrNull ?: 443
                val uuid = settings["uuid"]?.jsonPrimitive?.contentOrNull ?: ""
                val password = settings["password"]?.jsonPrimitive?.contentOrNull ?: ""
                val congestion = settings["congestion_controller"]?.jsonPrimitive?.contentOrNull ?: "bbr"
                val tlsSettings = streamSettings["tlsSettings"] as? JsonObject ?: JsonObject(emptyMap())

                val map = mutableMapOf<String, Any>(
                    "name" to name,
                    "type" to "tuic",
                    "server" to address,
                    "port" to port,
                    "uuid" to uuid,
                    "password" to password,
                    "congestion-controller" to congestion
                )
                settings["udp_relay_mode"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }?.let { map["udp-relay-mode"] = it }
                if (settings["reduce_rtt"]?.jsonPrimitive?.booleanOrNull == true) map["reduce-rtt"] = true
                if (settings["disable_sni"]?.jsonPrimitive?.booleanOrNull == true) map["disable-sni"] = true
                val sni = tlsSettings["serverName"]?.jsonPrimitive?.contentOrNull ?: address
                if (sni.isNotBlank() && !sni.matches(Regex("^[0-9.]+$"))) map["sni"] = sni
                val alpn = tlsSettings["alpn"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                if (!alpn.isNullOrEmpty()) map["alpn"] = alpn
                val allowInsecure = tlsSettings["allowInsecure"]?.jsonPrimitive?.booleanOrNull == true
                if (allowInsecure) map["skip-cert-verify"] = true
                map
            }
            "wireguard" -> parseWireGuard(name, settings)
            else -> null
        }
    }

    private fun parseWireGuard(name: String, settings: JsonObject): Map<String, Any>? {
        val peers = (settings["peers"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        fun peer(source: JsonObject): Map<String, Any>? {
            val endpoint = source["endpoint"]?.jsonPrimitive?.contentOrNull
            val server = endpoint?.substringBeforeLast(':')?.removeSurrounding("[", "]")
                ?: settings["address"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: return null
            val port = endpoint?.substringAfterLast(':')?.toIntOrNull()
                ?: settings["port"]?.jsonPrimitive?.intOrNull ?: 51820
            val result = mutableMapOf<String, Any>("server" to server, "port" to port,
                "public-key" to (source["publicKey"]?.jsonPrimitive?.contentOrNull ?: ""))
            source["preSharedKey"]?.jsonPrimitive?.contentOrNull?.let { result["pre-shared-key"] = it }
            source["presharedKey"]?.jsonPrimitive?.contentOrNull?.let { result["pre-shared-key"] = it }
            (source["allowedIPs"] as? JsonArray)?.let { result["allowed-ips"] = it.map { ip -> ip.jsonPrimitive.content } }
            return result
        }
        val mapped = peers.mapNotNull(::peer)
        val first = mapped.firstOrNull() ?: return null
        val map = mutableMapOf<String, Any>("name" to name, "type" to "wireguard", "private-key" to
            (settings["secretKey"]?.jsonPrimitive?.contentOrNull ?: return null))
        map.putAll(first)
        if (mapped.size > 1) map["peers"] = mapped
        val addresses = (settings["address"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        addresses.firstOrNull { ':' !in it }?.let { map["ip"] = it.substringBefore('/') }
        addresses.firstOrNull { ':' in it }?.let { map["ipv6"] = it.substringBefore('/') }
        settings["mtu"]?.jsonPrimitive?.intOrNull?.let { map["mtu"] = it }
        settings["workers"]?.jsonPrimitive?.intOrNull?.let { map["workers"] = it }
        (settings["reserved"] as? JsonArray)?.let { map["reserved"] = it.map { byte -> byte.jsonPrimitive.int } }
        return map
    }

    private fun applyStreamSecurityAndTransport(
        map: MutableMap<String, Any>,
        net: String,
        security: String,
        streamSettings: JsonObject,
        address: String
    ) {
        if (security == "xtls") {
            // Deprecated pre-reality XTLS: port as plain TLS, the flow field
            // (xtls-rprx-vision/xtls-rprx-direct) survives via the users record.
            map["tls"] = true
            val tlsSettings = streamSettings["tlsSettings"] as? JsonObject ?: JsonObject(emptyMap())
            map["servername"] = tlsSettings["serverName"]?.jsonPrimitive?.contentOrNull ?: address
            if (tlsSettings["allowInsecure"]?.jsonPrimitive?.booleanOrNull == true ||
                streamSettings["allowInsecure"]?.jsonPrimitive?.booleanOrNull == true
            ) map["skip-cert-verify"] = true
        } else if (security == "reality") {
            map["tls"] = true
            val realitySettings = streamSettings["realitySettings"] as? JsonObject ?: JsonObject(emptyMap())
            map["servername"] = realitySettings["serverName"]?.jsonPrimitive?.contentOrNull ?: address
            val realityOpts = mutableMapOf<String, Any>(
                "public-key" to (realitySettings["publicKey"]?.jsonPrimitive?.contentOrNull ?: ""),
                "short-id" to (realitySettings["shortId"]?.jsonPrimitive?.contentOrNull ?: "")
            )
            val spiderX = realitySettings["spiderX"]?.jsonPrimitive?.contentOrNull
            if (!spiderX.isNullOrBlank()) realityOpts["spider-x"] = spiderX
            map["reality-opts"] = realityOpts
            val fp = realitySettings["fingerprint"]?.jsonPrimitive?.contentOrNull ?: ""
            if (fp.isNotBlank()) map["client-fingerprint"] = fp
        } else if (security == "tls") {
            map["tls"] = true
            val tlsSettings = streamSettings["tlsSettings"] as? JsonObject ?: JsonObject(emptyMap())
            map["servername"] = tlsSettings["serverName"]?.jsonPrimitive?.contentOrNull ?: address
            val fp = tlsSettings["fingerprint"]?.jsonPrimitive?.contentOrNull ?: ""
            if (fp.isNotBlank()) map["client-fingerprint"] = fp
            val alpn = tlsSettings["alpn"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
            if (!alpn.isNullOrEmpty()) map["alpn"] = alpn
            val allowInsecure = tlsSettings["allowInsecure"]?.jsonPrimitive?.booleanOrNull
                ?: streamSettings["allowInsecure"]?.jsonPrimitive?.booleanOrNull
                ?: false
            if (allowInsecure) map["skip-cert-verify"] = true
        }

        // WS Settings
        if (net == "ws") {
            val wsSettings = streamSettings["wsSettings"] as? JsonObject
            val path = wsSettings?.get("path")?.jsonPrimitive?.contentOrNull ?: "/"
            val wsOpts = mutableMapOf<String, Any>("path" to path)
            val wsHeaders = wsSettings?.get("headers")?.jsonObject
            val headers = wsHeaders?.entries?.associate { (k, v) ->
                    k to (v.jsonPrimitive.contentOrNull ?: "")
                }.orEmpty().toMutableMap()
            wsSettings?.get("host")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { host ->
                headers.keys.filter { it.equals("host", ignoreCase = true) }.forEach(headers::remove)
                headers["Host"] = host
            }
            if (headers.isNotEmpty()) wsOpts["headers"] = headers
            val maxEarly = wsSettings?.get("maxEarlyData")?.jsonPrimitive?.intOrNull
                ?: wsSettings?.get("maxEarlyDataPx")?.let { null }
            if (maxEarly != null && maxEarly > 0) {
                wsOpts["max-early-data"] = maxEarly
                val earlyHeader = wsSettings?.get("earlyDataHeaderName")?.jsonPrimitive?.contentOrNull
                if (!earlyHeader.isNullOrBlank()) wsOpts["early-data-header-name"] = earlyHeader
            }
            map["ws-opts"] = wsOpts
        }

        // gRPC Settings
        if (net == "grpc") {
            val grpcSettings = streamSettings["grpcSettings"] as? JsonObject
            val svc = grpcSettings?.get("serviceName")?.jsonPrimitive?.contentOrNull ?: ""
            val userAgent = grpcSettings?.get("user_agent")?.jsonPrimitive?.contentOrNull.orEmpty()
            if (svc.isNotBlank() || userAgent.isNotBlank()) {
                val grpcOpts = mutableMapOf<String, Any>("grpc-service-name" to svc)
                if (userAgent.isNotBlank()) grpcOpts["grpc-user-agent"] = userAgent
                if (grpcSettings?.get("multiMode")?.jsonPrimitive?.booleanOrNull == true) {
                    grpcOpts["grpc-multi-mode"] = true
                }
                map["grpc-opts"] = grpcOpts
            }
        }

        // TCP with HTTP obfs header (header.type == "http") -> mihomo http-opts
        // (legacy tcpSettings; newer Xray calls it rawSettings)
        if (net == "tcp") {
            val tcpHeader = (streamSettings["tcpSettings"] ?: streamSettings["rawSettings"])
                ?.jsonObject?.get("header")?.jsonObject
            if (tcpHeader?.get("type")?.jsonPrimitive?.contentOrNull == "http") {
                val request = tcpHeader["request"]?.jsonObject
                val httpOpts = mutableMapOf<String, Any>()
                request?.get("method")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }?.let { httpOpts["method"] = it }
                val paths = request?.get("path")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                if (!paths.isNullOrEmpty()) httpOpts["path"] = paths
                val headers = request?.get("headers")?.jsonObject
                if (headers != null && headers.isNotEmpty()) {
                    val headerMap = headers.entries.associate { (k, v) ->
                        val list = (v as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                            ?: listOfNotNull(v.jsonPrimitive.contentOrNull)
                        k to list
                    }
                    httpOpts["headers"] = headerMap
                }
                map["network"] = "http"
                map["http-opts"] = httpOpts
            }
        }

        // xhttp / splithttp Settings
        if (net == "xhttp" || net == "splithttp") {
            val xhttpSettings = streamSettings["xhttpSettings"] as? JsonObject
                ?: streamSettings["splithttpSettings"] as? JsonObject
            val path = xhttpSettings?.get("path")?.jsonPrimitive?.contentOrNull ?: "/"
            val host = xhttpSettings?.get("host")?.jsonPrimitive?.contentOrNull ?: ""
            val xhttpOpts = mutableMapOf<String, Any>("path" to path)
            if (host.isNotBlank()) xhttpOpts["host"] = host
            val mode = xhttpSettings?.get("mode")?.jsonPrimitive?.contentOrNull
            if (!mode.isNullOrBlank()) xhttpOpts["mode"] = mode
            map["xhttp-opts"] = xhttpOpts
        }

        // HTTP / h2 Settings
        if (net == "http" || net == "h2") {
            val httpSettings = streamSettings["httpSettings"] as? JsonObject
            val path = httpSettings?.get("path")?.jsonPrimitive?.contentOrNull ?: "/"
            val host = httpSettings?.get("host")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            val h2Opts = mutableMapOf<String, Any>("path" to path)
            if (host.isNotEmpty()) h2Opts["host"] = host
            map["network"] = "h2"
            map["h2-opts"] = h2Opts
        }

        // HTTPUpgrade Settings
        if (net == "httpupgrade") {
            val upSettings = streamSettings["httpupgradeSettings"] as? JsonObject
            val path = upSettings?.get("path")?.jsonPrimitive?.contentOrNull ?: "/"
            val host = upSettings?.get("host")?.jsonPrimitive?.contentOrNull ?: ""
            map["network"] = "ws"
            map["ws-opts"] = buildMap<String, Any> {
                put("path", path)
                put("v2ray-http-upgrade", true)
                val headers = (upSettings?.get("headers") as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty().toMutableMap()
                if (host.isNotBlank()) {
                    headers.keys.filter { it.equals("host", ignoreCase = true) }.forEach(headers::remove)
                    headers["Host"] = host
                }
                if (headers.isNotEmpty()) put("headers", headers)
            }
        }

        // mKCP: Xray kcpSettings → mihomo mkcp-opts (current wiki name)
        if (net == "kcp" || net == "mkcp") {
            val kcp = (streamSettings["kcpSettings"] ?: streamSettings["mkcpSettings"])
                ?.jsonObject ?: JsonObject(emptyMap())
            val opts = mutableMapOf<String, Any>()
            kcp["mtu"]?.jsonPrimitive?.intOrNull?.let { opts["mtu"] = it }
            kcp["tti"]?.jsonPrimitive?.intOrNull?.let { opts["tti"] = it }
            kcp["uplinkCapacity"]?.jsonPrimitive?.intOrNull?.let { opts["uplink-capacity"] = it }
            kcp["downlinkCapacity"]?.jsonPrimitive?.intOrNull?.let { opts["downlink-capacity"] = it }
            kcp["congestion"]?.jsonPrimitive?.booleanOrNull?.let { opts["congestion"] = it }
            kcp["writeBufferSize"]?.jsonPrimitive?.intOrNull?.let { opts["write-buffer"] = it }
            kcp["readBufferSize"]?.jsonPrimitive?.intOrNull?.let { opts["read-buffer"] = it }
            kcp["seed"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }?.let { opts["seed"] = it }
            kcp["header"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() && it != "none" }?.let { opts["header"] = it }
            if (opts.isNotEmpty()) map["mkcp-opts"] = opts
        }

        // sockopt.dialerProxy chains one outbound behind another (mihomo dialer-proxy)
        streamSettings["sockopt"]?.jsonObject?.get("dialerProxy")
            ?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }?.let { map["dialer-proxy"] = it }
    }


}
