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

    /** Service/special outbound protocols that never become proxy nodes. */
    private val SERVICE_PROTOCOLS = setOf("freedom", "blackhole", "dns", "dokodemo-door", "api")

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
                            if (tag == "direct" || tag == "block" || tag == "dns-out" || tag == "api") continue

                            val protocol = ob["protocol"]?.jsonPrimitive?.contentOrNull ?: ""
                            if (protocol.lowercase() in SERVICE_PROTOCOLS) continue

                            val settings = ob["settings"]?.jsonObject ?: JsonObject(emptyMap())
                            val streamSettings = ob["streamSettings"]?.jsonObject ?: JsonObject(emptyMap())

                            val proxyMap = parseXrayOutboundToMihomo(remarks, protocol, settings, streamSettings)
                            if (proxyMap == null) {
                                warnings?.add(
                                    ConversionWarning.ProxyParseError(
                                        tag.ifBlank { protocol },
                                        "unsupported protocol or malformed settings"
                                    )
                                )
                            }
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
                        if (tag.isBlank() || tag == "direct" || tag == "block" || tag == "dns-out" || tag == "api") continue

                        val protocol = ob["protocol"]?.jsonPrimitive?.contentOrNull ?: ""
                        if (protocol.lowercase() in SERVICE_PROTOCOLS) continue

                        val settings = ob["settings"]?.jsonObject ?: JsonObject(emptyMap())
                        val streamSettings = ob["streamSettings"]?.jsonObject ?: JsonObject(emptyMap())

                        val proxyMap = parseXrayOutboundToMihomo(tag, protocol, settings, streamSettings)
                        if (proxyMap == null) {
                            warnings?.add(
                                ConversionWarning.ProxyParseError(
                                    tag,
                                    "unsupported protocol or malformed settings"
                                )
                            )
                        }
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
                val encryption = settings["encryption"]?.jsonPrimitive?.contentOrNull
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
                val down = settings["down"]?.jsonPrimitive?.contentOrNull
                    ?: settings["down_mbps"]?.jsonPrimitive?.intOrNull?.let { "$it Mbps" }
                val tlsSettings = streamSettings["tlsSettings"] as? JsonObject ?: JsonObject(emptyMap())

                val map = mutableMapOf<String, Any>(
                    "name" to name,
                    "type" to "hysteria",
                    "server" to address,
                    "port" to port,
                    "udp" to true
                )
                if (auth.isNotBlank()) map["password"] = auth
                if (up != null) map["up"] = up
                if (down != null) map["down"] = down
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
                val user = users?.firstOrNull() as? JsonObject
                user?.get("user")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }?.let { map["username"] = it }
                user?.get("pass")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }?.let { map["password"] = it }
                val tlsSettings = streamSettings["tlsSettings"] as? JsonObject ?: JsonObject(emptyMap())
                if (security == "tls") {
                    map["tls"] = true
                    val sni = tlsSettings["serverName"]?.jsonPrimitive?.contentOrNull ?: address
                    if (sni.isNotBlank() && !sni.matches(Regex("^[0-9.]+$"))) map["sni"] = sni
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
                val user = users?.firstOrNull() as? JsonObject
                user?.get("user")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }?.let { map["username"] = it }
                user?.get("pass")?.jsonPrimitive?.contentOrNull
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
            val wsOpts = mutableMapOf<String, Any>("path" to path)
            val wsHeaders = wsSettings?.get("headers")?.jsonObject
            if (wsHeaders != null && wsHeaders.isNotEmpty()) {
                wsOpts["headers"] = wsHeaders.entries.associate { (k, v) ->
                    k to (v.jsonPrimitive.contentOrNull ?: "")
                }
            }
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
            if (svc.isNotBlank()) {
                val grpcOpts = mutableMapOf<String, Any>("grpc-service-name" to svc)
                if (grpcSettings?.get("multiMode")?.jsonPrimitive?.booleanOrNull == true) {
                    grpcOpts["grpc-multi-mode"] = true
                }
                map["grpc-opts"] = grpcOpts
            }
        }

        // TCP with HTTP obfs header (header.type == "http") -> mihomo http-opts
        if (net == "tcp") {
            val tcpHeader = streamSettings["tcpSettings"]?.jsonObject?.get("header")?.jsonObject
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
            map["h2-opts"] = h2Opts
        }

        // HTTPUpgrade Settings
        if (net == "httpupgrade") {
            val upSettings = streamSettings["httpupgradeSettings"] as? JsonObject
            val path = upSettings?.get("path")?.jsonPrimitive?.contentOrNull ?: "/"
            val host = upSettings?.get("host")?.jsonPrimitive?.contentOrNull ?: ""
            val opts = mutableMapOf<String, Any>("path" to path)
            if (host.isNotBlank()) opts["host"] = host
            map["httpupgrade-opts"] = opts
        }

        // mKCP Settings
        if (net == "kcp") {
            val kcpSettings = streamSettings["kcpSettings"] as? JsonObject
            val opts = mutableMapOf<String, Any>()
            kcpSettings?.get("mtu")?.jsonPrimitive?.intOrNull?.let { opts["mtu"] = it }
            kcpSettings?.get("seed")?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }?.let { opts["seed"] = it }
            val headerType = kcpSettings?.get("header")?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull
            if (!headerType.isNullOrBlank() && headerType != "none") {
                opts["header"] = mapOf("type" to headerType)
            }
            map["kcp-opts"] = opts
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
