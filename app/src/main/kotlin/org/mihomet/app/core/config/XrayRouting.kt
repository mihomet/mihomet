package org.mihomet.app.core.config

import kotlinx.serialization.json.*

/** A field rule is AND across fields, OR within each field's values. Never widen a partial rule. */
internal object XrayRouting {
    private val known = setOf("type", "outboundTag", "balancerTag", "ruleTag", "domainMatcher", "domain", "ip", "port", "sourcePort", "source", "network", "user")
    private val unicodeCategories = setOf("L", "Lu", "Ll", "Lt", "Lm", "Lo", "M", "Mn", "Mc", "Me", "N", "Nd", "Nl", "No",
        "P", "Pc", "Pd", "Ps", "Pe", "Pi", "Pf", "Po", "S", "Sm", "Sc", "Sk", "So", "Z", "Zs", "Zl", "Zp", "C", "Cc", "Cf", "Cs", "Co", "Cn")

    fun convert(rule: JsonObject, target: String): String? {
        if ((rule["type"] as? JsonPrimitive)?.contentOrNull.let { it != null && it != "field" }) return null
        if (rule.keys.any { it !in known }) return null
        val clauses = mutableListOf<String>()
        for ((field, value) in rule) {
            if (field !in setOf("domain", "ip", "port", "sourcePort", "source", "network", "user")) continue
            val values = if (field in setOf("port", "sourcePort") && value is JsonPrimitive && value.intOrNull != null) {
                listOf(value.content)
            } else strings(value)?.ifEmpty { return null } ?: return null
            val terms = values.flatMap { if (field in setOf("port", "sourcePort", "network")) it.split(',') else listOf(it) }
                .map { term(field, it.trim()) ?: return null }
            clauses.add(if (terms.size == 1) terms.first() else "OR,(${terms.joinToString(",") { "($it)" }})")
        }
        if (clauses.isEmpty()) return "MATCH,$target"
        return if (clauses.size == 1) "${clauses.first()},$target"
        else "AND,(${clauses.joinToString(",") { "($it)" }}),$target"
    }

    private fun strings(value: JsonElement): List<String>? = when (value) {
        is JsonPrimitive -> value.takeIf { it.isString }?.let { listOf(it.content) }
        is JsonArray -> if (value.all { it is JsonPrimitive && it.isString }) value.map { it.jsonPrimitive.content } else null
        else -> null
    }

    private fun term(field: String, value: String): String? {
        if (value.isBlank() || value.any { it.isISOControl() } || ',' in value && !(field == "domain" && value.startsWith("regexp:"))) return null
        if (field == "domain" && !value.startsWith("regexp:") &&
            (value.substringAfter(':').isBlank() || value.any { it == '(' || it == ')' })) return null
        return when (field) {
            "domain" -> when {
                value.startsWith("geosite:") -> "GEOSITE,${value.substringAfter(':')}"
                value.startsWith("domain:") -> "DOMAIN-SUFFIX,${value.substringAfter(':')}"
                value.startsWith("full:") -> "DOMAIN,${value.substringAfter(':')}"
                value.startsWith("keyword:") -> "DOMAIN-KEYWORD,${value.substringAfter(':')}"
                value.startsWith("regexp:") -> {
                    nativeRegex(value.substringAfter(':'))?.let { "DOMAIN-REGEX,$it" }
                }
                ':' in value -> null
                else -> "DOMAIN-KEYWORD,$value"
            }
            "ip", "source" -> ipTerm(field == "source", value)
            "port", "sourcePort" -> {
                if (!value.matches(Regex("\\d{1,5}(?:-\\d{1,5})?"))) return null
                val ports = value.split('-').map { it.toInt() }
                if (ports.any { it !in 1..65535 } || ports.first() > ports.last()) return null
                "${if (field == "port") "DST-PORT" else "SRC-PORT"},$value"
            }
            "network" -> value.uppercase().takeIf { it == "TCP" || it == "UDP" }?.let { "NETWORK,$it" }
            // Xray user is an inbound email, not Mihomo's local user identity.
            else -> null
        }
    }

    private fun nativeRegex(expression: String): String? {
        val normalized = StringBuilder()
        var inClass = false
        var index = 0
        while (index < expression.length) {
            val char = expression[index++]
            if (char == '\\' && index < expression.length) {
                val escaped = expression[index++]
                if (escaped == 'Q') return null // The pinned core's regexp2 dialect has no quoted-literal blocks.
                if (escaped in setOf('p', 'P') && expression.getOrNull(index) == '{') {
                    val end = expression.indexOf('}', index)
                    if (end < 0 || expression.substring(index + 1, end) !in unicodeCategories) return null
                }
                if (escaped == '(' || escaped == ')') normalized.append(if (escaped == '(') "\\x28" else "\\x29")
                else normalized.append('\\').append(escaped)
            } else if (char == '(' && !inClass && expression.startsWith("?P<", index)) {
                normalized.append("(?<")
                index += 3
            } else {
                if (char == '[') inClass = true
                if (char == ']') inClass = false
                if (inClass && (char == '(' || char == ')')) normalized.append(if (char == '(') "\\x28" else "\\x29")
                else normalized.append(char)
            }
        }
        val pattern = normalized.toString()
        if (Regex("""\(\?[imsU-]*U[imsU-]*[:)]""").containsMatchIn(pattern) || pattern.contains("[:") || runCatching { Regex(pattern) }.isFailure) return null
        // Native logical rules count literal parentheses, and DOMAIN-REGEX otherwise forces ignore-case.
        return "(?-i:$pattern)"
    }

    private fun ipTerm(source: Boolean, value: String): String? {
        if (value.startsWith("geoip:")) {
            val region = value.substringAfter(':')
            val negated = region.startsWith('!')
            val name = region.removePrefix("!")
            if (!name.matches(Regex("[a-zA-Z0-9_-]+"))) return null
            val clause = "${if (source) "SRC-GEOIP" else "GEOIP"},$name"
            return if (negated) "NOT,(($clause))" else clause
        }
        val address = value.substringBefore('/')
        val ipv6 = ':' in address
        if (!address.matches(if (ipv6) Regex("[0-9a-fA-F:.]+") else Regex("(?:\\d{1,3}\\.){3}\\d{1,3}"))) return null
        if (ipv6 && runCatching { java.net.InetAddress.getByName(address) }.isFailure) return null
        if (!ipv6 && address.split('.').any { it.toInt() > 255 }) return null
        val prefix = value.substringAfter('/', if (ipv6) "128" else "32").toIntOrNull() ?: return null
        if (prefix !in 0..if (ipv6) 128 else 32) return null
        return "${if (source) "SRC-IP-CIDR" else if (ipv6) "IP-CIDR6" else "IP-CIDR"},$address/$prefix"
    }
}
