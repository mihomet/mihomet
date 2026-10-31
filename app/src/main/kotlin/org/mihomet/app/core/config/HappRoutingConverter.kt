package org.mihomet.app.core.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Happ routing deep-link / payload converter (`happ://routing/...`, the
 * base64 form, or the raw JSON with directSites/proxySites/blockSites fields):
 * produces a rules-only Mihomo YAML with DIRECT/REJECT/GLOBAL targets.
 */
internal object HappRoutingConverter {

    fun isHappRouting(str: String): Boolean {
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
            if (unescaped.startsWith("{")) unescaped else ConverterSupport.decodeBase64(unescaped)
        } else if (trimmed.startsWith("{")) {
            trimmed
        } else {
            runCatching { ConverterSupport.decodeBase64(trimmed) }.getOrDefault(trimmed)
        }

        val root = runCatching {
            ConverterSupport.json.parseToJsonElement(jsonPayload).jsonObject
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
}
