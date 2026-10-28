package org.mihomet.app.core.config

import kotlinx.serialization.json.*
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.StringWriter
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

object UniversalConfigConverter {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Converts any subscription content (Xray JSON, sing-box JSON, URI link list, or Clash YAML)
     * into a fully compliant Mihomo/Clash YAML configuration with 100% dynamic groups, balancers,
     * and rules without any hardcoded node or group names.
     */
    fun convertToMihomoYaml(content: String): String {
        return convertToMihomoYamlWithOutcome(content).yaml
    }

    /**
     * Same as [convertToMihomoYaml] but also reports non-fatal problems collected
     * during conversion so the caller can surface them to the user.
     */
    fun convertToMihomoYamlWithOutcome(content: String): ConversionOutcome {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return ConversionOutcome(content, emptyList())

        val warnings = mutableListOf<String>()

        // Check for Base64 encoded payload
        val decoded = if (isLikelyBase64(trimmed)) {
            runCatching {
                decodeBase64(trimmed)
            }.getOrDefault(trimmed)
        } else {
            trimmed
        }

        val yaml = when {
            isHappRouting(trimmed) -> convertHappRoutingToMihomoYaml(trimmed)
            isHappRouting(decoded) -> convertHappRoutingToMihomoYaml(decoded)
            isClashYaml(decoded) -> validateAndNormalizeYaml(decoded)
            isSingBoxJson(decoded) -> convertSingBoxJsonToMihomoYaml(decoded, warnings)
            isXrayJson(decoded) -> convertXrayJsonToMihomoYaml(decoded, warnings)
            isUriList(decoded) -> convertUriListToMihomoYaml(decoded, warnings)
            else -> {
                warnings.add("Не удалось определить формат конфигурации")
                decoded
            }
        }
        return ConversionOutcome(yaml, warnings)
    }

    private fun isHappRouting(str: String): Boolean {
        val trimmed = str.trim()
        if (trimmed.startsWith("happ://routing/", ignoreCase = true)) return true
        if (trimmed.startsWith("{") && (
                trimmed.contains("\"directSites\"", ignoreCase = true) ||
                trimmed.contains("\"proxySites\"", ignoreCase = true) ||
                trimmed.contains("\"blockSites\"", ignoreCase = true) ||
                trimmed.contains("\"directIp\"", ignoreCase = true) ||
                trimmed.contains("\"proxyIp\"", ignoreCase = true) ||
                trimmed.contains("\"blockIp\"", ignoreCase = true)
            )) {
            return true
        }
        return false
    }

    fun convertHappRoutingToMihomoYaml(content: String): String {
        val trimmed = content.trim()
        val jsonPayload = if (trimmed.startsWith("happ://routing/", ignoreCase = true)) {
            val rawPart = trimmed.substringAfter("://").substringAfter('/').substringAfter('/', "")
                .substringBefore('#').substringBefore('?')
            val unescaped = runCatching { URLDecoder.decode(rawPart, StandardCharsets.UTF_8.name()) }.getOrDefault(rawPart)
            if (unescaped.startsWith("{")) unescaped else decodeBase64(unescaped)
        } else if (trimmed.startsWith("{")) {
            trimmed
        } else {
            runCatching { decodeBase64(trimmed) }.getOrDefault(trimmed)
        }

        val root = runCatching {
            json.parseToJsonElement(jsonPayload).jsonObject
        }.getOrNull() ?: return content

        val fields = root.entries.associate { (k, v) -> k.lowercase() to v }
        val rules = mutableListOf<String>()

        fun extractList(key: String): List<String> {
            val elem = fields[key] ?: return emptyList()
            return when {
                elem is JsonArray -> elem.mapNotNull { if (it is JsonPrimitive) it.contentOrNull else null }
                elem is JsonPrimitive -> elem.content.split(',').map { it.trim() }
                else -> emptyList()
            }.filter { it.isNotBlank() }
        }

        fun addRuleEntries(items: List<String>, target: String) {
            for (item in items) {
                val clean = item.trim()
                if (clean.isBlank()) continue
                when {
                    clean.startsWith("geosite:", ignoreCase = true) -> {
                        rules.add("GEOSITE,${clean.substringAfter(':').uppercase()},$target")
                    }
                    clean.startsWith("geoip:", ignoreCase = true) -> {
                        rules.add("GEOIP,${clean.substringAfter(':').uppercase()},$target")
                    }
                    clean.startsWith("domain:", ignoreCase = true) -> {
                        rules.add("DOMAIN,${clean.substringAfter(':')},$target")
                    }
                    clean.startsWith("full:", ignoreCase = true) -> {
                        rules.add("DOMAIN,${clean.substringAfter(':')},$target")
                    }
                    clean.startsWith("keyword:", ignoreCase = true) -> {
                        rules.add("DOMAIN-KEYWORD,${clean.substringAfter(':')},$target")
                    }
                    clean.startsWith("regexp:", ignoreCase = true) -> {
                        rules.add("DOMAIN-REGEX,${clean.substringAfter(':')},$target")
                    }
                    clean.contains('/') || clean.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+.*")) -> {
                        val cidr = if (!clean.contains('/')) "$clean/32" else clean
                        rules.add("IP-CIDR,$cidr,$target,no-resolve")
                    }
                    clean.contains(':') && clean.contains("::") -> {
                        val cidr = if (!clean.contains('/')) "$clean/128" else clean
                        rules.add("IP-CIDR6,$cidr,$target,no-resolve")
                    }
                    else -> {
                        rules.add("DOMAIN-SUFFIX,$clean,$target")
                    }
                }
            }
        }

        addRuleEntries(extractList("directsites"), "DIRECT")
        addRuleEntries(extractList("directip"), "DIRECT")
        addRuleEntries(extractList("blocksites"), "REJECT")
        addRuleEntries(extractList("blockip"), "REJECT")
        addRuleEntries(extractList("proxysites"), "GLOBAL")
        addRuleEntries(extractList("proxyip"), "GLOBAL")

        val isGlobal = fields["globalproxy"]?.jsonPrimitive?.contentOrNull?.lowercase() != "false"
        if (isGlobal) {
            rules.add("MATCH,GLOBAL")
        } else {
            rules.add("MATCH,DIRECT")
        }

        val yamlMap = linkedMapOf<String, Any>(
            "rules" to rules
        )

        return DumperOptions().let { opt ->
            opt.defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            opt.isPrettyFlow = true
            Yaml(opt).dump(yamlMap)
        }
    }

    private fun decodeBase64(str: String): String {
        val clean = str.replace("\n", "").replace("\r", "").replace(" ", "").trim()
        val padded = when (clean.length % 4) {
            2 -> "$clean=="
            3 -> "$clean="
            else -> clean
        }
        return try {
            val bytes = Base64.getDecoder().decode(padded)
            String(bytes, StandardCharsets.UTF_8).trim()
        } catch (e: Exception) {
            val bytes = Base64.getUrlDecoder().decode(padded)
            String(bytes, StandardCharsets.UTF_8).trim()
        }
    }

    private fun isLikelyBase64(str: String): Boolean {
        if (str.startsWith("{") || str.startsWith("[") || str.startsWith("proxies:") || str.startsWith("port:") || str.startsWith("mixed-port:")) {
            return false
        }
        val clean = str.replace("\n", "").replace("\r", "").replace(" ", "").trim()
        if (clean.length < 16) return false
        val validChars = clean.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '-' || it == '_' }
        return validChars && !clean.contains("://")
    }

    private fun isClashYaml(str: String): Boolean {
        if (str.startsWith("{") || str.startsWith("[")) return false
        return str.contains("proxies:") || str.contains("proxy-groups:") || (str.contains("mixed-port:") && str.contains("rules:"))
    }

    private fun isSingBoxJson(str: String): Boolean {
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

    private fun isXrayJson(str: String): Boolean {
        if (str.startsWith("[")) return true
        if (!str.startsWith("{")) return false
        return str.contains("\"routing\"") || str.contains("\"burstObservatory\"") || str.contains("\"observatory\"") ||
                str.contains("\"vnext\"") || str.contains("\"streamSettings\"")
    }

    private fun isUriList(str: String): Boolean {
        // Panels prepend comment lines (#profile-title, usage notes) to URI lists — skip them
        val firstLine = str.lineSequence().firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("#") } ?: ""
        return firstLine.startsWith("vless://") ||
                firstLine.startsWith("vmess://") ||
                firstLine.startsWith("hysteria2://") ||
                firstLine.startsWith("hy2://") ||
                firstLine.startsWith("hysteria://") ||
                firstLine.startsWith("ss://") ||
                firstLine.startsWith("trojan://") ||
                firstLine.startsWith("tuic://") ||
                firstLine.startsWith("ssh://") ||
                firstLine.startsWith("snell://") ||
                firstLine.startsWith("wg://")
    }

    private fun validateAndNormalizeYaml(yamlContent: String): String {
        return try {
            val yaml = Yaml()
            val parsed = yaml.load<Map<String, Any>>(yamlContent) ?: return yamlContent
            val dumperOptions = DumperOptions().apply {
                defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
                isPrettyFlow = true
                indent = 2
            }
            val writer = StringWriter()
            Yaml(dumperOptions).dump(parsed, writer)
            writer.toString()
        } catch (e: Exception) {
            yamlContent
        }
    }

    /**
     * Converts Xray configurations (both JSON array from Happ/Incy/v2rayNG and single JSON object)
     * into Mihomo YAML with all balancers, nodes, and routing rules preserved dynamically.
     */
    private fun convertXrayJsonToMihomoYaml(jsonStr: String, warnings: MutableList<String>? = null): String {
        try {
            val rootElement = json.parseToJsonElement(jsonStr)
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
                warnings?.add("В Xray-конфиге не найдено ни одного поддерживаемого узла")
                return jsonStr
            }

            // 2. Extract Balancers from items
            val proxyGroups = mutableListOf<Map<String, Any>>()
            val balancerNames = mutableListOf<String>()
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
                    val intervalSec = parseSeconds(intervalStr, default = 300)

                    // Map item outbounds tags to node names
                    val itemTagToName = mutableMapOf<String, String>()
                    item["outbounds"]?.jsonArray?.filterIsInstance<JsonObject>()?.forEach { ob ->
                        val tag = ob["tag"]?.jsonPrimitive?.contentOrNull ?: return@forEach
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

                        val finalProxies = if (selectedNodes.isNotEmpty()) selectedNodes else serverNames

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
            warnings?.add("Ошибка конвертации Xray JSON: ${e.message ?: "неизвестная ошибка"}")
            return jsonStr
        }
    }

    /**
     * Converts Sing-Box JSON to Mihomo YAML preserving all dynamic urltest and selector groups
     * and supporting all modern Sing-Box protocols and transports.
     */
    private fun convertSingBoxJsonToMihomoYaml(jsonStr: String, warnings: MutableList<String>? = null): String {
        try {
            val root = json.parseToJsonElement(jsonStr).jsonObject
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
                        val intervalSec = parseSeconds(intervalStr, default = 300)
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
                            warnings?.add("Тип outbound '$type' ('$tag') не поддерживается конвертером")
                        }
                    }
                }
            }

            if (proxies.isEmpty()) {
                warnings?.add("В Sing-Box конфиге не найдено ни одного поддерживаемого узла")
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
            warnings?.add("Ошибка конвертации Sing-Box JSON: ${e.message ?: "неизвестная ошибка"}")
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
                val authStr = ob["auth_str"]?.jsonPrimitive?.contentOrNull ?: ""
                val map = mutableMapOf<String, Any>(
                    "name" to tag,
                    "type" to "hysteria",
                    "server" to server,
                    "port" to port,
                    "auth_str" to authStr
                )
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
                    map["plugin"] = plugin
                    if (!pluginOpts.isNullOrBlank()) map["plugin-opts"] = pluginOpts
                }
                map
            }
            "tuic" -> {
                val uuid = ob["uuid"]?.jsonPrimitive?.contentOrNull ?: ""
                val pass = ob["password"]?.jsonPrimitive?.contentOrNull ?: ""
                val cc = ob["congestion_controller"]?.jsonPrimitive?.contentOrNull ?: "bbr"
                val map = mutableMapOf<String, Any>(
                    "name" to tag,
                    "type" to "tuic",
                    "server" to server,
                    "port" to port,
                    "uuid" to uuid,
                    "password" to pass,
                    "congestion-controller" to cc
                )
                val sni = ob["tls"]?.jsonObject?.get("server_name")?.jsonPrimitive?.contentOrNull
                if (!sni.isNullOrBlank()) map["sni"] = sni
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
            "ws", "httpupgrade" -> {
                map["network"] = "ws"
                val path = transport["path"]?.jsonPrimitive?.contentOrNull ?: "/"
                val host = transport["headers"]?.jsonObject?.get("Host")?.jsonPrimitive?.contentOrNull ?: ""
                val wsOpts = mutableMapOf<String, Any>("path" to path)
                if (host.isNotBlank()) wsOpts["headers"] = mapOf("Host" to host)
                map["ws-opts"] = wsOpts
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

    private fun convertUriListToMihomoYaml(uriList: String, warnings: MutableList<String>? = null): String {
        val proxies = mutableListOf<Map<String, Any>>()
        val names = mutableListOf<String>()

        uriList.lineSequence().map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { !it.startsWith("#") }
            .forEach { uriStr ->
                val proxy = parseUriToMihomoProxy(uriStr, warnings)
                if (proxy != null) {
                    proxies.add(proxy)
                    names.add(proxy["name"].toString())
                }
            }

        if (proxies.isEmpty()) {
            warnings?.add("В подписке не найдено ни одного поддерживаемого узла")
            return uriList
        }

        val root = mutableMapOf<String, Any>(
            "mixed-port" to 7890,
            "allow-lan" to false,
            "mode" to "rule",
            "proxies" to proxies,
            "proxy-groups" to listOf(
                mapOf(
                    "name" to "PROXY",
                    "type" to "select",
                    "proxies" to names
                )
            ),
            "rules" to listOf("MATCH,PROXY")
        )

        val dumperOptions = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            indent = 2
        }
        val writer = StringWriter()
        Yaml(dumperOptions).dump(root, writer)
        return writer.toString()
    }

    private fun parseUriToMihomoProxy(uri: String, warnings: MutableList<String>? = null): Map<String, Any>? {
        return try {
            // Hiddify embeds an "expiry date" pseudo-link (trojan://…--YYYY.MM.DD.time:<port>) that is
            // client-side display metadata, not a proxy node — reported as unrecognized below
            val isExpiryPseudoLink =
                Regex("^trojan://.*--\\d{4}\\.\\d{2}\\.\\d{2}\\.time:\\d+").containsMatchIn(uri)
            val hashIdx = uri.indexOf('#')
            val rawRemarks = if (hashIdx != -1) uri.substring(hashIdx + 1) else ""
            val remarks = runCatching { URLDecoder.decode(rawRemarks, "UTF-8") }.getOrDefault(rawRemarks).ifBlank { "Node" }
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
                    if (recognized) "Ошибка разбора узла: $uri" else "Не удалось распознать узел: $uri"
                )
            }
            proxy
        } catch (e: Exception) {
            warnings?.add("Ошибка разбора узла: $uri (${e.message ?: "неизвестная ошибка"})")
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

        val params = parseQueryParams(query)

        val map = mutableMapOf<String, Any>(
            "name" to name,
            "type" to "vless",
            "server" to server,
            "port" to port,
            "uuid" to uuid,
            "network" to (params["type"] ?: "tcp"),
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

        if (map["network"] == "ws") {
            val wsOpts = mutableMapOf<String, Any>("path" to (params["path"] ?: "/"))
            params["host"]?.let { wsOpts["headers"] = mapOf("Host" to it) }
            // WebSocket 0-RTT early data (v2ray/xray "ed"/"eh" params)
            params["ed"]?.toIntOrNull()?.let { wsOpts["max-early-data"] = it }
            params["eh"]?.takeIf { it.isNotBlank() }?.let { wsOpts["early-data-header-name"] = it }
            map["ws-opts"] = wsOpts
        } else if (map["network"] == "grpc") {
            params["serviceName"]?.let { map["grpc-opts"] = mapOf("grpc-service-name" to it) }
        }

        return map
    }

    private fun parseVmessUri(uri: String, defaultName: String): Map<String, Any>? {
        val noScheme = uri.removePrefix("vmess://")
        val decoded = runCatching { decodeBase64(noScheme) }.getOrNull() ?: return null
        val obj = runCatching { json.parseToJsonElement(decoded).jsonObject }.getOrNull() ?: return null

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
            "cipher" to "auto",
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
            // Strip query string (?type=tcp&plugin=...) — SIP003 plugin mapping is not handled yet
            val qIdx = rest.indexOf('?')
            val hostPort = if (qIdx != -1) rest.substring(0, qIdx) else rest
            val (server, rawPort) = parseHostPort(hostPort) ?: return null
            mutableMapOf(
                "name" to name,
                "type" to "ss",
                "server" to server,
                "port" to (rawPort.toIntOrNull() ?: 8388),
                "cipher" to method,
                "password" to password,
                "udp" to true
            )
        } else {
            val decoded = decodeBase64(noScheme)
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
            val decodedUser = runCatching { decodeBase64(userinfo) }.getOrNull()
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
        params["upmbps"]?.toIntOrNull()?.let { map["up"] = it }
        params["downmbps"]?.toIntOrNull()?.let { map["down"] = it }

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
        params["obfs"]?.takeIf { it.isNotBlank() && it != "none" }?.let { map["obfs-opts"] = mapOf("mode" to it) }
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
            val p = it.split("=")
            if (p.size == 2) p[0] to URLDecoder.decode(p[1], "UTF-8") else null
        }.toMap()
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

    private fun parseSeconds(str: String, default: Int): Int {
        val trimmed = str.trim().lowercase()
        return when {
            trimmed.endsWith("s") -> trimmed.removeSuffix("s").toIntOrNull() ?: default
            trimmed.endsWith("m") -> (trimmed.removeSuffix("m").toIntOrNull() ?: (default / 60)) * 60
            trimmed.endsWith("h") -> (trimmed.removeSuffix("h").toIntOrNull() ?: 1) * 3600
            else -> trimmed.toIntOrNull() ?: default
        }
    }
}
