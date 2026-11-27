package org.mihomet.app.core.config

import kotlinx.serialization.json.*
import org.mihomet.app.core.config.XrayJsonConverter.array
import org.mihomet.app.core.config.XrayJsonConverter.obj
import org.mihomet.app.core.config.XrayJsonConverter.text

internal object XrayTransportOptions {
    fun network(stream: JsonObject): String = stream.text("method").ifBlank { stream.text("network").ifBlank { "tcp" } }.lowercase()

    private val transports = mapOf(
        "vless" to setOf("tcp", "raw", "ws", "websocket", "grpc", "h2", "http", "xhttp", "splithttp", "httpupgrade"),
        "vmess" to setOf("tcp", "raw", "ws", "websocket", "grpc", "h2", "http", "kcp", "mkcp", "httpupgrade"),
        "trojan" to setOf("tcp", "raw", "ws", "websocket", "grpc", "httpupgrade")
    )

    fun supported(protocol: String, stream: JsonObject): Boolean {
        val allowed = transports[protocol] ?: when (protocol) {
            "http", "socks", "shadowsocks" -> setOf("tcp", "raw")
            else -> null
        }
        val network = network(stream)
        val security = stream.text("security")
        if (protocol in setOf("shadowsocks", "wireguard") && security !in setOf("", "none")) return false
        if (protocol in setOf("http", "socks") && security !in setOf("", "none", "tls")) return false
        return (allowed == null || network in allowed) && security in setOf("", "none", "tls", "reality", "xtls") &&
            (security != "reality" || protocol == "vless")
    }

    fun validReality(stream: JsonObject): Boolean {
        if (stream.text("security") != "reality") return true
        val reality = stream.obj("realitySettings")
        val key = reality.text("publicKey").ifBlank { reality.text("password") }
        val decoded = runCatching { java.util.Base64.getUrlDecoder().decode(key) }.getOrNull()
        val shortId = reality.text("shortId")
        return decoded?.size == 32 && shortId.length <= 16 && shortId.length % 2 == 0 &&
            shortId.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
    }

    fun apply(map: MutableMap<String, Any>, protocol: String, settings: JsonObject, stream: JsonObject, omitted: (String) -> Unit) {
        val knownStream = setOf("network", "method", "security", "tlsSettings", "xtlsSettings", "realitySettings", "tcpSettings", "rawSettings", "wsSettings", "grpcSettings", "xhttpSettings", "splithttpSettings", "httpSettings", "httpupgradeSettings", "kcpSettings", "mkcpSettings", "sockopt", "allowInsecure", "hysteriaSettings")
        stream.keys.filterNot { it in knownStream }.forEach { omitted("streamSettings.${XraySourceFields.safeName(it)}") }
        stream.obj("sockopt").keys.filterNot { it == "dialerProxy" }.forEach { omitted("streamSettings.sockopt.${XraySourceFields.safeName(it)}") }
        val security = stream.text("security")
        val tls = if (security in setOf("tls", "xtls")) stream.obj("tlsSettings").ifEmpty { stream.obj("xtlsSettings") }
            else JsonObject(emptyMap())
        validateTlsVerification(tls)
        if (security == "tls") {
            val allowInsecure = (tls["allowInsecure"] as? JsonPrimitive)?.booleanOrNull
                ?: (stream["allowInsecure"] as? JsonPrimitive)?.booleanOrNull ?: false
            if (allowInsecure) map["skip-cert-verify"] = true
            if (protocol in setOf("http", "socks")) {
                if (tls.text("fingerprint").isNotBlank()) omitted("streamSettings.tlsSettings.fingerprint")
                val alpn = tls.array("alpn").map { it.jsonPrimitive.content }
                require(alpn.isEmpty() || alpn == listOf("http/1.1"))
            }
        }
        if (protocol == "socks" && stream.text("security") == "tls") {
            map["tls"] = true
            if (tls.text("serverName").let { it.isNotBlank() && it != map["server"] }) {
                throw IllegalArgumentException("Custom SOCKS TLS SNI is not supported by the pinned core")
            }
        }
        val sniKey = if (protocol in setOf("trojan", "hysteria", "hysteria2", "tuic", "http", "socks")) "sni" else "servername"
        tls.text("serverName").takeIf { it.isNotBlank() }?.let { map[sniKey] = it }
        if (sniKey == "sni") map.remove("servername")
        val reality = if (security == "reality") stream.obj("realitySettings") else JsonObject(emptyMap())
        if (reality.text("mldsa65Verify").isNotBlank()) {
            throw IllegalArgumentException("The pinned core cannot preserve ML-DSA server verification")
        }
        if (reality.isNotEmpty()) {
            map["reality-opts"] = ConverterSupport.realityOptions(
                reality.text("publicKey").ifBlank { reality.text("password") }, reality.text("shortId")
            )
            map["client-fingerprint"] = reality.text("fingerprint").ifBlank { "chrome" }
            if (reality.text("spiderX").isNotBlank()) omitted("streamSettings.realitySettings.spiderX")
            reality.keys.filterNot { it in setOf("publicKey", "password", "shortId", "serverName", "fingerprint", "show", "spiderX", "mldsa65Verify") }
                .forEach { omitted("streamSettings.realitySettings.${XraySourceFields.safeName(it)}") }
        }
        tls.keys.filterNot { it in setOf("serverName", "allowInsecure", "alpn", "fingerprint", "certificates") }
            .forEach { omitted("streamSettings.tlsSettings.${XraySourceFields.safeName(it)}") }
        if (stream.text("security") == "xtls") omitted("streamSettings.security")
        settings.text("packetEncoding").takeIf { it.isNotBlank() }?.let {
            if (protocol in setOf("vless", "vmess")) map["packet-encoding"] = it
            else omitted("settings.packetEncoding")
        }
        if (map["type"] == "vless" && map["flow"]?.toString()?.let { it.isNotEmpty() && it != "xtls-rprx-vision" } == true) {
            throw IllegalArgumentException("Unsupported VLESS flow")
        }
        val network = network(stream)
        val xhttp = stream.obj("xhttpSettings").ifEmpty { stream.obj("splithttpSettings") }
        if (network in setOf("xhttp", "splithttp") && xhttp.isNotEmpty()) map["xhttp-opts"] = xhttp(xhttp, omitted)
        val tcp = stream.obj("tcpSettings").ifEmpty { stream.obj("rawSettings") }
        val tcpHeader = tcp.obj("header")
        if (network in setOf("tcp", "raw") && tcpHeader.isNotEmpty() && tcpHeader.text("type") !in setOf("", "none", "http")) {
            throw IllegalArgumentException("Unsupported TCP header")
        }
        if (network == "grpc" && (stream.obj("grpcSettings")["multiMode"] as? JsonPrimitive)?.booleanOrNull == true) {
            map["grpc-opts"] = (map["grpc-opts"] as? Map<*, *>).orEmpty().filterKeys { it != "grpc-multi-mode" }
            omitted("streamSettings.grpcSettings.multiMode")
        }
    }

    private fun validateTlsVerification(tls: JsonObject) {
        val certificates = tls["certificates"]
        require(certificates == null || certificates is JsonNull || certificates is JsonArray && certificates.isEmpty())
        require((tls["disableSystemRoot"] as? JsonPrimitive)?.booleanOrNull != true)
        for (key in listOf("pinnedPeerCertSha256", "pinnedPeerCertificateChainSha256", "pinnedPeerCertificateSha256", "pinnedPeerCertificatePublicKeySha256", "verifyPeerCertByName")) {
            val value = tls[key] ?: continue
            require(value is JsonNull || value is JsonArray && value.isEmpty() || value is JsonPrimitive && value.content.isBlank())
        }
    }

    private val xhttpKeys = mapOf(
        "path" to "path", "host" to "host", "mode" to "mode", "headers" to "headers",
        "noGRPCHeader" to "no-grpc-header", "noGrpcHeader" to "no-grpc-header", "xPaddingBytes" to "x-padding-bytes",
        "xPaddingObfsMode" to "x-padding-obfs-mode", "xPaddingKey" to "x-padding-key", "xPaddingHeader" to "x-padding-header",
        "xPaddingPlacement" to "x-padding-placement", "xPaddingMethod" to "x-padding-method",
        "uplinkHTTPMethod" to "uplink-http-method", "sessionIDPlacement" to "session-placement", "sessionIDKey" to "session-key",
        "sessionIDTable" to "session-table", "sessionIDLength" to "session-length", "sessionPlacement" to "session-placement", "sessionKey" to "session-key",
        "sessionTable" to "session-table", "sessionLength" to "session-length", "seqPlacement" to "seq-placement",
        "seqKey" to "seq-key", "uplinkDataPlacement" to "uplink-data-placement", "uplinkDataKey" to "uplink-data-key",
        "uplinkChunkSize" to "uplink-chunk-size", "scMaxEachPostBytes" to "sc-max-each-post-bytes", "scMinPostsIntervalMs" to "sc-min-posts-interval-ms"
    )

    private fun xhttp(source: JsonObject, omitted: (String) -> Unit, path: String = "streamSettings.xhttpSettings", allowDownload: Boolean = true): Map<String, Any> {
        val extra = source["extra"]
        require(extra == null || extra is JsonNull || extra is JsonObject)
        // Xray replaces optional fields with extra, retaining only the outer host, path and mode.
        val merged = if (extra is JsonObject) extra + source.filterKeys { it in setOf("host", "path", "mode") } else source
        val result = linkedMapOf<String, Any>()
        for ((key, value) in merged) {
            when (key) {
                "extra" -> Unit
                "xmux" -> {
                    val names = mapOf("maxConcurrency" to "max-concurrency", "maxConnections" to "max-connections", "cMaxReuseTimes" to "c-max-reuse-times", "hMaxRequestTimes" to "h-max-request-times", "hMaxReusableSecs" to "h-max-reusable-secs", "hKeepAlivePeriod" to "h-keep-alive-period")
                    require(value is JsonObject)
                    val opts = value
                    result["reuse-settings"] = reuse(names.mapNotNull { (source, target) -> opts[source]?.let { target to scalar(it) } }.toMap())
                    opts.keys.filterNot { it in names }.forEach { omitted("$path.xmux.${XraySourceFields.safeName(it)}") }
                }
                "downloadSettings" -> {
                    if (!allowDownload) { omitted("$path.downloadSettings"); continue }
                    require(value is JsonObject)
                    result["download-settings"] = download(value, omitted, "$path.downloadSettings")
                }
                else -> {
                    val target = xhttpKeys[key]
                    if (target == null) omitted("$path.${XraySourceFields.safeName(key)}")
                    else if (key == "headers" && value is JsonObject) result[target] = value.mapValues { it.value.jsonPrimitive.content }
                    else if (value is JsonPrimitive && value !is JsonNull) result[target] = scalar(value)
                    else omitted("$path.${XraySourceFields.safeName(key)}")
                }
            }
        }
        return result
    }

    private fun download(source: JsonObject, omitted: (String) -> Unit, path: String): Map<String, Any> {
        val result = linkedMapOf<String, Any>()
        require(network(source) in setOf("xhttp", "splithttp"))
        val server = source.text("address")
        val port = (source["port"] as? JsonPrimitive)?.intOrNull
        require(server.isNotBlank() && port in 1..65535)
        result["server"] = server
        result["port"] = requireNotNull(port)
        val nested = xhttp(source.obj("xhttpSettings").ifEmpty { source.obj("splithttpSettings") }, omitted, "$path.xhttpSettings", allowDownload = false)
        result.putAll(nested.filterKeys { it in setOf("path", "host", "headers", "reuse-settings") })
        nested.keys.filterNot { it in setOf("path", "host", "headers", "reuse-settings") }.forEach { omitted("$path.xhttpSettings.$it") }
        // These are independent Xray streams; Mihomo otherwise inherits the upload stream's values.
        result.putIfAbsent("path", "/")
        result.putIfAbsent("host", "")
        result.putIfAbsent("headers", emptyMap<String, String>())
        result.putIfAbsent("reuse-settings", reuse(emptyMap()))
        val security = source.text("security")
        require(security in setOf("", "none", "tls", "reality"))
        val tls = if (security == "tls") source.obj("tlsSettings") else JsonObject(emptyMap())
        validateTlsVerification(tls)
        result["tls"] = security in setOf("tls", "reality")
        result["servername"] = tls.text("serverName").ifBlank { server }
        result["skip-cert-verify"] = (tls["allowInsecure"] as? JsonPrimitive)?.booleanOrNull == true
        result["client-fingerprint"] = if (security == "tls") tls.text("fingerprint").ifBlank { "chrome" } else ""
        result["alpn"] = tls.array("alpn").map { p -> p.jsonPrimitive.content }
        result["reality-opts"] = mapOf("public-key" to "")
        tls.keys.filterNot { it in setOf("serverName", "allowInsecure", "alpn", "fingerprint", "certificates") }
            .forEach { omitted("$path.tlsSettings.${XraySourceFields.safeName(it)}") }
        val reality = if (security == "reality") source.obj("realitySettings") else JsonObject(emptyMap())
        if (security == "reality") {
            require(validReality(source) && reality.text("mldsa65Verify").isBlank())
            result["reality-opts"] = ConverterSupport.realityOptions(reality.text("publicKey").ifBlank { reality.text("password") }, reality.text("shortId"))
            result["servername"] = reality.text("serverName").ifBlank { server }
            result["client-fingerprint"] = reality.text("fingerprint").ifBlank { "chrome" }
            reality.keys.filterNot { it in setOf("publicKey", "password", "shortId", "serverName", "fingerprint", "show", "mldsa65Verify") }
                .forEach { omitted("$path.realitySettings.${XraySourceFields.safeName(it)}") }
        }
        source.keys.filterNot { it in setOf("address", "port", "network", "method", "security", "tlsSettings", "realitySettings", "xhttpSettings", "splithttpSettings") }
            .forEach { omitted("$path.${XraySourceFields.safeName(it)}") }
        return result
    }

    private fun reuse(source: Map<String, Any>): Map<String, Any> {
        // All six fields are required by the pinned core's strict map decoder.
        val defaults: Map<String, Any> = mapOf("max-concurrency" to "0", "max-connections" to "0",
            "c-max-reuse-times" to "0", "h-max-request-times" to "0", "h-max-reusable-secs" to "0", "h-keep-alive-period" to 0)
        return defaults + source
    }

    private fun scalar(value: JsonElement): Any {
        val primitive = value as? JsonPrimitive ?: return value.toString()
        return primitive.booleanOrNull ?: primitive.intOrNull ?: primitive.content
    }
}
