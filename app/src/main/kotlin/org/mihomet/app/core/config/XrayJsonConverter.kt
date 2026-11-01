package org.mihomet.app.core.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
 * Xray JSON branch of the converter (Happ/Incy/v2rayNG payloads, both the
 * JSON-array form and the single-object form): maps outbounds to Mihomo
 * proxies, rebuilds balancers as url-test groups and converts the routing
 * rules to Mihomo rule syntax.
 */
internal object XrayJsonConverter {

    fun isXrayJson(str: String): Boolean {
        if (str.startsWith("[")) return true
        if (!str.startsWith("{")) return false
        return str.contains("\"routing\"") || str.contains("\"burstObservatory\"") || str.contains("\"observatory\"") ||
                str.contains("\"vnext\"") || str.contains("\"streamSettings\"")
    }

    /**
     * Converts Xray configurations (both JSON array from Happ/Incy/v2rayNG and single JSON object)
     * into Mihomo YAML with all balancers, nodes, and routing rules preserved dynamically.
     */
    fun convertXrayJsonToMihomoYaml(jsonStr: String, warnings: MutableList<ConversionWarning>? = null): String {
        try {
            val rootElement = ConverterSupport.json.parseToJsonElement(jsonStr)
            val items: List<JsonObject> = when (rootElement) {
                is JsonArray -> rootElement.filterIsInstance<JsonObject>()
                is JsonObject -> listOf(rootElement)
                else -> return jsonStr
            }

            // 1. Collect all individual proxy nodes and map their address:port to remarks
            val proxies = mutableListOf<Map<String, Any>>()
            val addrToRemarks = mutableMapOf<String, String>()
            val serverNames = mutableListOf<String>()

            for (item in items) {
                val remarks = item["remarks"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
                val balancers = item["routing"]?.jsonObject?.get("balancers")?.jsonArray
                val isBalancerItem = !balancers.isNullOrEmpty()

                if (!isBalancerItem && remarks.isNotBlank()) {
                    val outbounds = item["outbounds"]?.jsonArray
                    if (outbounds != null) {
                        for (obEl in outbounds) {
                            val ob = obEl as? JsonObject ?: continue
                            val tag = ob["tag"]?.jsonPrimitive?.contentOrNull ?: ""
                            if (tag == "direct" || tag == "block" || tag == "dns-out") continue

                            val protocol = ob["protocol"]?.jsonPrimitive?.contentOrNull ?: ""
                            val settings = ob["settings"]?.jsonObject ?: JsonObject(emptyMap())
                            val streamSettings = ob["streamSettings"]?.jsonObject ?: JsonObject(emptyMap())

                            val proxyMap = parseXrayOutboundToMihomo(remarks, protocol, settings, streamSettings)
                            if (proxyMap != null) {
                                val srv = proxyMap["server"]?.toString() ?: ""
                                val port = proxyMap["port"]?.toString() ?: ""
                                if (srv.isNotBlank() && port.isNotBlank()) {
                                    addrToRemarks["$srv:$port"] = remarks
                                }
                                proxies.add(proxyMap)
                                serverNames.add(remarks)
                                break
                            }
                        }
                    }
                }
            }

            // If no separate items had remarks (e.g. single config with tag-based outbounds):
            if (proxies.isEmpty()) {
                for (item in items) {
                    val outbounds = item["outbounds"]?.jsonArray ?: continue
                    for (obEl in outbounds) {
                        val ob = obEl as? JsonObject ?: continue
                        val tag = ob["tag"]?.jsonPrimitive?.contentOrNull ?: ""
                        if (tag.isBlank() || tag == "direct" || tag == "block" || tag == "dns-out") continue

                        val protocol = ob["protocol"]?.jsonPrimitive?.contentOrNull ?: ""
                        val settings = ob["settings"]?.jsonObject ?: JsonObject(emptyMap())
                        val streamSettings = ob["streamSettings"]?.jsonObject ?: JsonObject(emptyMap())

                        val proxyMap = parseXrayOutboundToMihomo(tag, protocol, settings, streamSettings)
                        if (proxyMap != null) {
                            proxies.add(proxyMap)
                            serverNames.add(tag)
                            val srv = proxyMap["server"]?.toString() ?: ""
                            val port = proxyMap["port"]?.toString() ?: ""
                            if (srv.isNotBlank() && port.isNotBlank()) {
                                addrToRemarks["$srv:$port"] = tag
                            }
                        }
                    }
                }
            }

            if (serverNames.isEmpty()) {
                warnings?.add(ConversionWarning.NoNodesXray)
                return jsonStr
            }

            // 2. Extract Balancers from items
            val proxyGroups = mutableListOf<Map<String, Any>>()
            val balancerNames = mutableListOf<String>()
            val balancerTagToGroupName = mutableMapOf<String, String>()
            val rawRulesList = mutableListOf<JsonObject>()

            for (item in items) {
                val remarks = item["remarks"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
                val routing = item["routing"]?.jsonObject ?: continue
                val balancers = routing["balancers"]?.jsonArray

                // Collect routing rules
                routing["rules"]?.jsonArray?.filterIsInstance<JsonObject>()?.let {
                    rawRulesList.addAll(it)
                }

                if (!balancers.isNullOrEmpty()) {
                    val burstPing = item["burstObservatory"]?.jsonObject?.get("pingConfig")?.jsonObject
                        ?: item["observatory"]?.jsonObject?.get("pingConfig")?.jsonObject

                    val destUrl = burstPing?.get("destination")?.jsonPrimitive?.contentOrNull
                        ?: "http://www.gstatic.com/generate_204"

                    val intervalStr = burstPing?.get("interval")?.jsonPrimitive?.contentOrNull ?: "300s"
                    val intervalSec = ConverterSupport.parseSeconds(intervalStr, default = 300)

                    // Map item outbounds tags to node names, synthesizing missing nodes if any
                    val itemTagToName = mutableMapOf<String, String>()
                    item["outbounds"]?.jsonArray?.filterIsInstance<JsonObject>()?.forEach { ob ->
                        val tag = ob["tag"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                        if (tag == "direct" || tag == "block" || tag == "dns-out") return@forEach
                        val proto = ob["protocol"]?.jsonPrimitive?.contentOrNull ?: ""
                        val sett = ob["settings"]?.jsonObject ?: JsonObject(emptyMap())
                        val (addr, port) = extractAddressAndPort(proto, sett)
                        if (addr != null && port != null && addrToRemarks.containsKey("$addr:$port")) {
                            itemTagToName[tag] = addrToRemarks["$addr:$port"]!!
                        } else {
                            itemTagToName[tag] = tag
                        }
                    }

                    for (balEl in balancers) {
                        val bal = balEl as? JsonObject ?: continue
                        val bTag = bal["tag"]?.jsonPrimitive?.contentOrNull ?: "balancer"
                        val groupName = if (remarks.isNotBlank()) remarks else bTag
                        balancerTagToGroupName[bTag] = groupName
                        if (!balancerNames.contains(groupName)) {
                            balancerNames.add(groupName)
                        }

                        val selectors = bal["selector"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                        val selectedNodes = mutableListOf<String>()

                        for (sel in selectors) {
                            if (itemTagToName.containsKey(sel)) {
                                val resolved = itemTagToName[sel]!!
                                if (serverNames.contains(resolved) && !selectedNodes.contains(resolved)) {
                                    selectedNodes.add(resolved)
                                }
                            } else {
                                // Match prefix or contains in serverNames
                                val matched = serverNames.filter { it.contains(sel, ignoreCase = true) }
                                matched.forEach { if (!selectedNodes.contains(it)) selectedNodes.add(it) }
                            }
                        }

                        // If strategy specifies high costs (e.g. ^TIER2 has cost 1,000,000,000 as emergency fallback),
                        // filter out high cost nodes from url-test pool if low cost nodes exist
                        val strategySettings = bal["strategy"]?.jsonObject?.get("settings")?.jsonObject
                        val costs = strategySettings?.get("costs")?.jsonArray
                        val highCostPatterns = mutableListOf<Regex>()
                        costs?.filterIsInstance<JsonObject>()?.forEach { c ->
                            val v = c["value"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                            val m = c["match"]?.jsonPrimitive?.contentOrNull ?: ""
                            if (v >= 1000L && m.isNotBlank()) {
                                runCatching { highCostPatterns.add(Regex(m)) }
                            }
                        }

                        val nonHighCostNodes = if (highCostPatterns.isNotEmpty()) {
                            selectedNodes.filter { nodeName ->
                                val origTag = itemTagToName.entries.firstOrNull { it.value == nodeName }?.key ?: nodeName
                                highCostPatterns.none { it.containsMatchIn(origTag) }
                            }
                        } else emptyList()

                        val finalProxies = when {
                            nonHighCostNodes.size >= 2 -> nonHighCostNodes
                            selectedNodes.isNotEmpty() -> selectedNodes
                            else -> serverNames
                        }

                        val autoGroup = mutableMapOf<String, Any>(
                            "name" to groupName,
                            "type" to "url-test",
                            "url" to destUrl,
                            "interval" to intervalSec,
                            "tolerance" to 50,
                            "proxies" to finalProxies
                        )
                        proxyGroups.add(autoGroup)
                    }
                }
            }

            // 3. Create Standard Selector Group (PROXY)
            val mainGroupName = "PROXY"
            val mainProxies = (balancerNames + serverNames).distinct()
            proxyGroups.add(
                mutableMapOf(
                    "name" to mainGroupName,
                    "type" to "select",
                    "proxies" to mainProxies
                )
            )

            // 4. Convert Xray routing rules to Mihomo rules
            val mihomoRules = mutableListOf<String>()
            val primaryTarget = mainGroupName

            for (r in rawRulesList) {
                val bTag = r["balancerTag"]?.jsonPrimitive?.contentOrNull
                val obTag = r["outboundTag"]?.jsonPrimitive?.contentOrNull

                val target = when {
                    bTag != null -> primaryTarget
                    obTag == "direct" -> "DIRECT"
                    obTag == "block" -> "REJECT"
                    obTag != null && serverNames.contains(obTag) -> obTag
                    else -> continue
                }

                // IP rules
                r["ip"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.forEach { ip ->
                    if (ip.startsWith("geoip:")) {
                        mihomoRules.add("GEOIP,${ip.removePrefix("geoip:").uppercase()},$target")
                    } else if (ip.contains("/")) {
                        mihomoRules.add("IP-CIDR,$ip,$target,no-resolve")
                    }
                }

                // Domain rules
                r["domain"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.forEach { d ->
                    when {
                        d.startsWith("geosite:") -> mihomoRules.add("GEOSITE,${d.removePrefix("geosite:")},$target")
                        d.startsWith("domain:") -> mihomoRules.add("DOMAIN-SUFFIX,${d.removePrefix("domain:")},$target")
                        d.startsWith("full:") -> mihomoRules.add("DOMAIN,${d.removePrefix("full:")},$target")
                        d.startsWith("regexp:.*\\.") -> {
                            val suffix = d.removePrefix("regexp:.*\\.").removeSuffix("$").replace("\\", "")
                            if (suffix.isNotBlank()) mihomoRules.add("DOMAIN-SUFFIX,$suffix,$target")
                        }
                    }
                }
            }

            if (mihomoRules.none { it.startsWith("MATCH,") }) {
                mihomoRules.add("MATCH,$primaryTarget")
            }

            val rootConfig = mutableMapOf<String, Any>(
                "mixed-port" to 7890,
                "allow-lan" to false,
                "mode" to "rule",
                "log-level" to "info",
                "proxies" to proxies,
                "proxy-groups" to proxyGroups,
                "rules" to mihomoRules
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
            warnings?.add(ConversionWarning.XrayError(e.message))
            return jsonStr
        }
    }

    private fun parseXrayOutboundToMihomo(
        name: String,
        protocol: String,
        settings: JsonObject,
        streamSettings: JsonObject
    ): Map<String, Any>? {
        val net = streamSettings["network"]?.jsonPrimitive?.contentOrNull ?: "tcp"
        val security = streamSettings["security"]?.jsonPrimitive?.contentOrNull ?: ""

        return when (protocol.lowercase()) {
            "vless" -> {
                val vnextList = settings["vnext"] as? JsonArray ?: return null
                val vnext = vnextList.firstOrNull() as? JsonObject ?: return null
                val address = vnext["address"]?.jsonPrimitive?.contentOrNull ?: return null
                val port = vnext["port"]?.jsonPrimitive?.intOrNull ?: 443

                val usersList = vnext["users"] as? JsonArray ?: return null
                val user = usersList.firstOrNull() as? JsonObject ?: return null
                val uuid = user["id"]?.jsonPrimitive?.contentOrNull ?: return null
                val flow = user["flow"]?.jsonPrimitive?.contentOrNull ?: ""

                val map = mutableMapOf<String, Any>(
                    "name" to name,
                    "type" to "vless",
                    "server" to address,
                    "port" to port,
                    "uuid" to uuid,
                    "network" to if (net == "splithttp") "xhttp" else net
                )
                if (flow.isNotBlank()) map["flow"] = flow

                applyStreamSecurityAndTransport(map, net, security, streamSettings, address)
                map
            }
            "hysteria", "hysteria2" -> {
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
                val vnextList = settings["vnext"] as? JsonArray ?: return null
                val vnext = vnextList.firstOrNull() as? JsonObject ?: return null
                val address = vnext["address"]?.jsonPrimitive?.contentOrNull ?: return null
                val port = vnext["port"]?.jsonPrimitive?.intOrNull ?: 443
                val user = (vnext["users"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
                val uuid = user["id"]?.jsonPrimitive?.contentOrNull ?: return null
                val alterId = user["alterId"]?.jsonPrimitive?.intOrNull ?: 0
                val cipher = user["security"]?.jsonPrimitive?.contentOrNull ?: "auto"

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
                val servers = settings["servers"] as? JsonArray ?: return null
                val srv = servers.firstOrNull() as? JsonObject ?: return null
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
                val servers = settings["servers"] as? JsonArray ?: return null
                val srv = servers.firstOrNull() as? JsonObject ?: return null
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
            "tuic" -> {
                val address = settings["address"]?.jsonPrimitive?.contentOrNull ?: return null
                val port = settings["port"]?.jsonPrimitive?.intOrNull ?: 443
                val uuid = settings["uuid"]?.jsonPrimitive?.contentOrNull ?: ""
                val password = settings["password"]?.jsonPrimitive?.contentOrNull ?: ""
                val congestion = settings["congestion_controller"]?.jsonPrimitive?.contentOrNull ?: "bbr"

                mutableMapOf(
                    "name" to name,
                    "type" to "tuic",
                    "server" to address,
                    "port" to port,
                    "uuid" to uuid,
                    "password" to password,
                    "congestion-controller" to congestion
                )
            }
            "wireguard" -> {
                val address = settings["address"]?.jsonPrimitive?.contentOrNull ?: return null
                val port = settings["port"]?.jsonPrimitive?.intOrNull ?: 51820
                val secretKey = settings["secretKey"]?.jsonPrimitive?.contentOrNull ?: ""
                val peers = settings["peers"] as? JsonArray
                val firstPeer = peers?.firstOrNull()?.jsonObject
                val publicKey = firstPeer?.get("publicKey")?.jsonPrimitive?.contentOrNull ?: ""
                val presharedKey = firstPeer?.get("presharedKey")?.jsonPrimitive?.contentOrNull

                val map = mutableMapOf<String, Any>(
                    "name" to name,
                    "type" to "wireguard",
                    "server" to address,
                    "port" to port,
                    "private-key" to secretKey,
                    "public-key" to publicKey
                )
                if (!presharedKey.isNullOrBlank()) map["preshared-key"] = presharedKey
                map
            }
            else -> null
        }
    }

    private fun applyStreamSecurityAndTransport(
        map: MutableMap<String, Any>,
        net: String,
        security: String,
        streamSettings: JsonObject,
        address: String
    ) {
        if (security == "reality") {
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
            val allowInsecure = tlsSettings["allowInsecure"]?.jsonPrimitive?.booleanOrNull ?: false
            if (allowInsecure) map["skip-cert-verify"] = true
        }

        // WS Settings
        if (net == "ws") {
            val wsSettings = streamSettings["wsSettings"] as? JsonObject
            val path = wsSettings?.get("path")?.jsonPrimitive?.contentOrNull ?: "/"
            val host = wsSettings?.get("headers")?.jsonObject?.get("Host")?.jsonPrimitive?.contentOrNull ?: ""
            val wsOpts = mutableMapOf<String, Any>("path" to path)
            if (host.isNotBlank()) wsOpts["headers"] = mapOf("Host" to host)
            map["ws-opts"] = wsOpts
        }

        // gRPC Settings
        if (net == "grpc") {
            val grpcSettings = streamSettings["grpcSettings"] as? JsonObject
            val svc = grpcSettings?.get("serviceName")?.jsonPrimitive?.contentOrNull ?: ""
            if (svc.isNotBlank()) {
                map["grpc-opts"] = mapOf("grpc-service-name" to svc)
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
            map["h2-opts"] = h2Opts
        }
    }

    private fun extractAddressAndPort(protocol: String, settings: JsonObject): Pair<String?, Int?> {
        return when (protocol) {
            "vless", "vmess" -> {
                val vnext = (settings["vnext"] as? JsonArray)?.firstOrNull() as? JsonObject
                vnext?.get("address")?.jsonPrimitive?.contentOrNull to vnext?.get("port")?.jsonPrimitive?.intOrNull
            }
            "hysteria", "hysteria2" -> {
                val addr = settings["address"]?.jsonPrimitive?.contentOrNull
                    ?: (settings["servers"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("address")?.jsonPrimitive?.contentOrNull
                val port = settings["port"]?.jsonPrimitive?.intOrNull
                    ?: (settings["servers"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("port")?.jsonPrimitive?.intOrNull
                addr to port
            }
            "trojan", "shadowsocks" -> {
                val srv = (settings["servers"] as? JsonArray)?.firstOrNull() as? JsonObject
                srv?.get("address")?.jsonPrimitive?.contentOrNull to srv?.get("port")?.jsonPrimitive?.intOrNull
            }
            else -> null to null
        }
    }
}
