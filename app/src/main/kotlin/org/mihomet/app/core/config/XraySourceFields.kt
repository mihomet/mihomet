package org.mihomet.app.core.config

import kotlinx.serialization.json.*
import org.mihomet.app.core.config.XrayJsonConverter.obj
import org.mihomet.app.core.config.XrayJsonConverter.text

/** Audit original fields before normalization can erase malformed shapes or unsupported options. */
internal object XraySourceFields {
    fun safeName(key: String): String = key.takeIf { it in setOf("redirect", "domainStrategy", "userLevel", "fragment", "noises",
        "enableSessionResumption", "minVersion", "maxVersion", "cipherSuites", "masterKeyLog", "echConfigList", "echServerKeys",
        "tcpFastOpen", "tcpKeepAliveIdle", "tcpKeepAliveInterval", "mark", "interface", "sendThrough") } ?: "unmapped"

    private fun objectOrAbsent(value: JsonElement?): Boolean = value == null || value is JsonNull || value is JsonObject

    fun validShape(outbound: JsonObject): Boolean {
        if (!objectOrAbsent(outbound["settings"]) || !objectOrAbsent(outbound["streamSettings"]) || !objectOrAbsent(outbound["proxySettings"])) return false
        val stream = outbound.obj("streamSettings")
        if (!objectOrAbsent(stream["sockopt"])) return false
        if (listOf("network", "method", "security").any { key ->
                val value = stream[key]
                value != null && value !is JsonNull && (value !is JsonPrimitive || !value.isString)
            }) return false
        val active = when (XrayTransportOptions.network(stream)) {
            "tcp", "raw" -> listOf("tcpSettings", "rawSettings")
            "ws", "websocket" -> listOf("wsSettings")
            "grpc" -> listOf("grpcSettings")
            "httpupgrade" -> listOf("httpupgradeSettings")
            "xhttp", "splithttp" -> listOf("xhttpSettings", "splithttpSettings")
            "http", "h2" -> listOf("httpSettings")
            "kcp", "mkcp" -> listOf("kcpSettings", "mkcpSettings")
            else -> emptyList()
        } + when (stream.text("security")) {
            "tls", "xtls" -> listOf("tlsSettings", "xtlsSettings")
            "reality" -> listOf("realitySettings")
            else -> emptyList()
        }
        return active.all { objectOrAbsent(stream[it]) }
    }

    fun reportUnmappedFields(outbound: JsonObject, settings: JsonObject, omitted: (String) -> Unit) {
        fun fields(source: JsonObject, mapped: Set<String>, path: String) {
            // Unknown keys can themselves contain credentials; persist only generated schema paths.
            if (source.keys.any { it !in mapped }) omitted("$path.unmapped")
            if ((source["level"] as? JsonPrimitive)?.intOrNull?.let { it != 0 } == true) omitted("$path.level")
        }
        fields(outbound, setOf("protocol", "tag", "settings", "streamSettings", "proxySettings", "mux", "remarks"), "outbound")
        fields(outbound.obj("proxySettings"), setOf("tag", "transportLayer"), "proxySettings")
        val protocol = outbound.text("protocol").lowercase()
        val common = setOf("address", "port", "level", "email", "servers")
        val keys = when (protocol) {
            "vless" -> setOf("id", "flow", "encryption", "packetEncoding")
            "vmess" -> setOf("id", "alterId", "security", "packetEncoding")
            "trojan" -> setOf("password")
            "shadowsocks" -> setOf("method", "password")
            "http" -> setOf("users", "user", "pass", "headers")
            "socks" -> setOf("users", "user", "pass")
            // These legacy panel extensions have their own parsers, outside the Xray mapping scope.
            else -> null
        }
        if (keys != null) {
            fields(settings, common + keys, "settings")
            (settings["servers"] as? JsonArray)?.filterIsInstance<JsonObject>()?.forEach { fields(it, common + keys, "settings.servers") }
            (settings["users"] as? JsonArray)?.filterIsInstance<JsonObject>()?.forEach { fields(it, setOf("user", "pass", "level", "email"), "settings.users") }
        }
        val stream = outbound.obj("streamSettings")
        when (XrayTransportOptions.network(stream)) {
            "ws", "websocket" -> fields(stream.obj("wsSettings"), setOf("host", "path", "headers", "maxEarlyData", "earlyDataHeaderName"), "streamSettings.wsSettings")
            "grpc" -> fields(stream.obj("grpcSettings"), setOf("serviceName", "multiMode", "user_agent"), "streamSettings.grpcSettings")
            "httpupgrade" -> fields(stream.obj("httpupgradeSettings"), setOf("host", "path", "headers"), "streamSettings.httpupgradeSettings")
            "http", "h2" -> fields(stream.obj("httpSettings"), setOf("host", "path"), "streamSettings.httpSettings")
            "tcp", "raw" -> {
                val tcp = stream.obj("tcpSettings").ifEmpty { stream.obj("rawSettings") }
                fields(tcp, setOf("header"), "streamSettings.tcpSettings")
                fields(tcp.obj("header"), setOf("type", "request"), "streamSettings.tcpSettings.header")
                fields(tcp.obj("header").obj("request"), setOf("method", "path", "headers"), "streamSettings.tcpSettings.header.request")
            }
        }
    }
}
