package org.mihomet.app.core.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.StringWriter

/**
 * Sing-Box JSON branch of the converter: maps all modern Sing-Box protocols
 * and transports, preserves dynamic urltest/selector groups and converts the
 * route rules to Mihomo rule syntax.
 */
internal object SingBoxConverter {

    fun isSingBoxJson(str: String): Boolean {
        if (!str.startsWith("{")) return false
        // Sing-Box has "route" (singular) or outbounds with "server_port" / "type": "selector" / "type": "urltest"
        if (str.contains("\"route\"") && str.contains("\"outbounds\"")) return true
        if (str.contains("\"server_port\"")) return true
        if (str.contains("\"type\"") && (str.contains("\"urltest\"") || str.contains("\"selector\""))) return true
        if (str.contains("\"outbounds\"") && !str.contains("\"vnext\"") && !str.contains("\"routing\"") && !str.contains("\"burstObservatory\"")) {
            return true
        }
        return false
    }

    /**
     * Converts Sing-Box JSON to Mihomo YAML preserving all dynamic urltest and selector groups
     * and supporting all modern Sing-Box protocols and transports.
     */
    fun convertSingBoxJsonToMihomoYaml(jsonStr: String, warnings: MutableList<ConversionWarning>? = null): String {
        try {
            val root = ConverterSupport.json.parseToJsonElement(jsonStr).jsonObject
            val outbounds = root["outbounds"]?.jsonArray ?: return jsonStr

            val proxies = mutableListOf<Map<String, Any>>()
            val proxyGroups = mutableListOf<Map<String, Any>>()
            val proxyNames = mutableListOf<String>()

            for (obEl in outbounds) {
                val ob = obEl as? JsonObject ?: continue
                val type = ob["type"]?.jsonPrimitive?.contentOrNull ?: continue
                val tag = ob["tag"]?.jsonPrimitive?.contentOrNull ?: continue

                when (type) {
                    "urltest" -> {
                        val url = ob["url"]?.jsonPrimitive?.contentOrNull ?: "http://www.gstatic.com/generate_204"
                        val intervalStr = ob["interval"]?.jsonPrimitive?.contentOrNull ?: "300s"
                        val intervalSec = ConverterSupport.parseSeconds(intervalStr, default = 300)
                        val tolerance = ob["tolerance"]?.jsonPrimitive?.intOrNull ?: 50
                        val obs = ob["outbounds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

                        proxyGroups.add(
                            mutableMapOf(
                                "name" to tag,
                                "type" to "url-test",
                                "url" to url,
                                "interval" to intervalSec,
                                "tolerance" to tolerance,
                                "proxies" to obs
                            )
                        )
                    }
                    "selector" -> {
                        val obs = ob["outbounds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                        proxyGroups.add(
                            mutableMapOf(
                                "name" to tag,
                                "type" to "select",
                                "proxies" to obs
                            )
                        )
                    }
                    "direct", "block", "dns" -> {
                        // Built-in sing-box outbounds
                    }
                    else -> {
                        // Proxy outbound (vless, hysteria2, hysteria, vmess, shadowsocks, trojan, tuic, wireguard)
                        val proxyMap = parseSingBoxOutboundToMihomo(tag, type, ob)
                        if (proxyMap != null) {
                            proxies.add(proxyMap)
                            proxyNames.add(tag)
                        } else if (type !in setOf("direct", "block", "dns")) {
                            warnings?.add(ConversionWarning.UnsupportedOutbound(type, tag))
                        }
                    }
                }
            }

            if (proxies.isEmpty()) {
                warnings?.add(ConversionWarning.NoNodesSingBox)
                return jsonStr
            }

            // If no selector group was defined in Sing-Box, create standard selector group
            if (proxyGroups.none { it["type"] == "select" }) {
                proxyGroups.add(
                    mutableMapOf(
                        "name" to "PROXY",
                        "type" to "select",
                        "proxies" to proxyNames
                    )
                )
            }

            // Convert sing-box route rules
            val rulesList = mutableListOf<String>()
            val primaryTarget = proxyGroups.firstOrNull { it["type"] == "select" }?.get("name")?.toString()
                ?: proxyGroups.firstOrNull()?.get("name")?.toString()
                ?: "DIRECT"

            val routeRules = root["route"]?.jsonObject?.get("rules")?.jsonArray
            routeRules?.filterIsInstance<JsonObject>()?.forEach { r ->
                val targetOutbound = r["outbound"]?.jsonPrimitive?.contentOrNull
                    ?: if (r["action"]?.jsonPrimitive?.contentOrNull == "reject") "block"
                    else if (r["action"]?.jsonPrimitive?.contentOrNull == "direct") "direct"
                    else return@forEach

                val target = when (targetOutbound) {
                    "direct" -> "DIRECT"
                    "block" -> "REJECT"
                    else -> targetOutbound
                }

                jsonStringList(r["rule_set"]).forEach { rs ->
                    if (rs.startsWith("geoip-") || rs.contains("geoip")) {
                        rulesList.add("GEOIP,${rs.removePrefix("geoip-").uppercase()},$target")
                    } else if (rs.startsWith("geosite-") || rs.contains("geosite")) {
                        rulesList.add("GEOSITE,${rs.removePrefix("geosite-").uppercase()},$target")
                    }
                }

                jsonStringList(r["geosite"]).forEach {
                    rulesList.add("GEOSITE,$it,$target")
                }
                jsonStringList(r["geoip"]).forEach {
                    rulesList.add("GEOIP,${it.uppercase()},$target")
                }
                jsonStringList(r["domain"]).forEach {
                    rulesList.add("DOMAIN,$it,$target")
                }
                jsonStringList(r["domain_suffix"]).forEach {
                    rulesList.add("DOMAIN-SUFFIX,$it,$target")
                }
                jsonStringList(r["domain_keyword"]).forEach {
                    rulesList.add("DOMAIN-KEYWORD,$it,$target")
                }
                jsonStringList(r["domain_regex"]).forEach {
                    rulesList.add("DOMAIN-REGEX,$it,$target")
                }
                jsonStringList(r["ip_cidr"]).forEach {
                    rulesList.add("IP-CIDR,$it,$target,no-resolve")
                }
                jsonStringList(r["port"]).forEach {
                    rulesList.add("DST-PORT,$it,$target")
                }
            }

            if (rulesList.none { it.startsWith("MATCH,") }) {
                rulesList.add("MATCH,$primaryTarget")
            }

            val rootConfig = mutableMapOf<String, Any>(
                "mixed-port" to 7890,
                "allow-lan" to false,
                "mode" to "rule",
                "log-level" to "info",
                "proxies" to proxies,
                "proxy-groups" to proxyGroups,
                "rules" to rulesList
            )

            val dumperOptions = DumperOptions().apply {
                defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
                isPrettyFlow = true
                indent = 2
            }
            val writer = StringWriter()
            Yaml(dumperOptions).dump(rootConfig, writer)
            return writer.toString()
        } catch (e: Exception) {
            warnings?.add(ConversionWarning.SingBoxError(e.message))
            return jsonStr
        }
    }

    private fun parseSingBoxOutboundToMihomo(tag: String, type: String, ob: JsonObject): Map<String, Any>? {
        val server = ob["server"]?.jsonPrimitive?.contentOrNull ?: return null
        val port = ob["server_port"]?.jsonPrimitive?.intOrNull ?: return null

        return when (type) {
            "vless" -> {
                val uuid = ob["uuid"]?.jsonPrimitive?.contentOrNull ?: return null
                val flow = ob["flow"]?.jsonPrimitive?.contentOrNull ?: ""
                val map = mutableMapOf<String, Any>(
                    "name" to tag,
                    "type" to "vless",
                    "server" to server,
                    "port" to port,
                    "uuid" to uuid
                )
                if (flow.isNotBlank()) map["flow"] = flow

                applySingBoxTls(map, ob["tls"]?.jsonObject, server)
                applySingBoxTransport(map, ob["transport"]?.jsonObject)
                applyMultiplex(map, ob["multiplex"]?.jsonObject)
                map
            }
            "hysteria2" -> {
                val pass = ob["password"]?.jsonPrimitive?.contentOrNull ?: ""
                val map = mutableMapOf<String, Any>(
                    "name" to tag,
                    "type" to "hysteria2",
                    "server" to server,
                    "port" to port,
                    "password" to pass,
                    "udp" to true
                )
                val tlsObj = ob["tls"]?.jsonObject
                val sni = tlsObj?.get("server_name")?.jsonPrimitive?.contentOrNull
                    ?: if (!server.matches(Regex("^[0-9.]+$")) && !server.contains(":")) server else null
                if (!sni.isNullOrBlank()) map["sni"] = sni
                map["alpn"] = listOf("h3")

                val insecure = tlsObj?.get("insecure")?.jsonPrimitive?.booleanOrNull == true
                if (insecure) map["skip-cert-verify"] = true

                val serverPorts = ob["server_ports"]?.jsonPrimitive?.contentOrNull
                if (!serverPorts.isNullOrBlank()) {
                    map["ports"] = serverPorts.replace(":", "-")
                }

                val obfs = ob["obfs"]?.jsonObject
                val obfsType = obfs?.get("type")?.jsonPrimitive?.contentOrNull
                val obfsPass = obfs?.get("password")?.jsonPrimitive?.contentOrNull
                if (!obfsType.isNullOrBlank() && !obfsPass.isNullOrBlank()) {
                    map["obfs"] = obfsType
                    map["obfs-password"] = obfsPass
                }
                map
            }
            "hysteria" -> {
                val map = mutableMapOf<String, Any>(
                    "name" to tag,
                    "type" to "hysteria",
                    "server" to server,
                    "port" to port,
                    // mihomo maps hysteria v1 auth to `password`
                    "password" to (ob["auth_str"]?.jsonPrimitive?.contentOrNull
                        ?: ob["password"]?.jsonPrimitive?.contentOrNull ?: ""),
                    "udp" to true
                )
                ob["up_mbps"]?.jsonPrimitive?.intOrNull?.let { map["up"] = "$it Mbps" }
                ob["down_mbps"]?.jsonPrimitive?.intOrNull?.let { map["down"] = "$it Mbps" }
                val sni = ob["tls"]?.jsonObject?.get("server_name")?.jsonPrimitive?.contentOrNull
                if (!sni.isNullOrBlank()) map["sni"] = sni
                map
            }
            "vmess" -> {
                val uuid = ob["uuid"]?.jsonPrimitive?.contentOrNull ?: return null
                val alterId = ob["alter_id"]?.jsonPrimitive?.intOrNull ?: 0
                val security = ob["security"]?.jsonPrimitive?.contentOrNull ?: "auto"
                val map = mutableMapOf<String, Any>(
                    "name" to tag,
                    "type" to "vmess",
                    "server" to server,
                    "port" to port,
                    "uuid" to uuid,
                    "alterId" to alterId,
                    "cipher" to security
                )
                applySingBoxTls(map, ob["tls"]?.jsonObject, server)
                applySingBoxTransport(map, ob["transport"]?.jsonObject)
                applyMultiplex(map, ob["multiplex"]?.jsonObject)
                map
            }
            "trojan" -> {
                val pass = ob["password"]?.jsonPrimitive?.contentOrNull ?: return null
                val map = mutableMapOf<String, Any>(
                    "name" to tag,
                    "type" to "trojan",
                    "server" to server,
                    "port" to port,
                    "password" to pass
                )
                applySingBoxTls(map, ob["tls"]?.jsonObject, server)
                applySingBoxTransport(map, ob["transport"]?.jsonObject)
                applyMultiplex(map, ob["multiplex"]?.jsonObject)
                map
            }
            "shadowsocks" -> {
                val method = ob["method"]?.jsonPrimitive?.contentOrNull ?: return null
                val password = ob["password"]?.jsonPrimitive?.contentOrNull ?: return null
                val map = mutableMapOf<String, Any>(
                    "name" to tag,
                    "type" to "ss",
                    "server" to server,
                    "port" to port,
                    "cipher" to method,
                    "password" to password
                )
                val plugin = ob["plugin"]?.jsonPrimitive?.contentOrNull
                val pluginOpts = ob["plugin_opts"]?.jsonPrimitive?.contentOrNull
                if (!plugin.isNullOrBlank()) {
                    // Normalize sing-box plugin names to mihomo equivalents and parse
                    // the "k=v;k=v" opts string into the mihomo plugin-opts MAP
                    // (mihomo rejects a raw string there).
                    map["plugin"] = when (plugin) {
                        "obfs-local", "simple-obfs" -> "obfs"
                        else -> plugin
                    }
                    if (!pluginOpts.isNullOrBlank()) {
                        val opts = mutableMapOf<String, Any>()
                        for (part in pluginOpts.split(";")) {
                            val kv = part.split("=", limit = 2)
                            when (kv.getOrNull(0)) {
                                "obfs" -> opts["mode"] = kv.getOrNull(1) ?: "http"
                                "obfs-host" -> kv.getOrNull(1)?.let { opts["host"] = it }
                                "mode" -> opts["mode"] = kv.getOrNull(1) ?: "websocket"
                                "host" -> kv.getOrNull(1)?.let { opts["host"] = it }
                                "path" -> kv.getOrNull(1)?.let { opts["path"] = it }
                                "tls" -> opts["tls"] = true
                            }
                        }
                        if (opts.isNotEmpty()) map["plugin-opts"] = opts
                    }
                }
                map
            }
            "tuic" -> {
                val uuid = ob["uuid"]?.jsonPrimitive?.contentOrNull ?: ""
                val pass = ob["password"]?.jsonPrimitive?.contentOrNull ?: ""
                // sing-box renamed the field across versions: accept both spellings.
                val cc = ob["congestion_control"]?.jsonPrimitive?.contentOrNull
                    ?: ob["congestion_controller"]?.jsonPrimitive?.contentOrNull ?: "bbr"
                val map = mutableMapOf<String, Any>(
                    "name" to tag,
                    "type" to "tuic",
                    "server" to server,
                    "port" to port,
                    "uuid" to uuid,
                    "password" to pass,
                    "congestion-controller" to cc
                )
                ob["udp_relay_mode"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }?.let { map["udp-relay-mode"] = it }
                if (ob["reduce_rtt"]?.jsonPrimitive?.booleanOrNull == true) map["reduce-rtt"] = true
                val alpn = jsonStringList(ob["tls"]?.jsonObject?.get("alpn"))
                if (alpn.isNotEmpty()) map["alpn"] = alpn
                val sni = ob["tls"]?.jsonObject?.get("server_name")?.jsonPrimitive?.contentOrNull
                if (!sni.isNullOrBlank()) map["sni"] = sni
                map
            }
            "http" -> {
                // sing-box http outbound -> mihomo http proxy.
                val map = mutableMapOf<String, Any>(
                    "name" to tag,
                    "type" to "http",
                    "server" to server,
                    "port" to port
                )
                ob["username"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }?.let { map["username"] = it }
                ob["password"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }?.let { map["password"] = it }
                applySingBoxTls(map, ob["tls"]?.jsonObject, server)
                map
            }
            "anytls" -> {
                // mihomo supports the anytls protocol natively.
                val map = mutableMapOf<String, Any>(
                    "name" to tag,
                    "type" to "anytls",
                    "server" to server,
                    "port" to port,
                    "password" to (ob["password"]?.jsonPrimitive?.contentOrNull ?: "")
                )
                applySingBoxTls(map, ob["tls"]?.jsonObject, server)
                applySingBoxTransport(map, ob["transport"]?.jsonObject)
                map
            }
            "shadowtls" -> {
                // mihomo shadowtls: server, port, version, password + client TLS params.
                val map = mutableMapOf<String, Any>(
                    "name" to tag,
                    "type" to "shadowtls",
                    "server" to server,
                    "port" to port,
                    "version" to (ob["version"]?.jsonPrimitive?.intOrNull ?: 3),
                    "password" to (ob["password"]?.jsonPrimitive?.contentOrNull ?: "")
                )
                applySingBoxTls(map, ob["tls"]?.jsonObject, server)
                map
            }
            "wireguard" -> {
                val privateKey = ob["private_key"]?.jsonPrimitive?.contentOrNull ?: ""
                val peerPublicKey = ob["peer_public_key"]?.jsonPrimitive?.contentOrNull ?: ""
                val preSharedKey = ob["pre_shared_key"]?.jsonPrimitive?.contentOrNull
                // sing-box allows local_address as either an array or a single string
                val localAddress = jsonStringList(ob["local_address"])

                val map = mutableMapOf<String, Any>(
                    "name" to tag,
                    "type" to "wireguard",
                    "server" to server,
                    "port" to port,
                    "private-key" to privateKey,
                    "public-key" to peerPublicKey
                )
                if (localAddress.isNotEmpty()) map["ip"] = localAddress.first()
                if (!preSharedKey.isNullOrBlank()) map["preshared-key"] = preSharedKey
                map
            }
            else -> null
        }
    }

    private fun applySingBoxTls(map: MutableMap<String, Any>, tls: JsonObject?, fallbackServer: String) {
        if (tls == null || tls["enabled"]?.jsonPrimitive?.booleanOrNull != true) return
        map["tls"] = true
        val sni = tls["server_name"]?.jsonPrimitive?.contentOrNull ?: fallbackServer
        map["servername"] = sni

        val insecure = tls["insecure"]?.jsonPrimitive?.booleanOrNull ?: false
        if (insecure) map["skip-cert-verify"] = true

        val alpn = jsonStringList(tls["alpn"])
        if (alpn.isNotEmpty()) map["alpn"] = alpn

        val utls = tls["utls"]?.jsonObject
        val fp = utls?.get("fingerprint")?.jsonPrimitive?.contentOrNull
        if (!fp.isNullOrBlank()) map["client-fingerprint"] = fp

        val reality = tls["reality"]?.jsonObject
        if (reality != null && reality["enabled"]?.jsonPrimitive?.booleanOrNull == true) {
            val pubKey = reality["public_key"]?.jsonPrimitive?.contentOrNull ?: ""
            val shortId = reality["short_id"]?.jsonPrimitive?.contentOrNull ?: ""
            map["reality-opts"] = mapOf(
                "public-key" to pubKey,
                "short-id" to shortId
            )
        }
    }

    private fun applySingBoxTransport(map: MutableMap<String, Any>, transport: JsonObject?) {
        if (transport == null) return
        val tType = transport["type"]?.jsonPrimitive?.contentOrNull ?: return

        when (tType) {
            "ws" -> {
                map["network"] = "ws"
                val path = transport["path"]?.jsonPrimitive?.contentOrNull ?: "/"
                val wsOpts = mutableMapOf<String, Any>("path" to path)
                val wsHeaders = transport["headers"]?.jsonObject
                if (wsHeaders != null && wsHeaders.isNotEmpty()) {
                    wsOpts["headers"] = wsHeaders.entries.associate { (k, v) ->
                        k to (v.jsonPrimitive.contentOrNull ?: "")
                    }
                }
                val maxEarly = transport["max_early_data"]?.jsonPrimitive?.intOrNull
                if (maxEarly != null && maxEarly > 0) {
                    wsOpts["max-early-data"] = maxEarly
                    val earlyHeader = transport["early_data_header_name"]?.jsonPrimitive?.contentOrNull
                    if (!earlyHeader.isNullOrBlank()) wsOpts["early-data-header-name"] = earlyHeader
                }
                map["ws-opts"] = wsOpts
            }
            "httpupgrade" -> {
                // httpupgrade is its own mihomo network — do NOT fold it into ws.
                map["network"] = "httpupgrade"
                val path = transport["path"]?.jsonPrimitive?.contentOrNull ?: "/"
                val host = transport["host"]?.jsonPrimitive?.contentOrNull
                    ?: transport["headers"]?.jsonObject?.get("Host")?.jsonPrimitive?.contentOrNull ?: ""
                val opts = mutableMapOf<String, Any>("path" to path)
                if (host.isNotBlank()) opts["host"] = host
                map["httpupgrade-opts"] = opts
            }
            "grpc" -> {
                map["network"] = "grpc"
                val svc = transport["service_name"]?.jsonPrimitive?.contentOrNull ?: ""
                if (svc.isNotBlank()) {
                    map["grpc-opts"] = mapOf("grpc-service-name" to svc)
                }
            }
            "http", "h2" -> {
                map["network"] = "h2"
                val path = transport["path"]?.jsonPrimitive?.contentOrNull ?: "/"
                val host = jsonStringList(transport["host"])
                val h2Opts = mutableMapOf<String, Any>("path" to path)
                if (host.isNotEmpty()) h2Opts["host"] = host
                map["h2-opts"] = h2Opts
            }
        }
        // Multiplex (smux / h2mux / yamux) -> mihomo smux block.
        val mux = transport["multiplex"]?.jsonObject
            ?: transport["transport"]?.jsonObject?.get("multiplex")?.jsonObject
        if (mux == null && transport["multiplex"] == null) {
            // multiplex may also sit on the outbound itself (handled by callers via applyMultiplex)
        }
    }

    /** Multiplex block (sing-box "multiplex") -> mihomo "smux". */
    private fun applyMultiplex(map: MutableMap<String, Any>, mux: JsonObject?) {
        if (mux?.get("enabled")?.jsonPrimitive?.booleanOrNull != true) return
        val smux = mutableMapOf<String, Any>()
        mux["protocol"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { smux["protocol"] = it }
        mux["max_connections"]?.jsonPrimitive?.intOrNull?.let { smux["max-connections"] = it }
        mux["min_streams"]?.jsonPrimitive?.intOrNull?.let { smux["min-streams"] = it }
        mux["max_streams"]?.jsonPrimitive?.intOrNull?.let { smux["max-streams"] = it }
        if (mux["padding"]?.jsonPrimitive?.booleanOrNull == true) smux["padding"] = true
        if (smux.isNotEmpty()) map["smux"] = smux
    }

    /**
     * Reads a JSON field that panels emit either as an array of strings or as a
     * single string (both are legal in sing-box configs, e.g. "alpn": "h3").
     */
    private fun jsonStringList(el: JsonElement?): List<String> = when (el) {
        null -> emptyList()
        is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(el.contentOrNull)
        else -> emptyList()
    }
}
