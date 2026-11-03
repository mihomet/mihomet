package org.mihomet.app.core.config

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder

/**
 * Parser for the share-link URI schemes mihomet understands: vless, vmess,
 * trojan, ss (SIP002 + legacy base64), hysteria2/hy2, hysteria, tuic, ssh,
 * snell and wg. Each URI maps to a Mihomo proxy map or null (the caller is
 * responsible for reporting the drop).
 *
 * New protocols must be added to [parseUriToMihomoProxy] dispatch + fixture
 * tests (see AGENTS.md invariant 7).
 */
internal object UriListParser {

    fun parseUriToMihomoProxy(uri: String, warnings: MutableList<ConversionWarning>? = null): Map<String, Any>? {
        return try {
            // Hiddify embeds an "expiry date" pseudo-link (trojan://…--YYYY.MM.DD.time:<port>) that is
            // client-side display metadata, not a proxy node — reported as unrecognized below
            val isExpiryPseudoLink =
                Regex("^[a-z0-9]+://.*--\\d{4}\\.\\d{2}\\.\\d{2}\\.time:\\d+")
                    .containsMatchIn(uri)
            val hashIdx = uri.indexOf('#')
            val rawRemarks = if (hashIdx != -1) uri.substring(hashIdx + 1) else ""
            // URL-decoded remarks may carry CR/LF from a hostile subscription —
            // a node name lands inside the YAML config and log lines, so control
            // characters must never survive (YAML/header injection guard).
            val remarks = runCatching { URLDecoder.decode(rawRemarks, "UTF-8") }
                .getOrDefault(rawRemarks)
                .replace(Regex("[\\r\\n\u0000-\u001f]+"), " ")
                .trim()
                .ifBlank { "Node" }
            val cleanUri = if (hashIdx != -1) uri.substring(0, hashIdx) else uri

            var recognized = true
            val proxy = when {
                isExpiryPseudoLink -> null
                cleanUri.startsWith("vless://") -> parseVlessUri(cleanUri, remarks)
                cleanUri.startsWith("vmess://") -> parseVmessUri(cleanUri, remarks)
                cleanUri.startsWith("trojan://") -> parseTrojanUri(cleanUri, remarks)
                cleanUri.startsWith("ss://") -> parseShadowsocksUri(cleanUri, remarks)
                cleanUri.startsWith("hysteria2://") || cleanUri.startsWith("hy2://") -> parseHysteria2Uri(cleanUri, remarks)
                cleanUri.startsWith("hysteria://") -> parseHysteriaUri(cleanUri, remarks)
                cleanUri.startsWith("tuic://") -> parseTuicUri(cleanUri, remarks)
                cleanUri.startsWith("ssh://") -> parseSshUri(cleanUri, remarks)
                cleanUri.startsWith("snell://") -> parseSnellUri(cleanUri, remarks)
                cleanUri.startsWith("wg://") -> parseWireguardUri(cleanUri, remarks)
                else -> {
                    recognized = false
                    null
                }
            }
            if (proxy == null) {
                // A matched scheme that failed to parse must not vanish silently either
                warnings?.add(
                    if (recognized) ConversionWarning.ProxyParseError(uri) else ConversionWarning.UnrecognizedNode(uri)
                )
            }
            proxy
        } catch (e: Exception) {
            warnings?.add(ConversionWarning.ProxyParseError(uri, e.message))
            null
        }
    }

    private fun parseVlessUri(uri: String, name: String): Map<String, Any>? {
        val noScheme = uri.removePrefix("vless://")
        val atIdx = noScheme.indexOf('@')
        if (atIdx == -1) return null
        val uuid = noScheme.substring(0, atIdx)
        val rest = noScheme.substring(atIdx + 1)
        val qIdx = rest.indexOf('?')
        val hostPort = if (qIdx != -1) rest.substring(0, qIdx) else rest
        val query = if (qIdx != -1) rest.substring(qIdx + 1) else ""

        val (server, rawPort) = parseHostPort(hostPort) ?: return null
        val port = rawPort.toIntOrNull() ?: 443
        if (uuid.isBlank() || server.isBlank() || port !in 1..65535) return null

        val params = parseQueryParams(query)
        // splithttp is emitted by some panels as the alias of xhttp
        val network = when (params["type"]) {
            "splithttp" -> "xhttp"
            else -> params["type"] ?: "tcp"
        }

        val map = mutableMapOf<String, Any>(
            "name" to name,
            "type" to "vless",
            "server" to server,
            "port" to port,
            "uuid" to uuid,
            "network" to network,
            "udp" to true
        )
        params["flow"]?.let { if (it.isNotBlank()) map["flow"] = it }

        val sec = params["security"] ?: ""
        if (sec == "reality") {
            map["tls"] = true
            map["servername"] = params["sni"] ?: server
            map["reality-opts"] = mapOf(
                "public-key" to (params["pbk"] ?: ""),
                "short-id" to (params["sid"] ?: "")
            )
            params["fp"]?.let { map["client-fingerprint"] = it }
        } else if (sec == "tls") {
            map["tls"] = true
            map["servername"] = params["sni"] ?: server
            params["fp"]?.let { map["client-fingerprint"] = it }
            params["alpn"]?.let { map["alpn"] = it.split(",") }
        }

        when (map["network"]) {
            "ws" -> {
                val wsOpts = mutableMapOf<String, Any>("path" to (params["path"] ?: "/"))
                params["host"]?.let { wsOpts["headers"] = mapOf("Host" to it) }
                // WebSocket 0-RTT early data (v2ray/xray "ed"/"eh" params)
                params["ed"]?.toIntOrNull()?.let { wsOpts["max-early-data"] = it }
                params["eh"]?.takeIf { it.isNotBlank() }?.let { wsOpts["early-data-header-name"] = it }
                map["ws-opts"] = wsOpts
            }
            "grpc" -> {
                params["serviceName"]?.let { map["grpc-opts"] = mapOf("grpc-service-name" to it) }
            }
            "xhttp" -> {
                val opts = mutableMapOf<String, Any>(
                    "path" to (params["path"] ?: "/")
                )
                params["host"]?.let { opts["host"] = it }
                params["mode"]?.let { if (it.isNotBlank()) opts["mode"] = it }
                map["xhttp-opts"] = opts
            }
            "httpupgrade" -> {
                val opts = mutableMapOf<String, Any>("path" to (params["path"] ?: "/"))
                params["host"]?.let { opts["host"] = it }
                map["httpupgrade-opts"] = opts
            }
            "kcp" -> {
                val opts = mutableMapOf<String, Any>()
                params["seed"]?.takeIf { it.isNotBlank() }?.let { opts["seed"] = it }
                params["headerType"]?.takeIf { it.isNotBlank() && it != "none" }
                    ?.let { opts["header"] = mapOf("type" to it) }
                map["kcp-opts"] = opts
            }
            "tcp" -> {
                // HTTP obfs (headerType=http) -> mihomo http-opts
                if (params["headerType"] == "http") {
                    val opts = mutableMapOf<String, Any>()
                    params["path"]?.takeIf { it.isNotBlank() }?.let { opts["path"] = listOf(it) }
                    params["host"]?.takeIf { it.isNotBlank() }
                        ?.let { opts["headers"] = mapOf("Host" to listOf(it)) }
                    map["http-opts"] = opts
                }
            }
        }

        return map
    }

    private fun parseVmessUri(uri: String, defaultName: String): Map<String, Any>? {
        val noScheme = uri.removePrefix("vmess://")
        val decoded = runCatching { ConverterSupport.decodeBase64(noScheme) }.getOrNull() ?: return null
        val obj = runCatching { ConverterSupport.json.parseToJsonElement(decoded).jsonObject }.getOrNull() ?: return null

        val server = obj["add"]?.jsonPrimitive?.contentOrNull ?: return null
        val port = obj["port"]?.jsonPrimitive?.intOrNull ?: (obj["port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()) ?: return null
        val uuid = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val alterId = obj["aid"]?.jsonPrimitive?.intOrNull ?: (obj["aid"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()) ?: 0
        val ps = obj["ps"]?.jsonPrimitive?.contentOrNull ?: defaultName
        val net = obj["net"]?.jsonPrimitive?.contentOrNull ?: "tcp"
        val tls = obj["tls"]?.jsonPrimitive?.contentOrNull ?: ""

        val map = mutableMapOf<String, Any>(
            "name" to ps.ifBlank { defaultName },
            "type" to "vmess",
            "server" to server,
            "port" to port,
            "uuid" to uuid,
            "alterId" to alterId,
            "cipher" to (obj["scy"]?.jsonPrimitive?.contentOrNull ?: "auto"),
            "network" to net,
            "udp" to true
        )

        if (tls == "tls") {
            map["tls"] = true
            val sni = obj["sni"]?.jsonPrimitive?.contentOrNull ?: server
            map["servername"] = sni
        }

        if (net == "ws") {
            val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: "/"
            val host = obj["host"]?.jsonPrimitive?.contentOrNull ?: ""
            val wsOpts = mutableMapOf<String, Any>("path" to path)
            if (host.isNotBlank()) wsOpts["headers"] = mapOf("Host" to host)
            map["ws-opts"] = wsOpts
        }

        return map
    }

    private fun parseTrojanUri(uri: String, name: String): Map<String, Any>? {
        val noScheme = uri.removePrefix("trojan://")
        val atIdx = noScheme.indexOf('@')
        if (atIdx == -1) return null
        val password = noScheme.substring(0, atIdx)
        val rest = noScheme.substring(atIdx + 1)
        val qIdx = rest.indexOf('?')
        val hostPort = if (qIdx != -1) rest.substring(0, qIdx) else rest
        val query = if (qIdx != -1) rest.substring(qIdx + 1) else ""

        val (server, rawPort) = parseHostPort(hostPort) ?: return null
        val port = rawPort.toIntOrNull() ?: 443

        val params = parseQueryParams(query)

        val map = mutableMapOf<String, Any>(
            "name" to name,
            "type" to "trojan",
            "server" to server,
            "port" to port,
            "password" to password,
            "udp" to true
        )
        val sni = params["sni"] ?: server
        map["sni"] = sni
        return map
    }

    private fun parseShadowsocksUri(uri: String, name: String): Map<String, Any>? {
        val noScheme = uri.removePrefix("ss://")
        return if (noScheme.contains("@")) {
            // SIP002: ss://userinfo@host:port — userinfo is either websafe-base64(cipher:password)
            // or, with SS2022 panels (3x-ui etc.), a percent-encoded plain "cipher:psk[:ipsk]"
            val atIdx = noScheme.lastIndexOf('@')
            val userinfo = noScheme.substring(0, atIdx)
            val rest = noScheme.substring(atIdx + 1)
            val (method, password) = decodeShadowsocksUserinfo(userinfo) ?: return null
            val qIdx = rest.indexOf('?')
            val hostPort = if (qIdx != -1) rest.substring(0, qIdx) else rest
            val (server, rawPort) = parseHostPort(hostPort) ?: return null
            val map = mutableMapOf<String, Any>(
                "name" to name,
                "type" to "ss",
                "server" to server,
                "port" to (rawPort.toIntOrNull() ?: 8388),
                "cipher" to method,
                "password" to password,
                "udp" to true
            )
            // SIP003 plugin (simple-obfs / v2ray-plugin) -> mihomo plugin + plugin-opts
            if (qIdx != -1) {
                val params = parseQueryParams(rest.substring(qIdx + 1))
                val pluginParam = params["plugin"]
                if (!pluginParam.isNullOrBlank()) {
                    val parts = pluginParam.split(";")
                    when (parts.firstOrNull()) {
                        "obfs-local", "simple-obfs" -> {
                            map["plugin"] = "obfs"
                            val opts = mutableMapOf<String, Any>()
                            for (part in parts.drop(1)) {
                                val kv = part.split("=", limit = 2)
                                when (kv.getOrNull(0)) {
                                    "obfs" -> opts["mode"] = kv.getOrNull(1) ?: "http"
                                    "obfs-host" -> kv.getOrNull(1)?.let { opts["host"] = it }
                                }
                            }
                            map["plugin-opts"] = opts
                        }
                        "v2ray-plugin" -> {
                            map["plugin"] = "v2ray-plugin"
                            val opts = mutableMapOf<String, Any>()
                            for (part in parts.drop(1)) {
                                val kv = part.split("=", limit = 2)
                                when (kv.getOrNull(0)) {
                                    "mode" -> opts["mode"] = kv.getOrNull(1) ?: "websocket"
                                    "host" -> kv.getOrNull(1)?.let { opts["host"] = it }
                                    "path" -> kv.getOrNull(1)?.let { opts["path"] = it }
                                    "tls" -> opts["tls"] = true
                                }
                            }
                            if (opts["mode"] == null) opts["mode"] = "websocket"
                            map["plugin-opts"] = opts
                        }
                    }
                }
            }
            map
        } else {
            val decoded = ConverterSupport.decodeBase64(noScheme)
            val atIdx = decoded.indexOf('@')
            if (atIdx == -1) return null
            val userinfo = decoded.substring(0, atIdx)
            val hp = decoded.substring(atIdx + 1)
            val upParts = userinfo.split(":")
            val hostPort = parseHostPort(hp) ?: return null
            if (upParts.size != 2) return null
            mutableMapOf(
                "name" to name,
                "type" to "ss",
                "server" to hostPort.first,
                "port" to (hostPort.second.toIntOrNull() ?: 8388),
                "cipher" to upParts[0],
                "password" to upParts[1]
            )
        }
    }

    /**
     * Decodes the SIP002 userinfo part of an ss:// link into (cipher, password).
     */
    private fun decodeShadowsocksUserinfo(userinfo: String): Pair<String, String>? {
        // Websafe-base64 form — raw userinfo never contains a colon
        if (!userinfo.contains(':')) {
            val decodedUser = runCatching { ConverterSupport.decodeBase64(userinfo) }.getOrNull()
            if (decodedUser != null) {
                val parts = decodedUser.split(":", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    return parts[0] to parts[1]
                }
            }
        }
        // Plain percent-encoded form (SS2022): "cipher:psk" — password may itself contain ':'
        val decoded = runCatching { URLDecoder.decode(userinfo, "UTF-8") }.getOrElse { userinfo }
        val idx = decoded.indexOf(':')
        if (idx != -1) {
            val method = decoded.substring(0, idx)
            val password = decoded.substring(idx + 1)
            if (method.isNotBlank() && password.isNotBlank()) return method to password
        }
        return null
    }

    private fun parseHysteria2Uri(uri: String, name: String): Map<String, Any>? {
        val cleanScheme = if (uri.startsWith("hy2://")) uri.removePrefix("hy2://") else uri.removePrefix("hysteria2://")
        val atIdx = cleanScheme.indexOf('@')
        if (atIdx == -1) return null
        val password = cleanScheme.substring(0, atIdx)
        val rest = cleanScheme.substring(atIdx + 1)
        val qIdx = rest.indexOf('?')
        val hostPort = if (qIdx != -1) rest.substring(0, qIdx) else rest
        val query = if (qIdx != -1) rest.substring(qIdx + 1) else ""

        val (server, rawPort) = parseHostPort(hostPort) ?: return null

        val params = parseQueryParams(query)

        val port = rawPort.split("-", ",").firstOrNull()?.trim()?.toIntOrNull() ?: 443

        val map = mutableMapOf<String, Any>(
            "name" to name,
            "type" to "hysteria2",
            "server" to server,
            "port" to port,
            "password" to password,
            "udp" to true
        )

        // Port hopping support (e.g. 20000-40000 or mport parameter)
        if (rawPort.contains("-") || rawPort.contains(",")) {
            map["ports"] = rawPort
        } else if (!params["mport"].isNullOrBlank()) {
            map["ports"] = params["mport"]!!
        } else if (!params["ports"].isNullOrBlank()) {
            map["ports"] = params["ports"]!!
        }

        val sni = params["sni"]?.ifBlank { null }
            ?: params["peer"]?.ifBlank { null }
            ?: if (!server.matches(Regex("^[0-9.]+$")) && !server.contains(":")) server else null
        if (sni != null) {
            map["sni"] = sni
        }

        map["alpn"] = params["alpn"]?.split(",") ?: listOf("h3")

        val insecure = params["insecure"] == "1" ||
                params["insecure"].equals("true", ignoreCase = true) ||
                params["skip-cert-verify"].equals("true", ignoreCase = true) ||
                params["allowInsecure"] == "1"
        if (insecure) {
            map["skip-cert-verify"] = true
        }

        params["obfs"]?.let {
            map["obfs"] = it
            params["obfs-password"]?.let { op -> map["obfs-password"] = op }
        }
        params["up"]?.let { map["up"] = it }
        params["down"]?.let { map["down"] = it }
        return map
    }

    private fun parseHysteriaUri(uri: String, name: String): Map<String, Any>? {
        val noScheme = uri.removePrefix("hysteria://")
        // Auth may be either userinfo ("hysteria://auth@host:port") or the "auth" query param.
        // lastIndexOf keeps bare IPv6 user info from breaking the split.
        val atIdx = noScheme.lastIndexOf('@')
        var auth = ""
        val rest: String
        if (atIdx != -1 && noScheme.indexOf(':', atIdx + 1) != -1) {
            auth = noScheme.substring(0, atIdx)
            rest = noScheme.substring(atIdx + 1)
        } else {
            rest = noScheme
        }

        val qIdx = rest.indexOf('?')
        val hostPort = if (qIdx != -1) rest.substring(0, qIdx) else rest
        val query = if (qIdx != -1) rest.substring(qIdx + 1) else ""

        val (server, rawPort) = parseHostPort(hostPort) ?: return null
        val port = rawPort.toIntOrNull() ?: return null

        val params = parseQueryParams(query)
        params["auth"]?.takeIf { it.isNotBlank() }?.let { auth = it }

        val map = mutableMapOf<String, Any>(
            "name" to name,
            "type" to "hysteria",
            "server" to server,
            "port" to port,
            "udp" to true
        )
        if (auth.isNotBlank()) map["auth-str"] = auth
        val up = params["upmbps"]?.toIntOrNull() ?: params["up"]?.toIntOrNull() ?: 50
        val down = params["downmbps"]?.toIntOrNull() ?: params["down"]?.toIntOrNull() ?: 100
        map["up"] = up
        map["down"] = down

        val sni = params["peer"]?.takeIf { it.isNotBlank() } ?: params["sni"]?.takeIf { it.isNotBlank() }
        if (sni != null) map["sni"] = sni

        val insecure = params["insecure"] == "1" || params["insecure"].equals("true", ignoreCase = true)
        if (insecure) map["skip-cert-verify"] = true

        params["obfs"]?.takeIf { it.isNotBlank() }?.let { map["obfs"] = it }
        params["alpn"]?.takeIf { it.isNotBlank() }?.let { map["alpn"] = it.split(",") }
        return map
    }

    private fun parseTuicUri(uri: String, name: String): Map<String, Any>? {
        val noScheme = uri.removePrefix("tuic://")
        val atIdx = noScheme.lastIndexOf('@')
        if (atIdx == -1) return null
        val userinfo = noScheme.substring(0, atIdx)
        val rest = noScheme.substring(atIdx + 1)

        val qIdx = rest.indexOf('?')
        val hostPort = if (qIdx != -1) rest.substring(0, qIdx) else rest
        val query = if (qIdx != -1) rest.substring(qIdx + 1) else ""

        val uuid = userinfo.substringBefore(':')
        val password = userinfo.substringAfter(':', missingDelimiterValue = "")

        val (server, rawPort) = parseHostPort(hostPort) ?: return null
        val port = rawPort.toIntOrNull() ?: return null

        val params = parseQueryParams(query)

        val map = mutableMapOf<String, Any>(
            "name" to name,
            "type" to "tuic",
            "server" to server,
            "port" to port,
            "uuid" to uuid,
            "password" to password,
            "udp" to true
        )
        params["sni"]?.takeIf { it.isNotBlank() }?.let { map["sni"] = it }
        params["congestion_control"]?.takeIf { it.isNotBlank() }?.let { map["congestion-controller"] = it }
        params["udp_relay_mode"]?.takeIf { it.isNotBlank() }?.let { map["udp-relay-mode"] = it }
        params["alpn"]?.takeIf { it.isNotBlank() }?.let { map["alpn"] = it.split(",") }

        val insecure = params["allow_insecure"] == "1" ||
                params["allow_insecure"].equals("true", ignoreCase = true) ||
                params["skip_cert_verify"].equals("true", ignoreCase = true) ||
                params["allowInsecure"] == "1"
        if (insecure) map["skip-cert-verify"] = true

        if (params["disable_sni"] == "1" || params["disable_sni"].equals("true", ignoreCase = true)) {
            map["disable-sni"] = true
        }
        return map
    }

    /**
     * Snell URI (Hiddify-style): snell://[userinfo@]host:port?psk=...&version=4&obfs=none
     */
    private fun parseSnellUri(uri: String, name: String): Map<String, Any>? {
        val noScheme = uri.removePrefix("snell://")
        val qIdx = noScheme.indexOf('?')
        var authority = if (qIdx != -1) noScheme.substring(0, qIdx) else noScheme
        val query = if (qIdx != -1) noScheme.substring(qIdx + 1) else ""
        authority = authority.trimEnd('/').substringAfterLast('@')

        val (server, rawPort) = parseHostPort(authority) ?: return null
        val port = rawPort.toIntOrNull() ?: return null
        val params = parseQueryParams(query)
        val psk = params["psk"]?.takeIf { it.isNotBlank() } ?: return null

        val map = mutableMapOf<String, Any>(
            "name" to name,
            "type" to "snell",
            "server" to server,
            "port" to port,
            "psk" to psk,
            "udp" to true
        )
        params["version"]?.takeIf { it.isNotBlank() }?.let { map["version"] = it }
        val obfsMode = params["obfs"]?.takeIf { it.isNotBlank() && it != "none" }
        if (obfsMode != null) {
            val obfsOpts = mutableMapOf<String, Any>("mode" to obfsMode)
            params["obfs-host"]?.takeIf { it.isNotBlank() }?.let { obfsOpts["host"] = it }
            map["obfs-opts"] = obfsOpts
        }
        return map
    }

    /**
     * WireGuard URI (Hiddify wg:// scheme): wg://host:port
     * ?pk=<private key>&local_address=<cidr>&peer_pk=<peer public>&pre...=<preshared>
     */
    private fun parseWireguardUri(uri: String, name: String): Map<String, Any>? {
        val noScheme = uri.removePrefix("wg://")
        val qIdx = noScheme.indexOf('?')
        var authority = if (qIdx != -1) noScheme.substring(0, qIdx) else noScheme
        val query = if (qIdx != -1) noScheme.substring(qIdx + 1) else ""
        authority = authority.trimEnd('/')

        val (server, rawPort) = parseHostPort(authority) ?: return null
        val port = rawPort.toIntOrNull() ?: return null
        val params = parseQueryParams(query)
        val privateKey = params["pk"]?.takeIf { it.isNotBlank() } ?: return null

        val map = mutableMapOf<String, Any>(
            "name" to name,
            "type" to "wireguard",
            "server" to server,
            "port" to port,
            "private-key" to privateKey
        )
        params["peer_pk"]?.takeIf { it.isNotBlank() }?.let { map["public-key"] = it }
        params["preshared_key"]?.takeIf { it.isNotBlank() }?.let { map["preshared-key"] = it }
        params["local_address"]?.takeIf { it.isNotBlank() }?.let { map["ip"] = it }
        params["dns"]?.takeIf { it.isNotBlank() }?.let { map["dns"] = listOf(it) }
        params["mtu"]?.toIntOrNull()?.let { map["mtu"] = it }
        return map
    }

    /**
     * Parses ssh:// links (Hiddify-style): ssh://user:password@host:port
     * ?private_key=...&hk=<host key>. Multi-line key material arrives
     * percent-encoded and goes into the YAML as a quoted scalar.
     */
    private fun parseSshUri(uri: String, name: String): Map<String, Any>? {
        val noScheme = uri.removePrefix("ssh://")
        val qIdx = noScheme.indexOf('?')
        var authority = if (qIdx != -1) noScheme.substring(0, qIdx) else noScheme
        val query = if (qIdx != -1) noScheme.substring(qIdx + 1) else ""
        authority = authority.trimEnd('/')

        val atIdx = authority.lastIndexOf('@')
        if (atIdx == -1) return null
        val userinfo = authority.substring(0, atIdx)
        val (server, rawPort) = parseHostPort(authority.substring(atIdx + 1)) ?: return null
        val port = rawPort.toIntOrNull() ?: 22

        val username = runCatching { URLDecoder.decode(userinfo.substringBefore(':'), "UTF-8") }
            .getOrDefault(userinfo.substringBefore(':'))
        val password = userinfo.substringAfter(':', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }

        val params = parseQueryParams(query)
        val map = mutableMapOf<String, Any>(
            "name" to name,
            "type" to "ssh",
            "server" to server,
            "port" to port,
            "username" to username
        )
        password?.let { map["password"] = it }
        params["private_key"]?.takeIf { it.isNotBlank() }?.let {
            map["private-key"] = URLDecoder.decode(it, "UTF-8")
        }
        params["hk"]?.takeIf { it.isNotBlank() }?.let {
            map["host-key"] = URLDecoder.decode(it, "UTF-8")
        }
        return map
    }

    /**
     * Parses "host:port" tolerating IPv6: "[2001:db8::1]:443" or plain "host:443".
     * A trailing "/" (authority form before the query string) is tolerated.
     * Returns the host and the raw (unparsed) port; null for a bracket-less IPv6
     * literal (ambiguous without a port) or a malformed bracket form.
     */
    private fun parseHostPort(raw: String): Pair<String, String>? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.startsWith("[")) {
            val close = trimmed.indexOf(']')
            if (close == -1) return null
            val host = trimmed.substring(1, close)
            val rest = trimmed.substring(close + 1)
            if (!rest.startsWith(":")) return null
            return host to rest.removePrefix(":")
        }
        if (trimmed.count { it == ':' } == 1) {
            return trimmed.substringBefore(':') to trimmed.substringAfter(':')
        }
        return null
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        return query.split("&").mapNotNull {
            val p = it.split("=", limit = 2)
            if (p.size == 2) p[0] to URLDecoder.decode(p[1], "UTF-8") else null
        }.toMap()
    }
}
