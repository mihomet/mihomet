package org.mihomet.app.core.config

/**
 * Facade over the per-format converters ([HappRoutingConverter],
 * [ClashYamlConverter], [XrayJsonConverter], [SingBoxConverter],
 * [UriListConverter] / [UriListParser]). Keeps the single public entry points
 * the rest of the app (and the fixture tests) rely on:
 *
 * - [convertToMihomoYaml] / [convertToMihomoYamlWithOutcome] — any subscription
 *   content (Xray JSON, sing-box JSON, URI link list or Clash YAML) → Mihomo YAML
 * - [convertHappRoutingToMihomoYaml] — `happ://routing` deep-link payloads
 *
 * The format converters are internal; warnings flow out through
 * [ConversionOutcome] (see the warning contract documented there).
 */
object UniversalConfigConverter {

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
        val trimmed = content.trim().removePrefix("\uFEFF").trim()
        if (trimmed.isBlank()) return ConversionOutcome(content, emptyList(), format = null)

        val warnings = mutableListOf<ConversionWarning>()

        // Check for Base64 encoded payload
        val decoded = if (ConverterSupport.isLikelyBase64(trimmed)) {
            runCatching {
                ConverterSupport.decodeBase64(trimmed)
            }.getOrDefault(trimmed)
        } else {
            trimmed
        }

        val payload = decoded.trim().removePrefix("\uFEFF").trim()
        val format: String
        val yaml = when {
            HappRoutingConverter.isHappRouting(trimmed) -> {
                format = "happ-routing"
                HappRoutingConverter.convertHappRoutingToMihomoYaml(trimmed)
            }
            HappRoutingConverter.isHappRouting(payload) -> {
                format = "happ-routing"
                HappRoutingConverter.convertHappRoutingToMihomoYaml(payload)
            }
            WireGuardConfParser.isWireGuardConf(payload) -> {
                format = "wireguard"
                // Wrap in a full config shape ("proxies: [ ... ]"): the bare proxy
                // map would fail ProfileValidation (no proxies/rules keys) and the
                // whitelist sanitizer would strip every field at generation time.
                val proxy = WireGuardConfParser.parse(payload, "WireGuard")
                org.yaml.snakeyaml.Yaml().dump(mapOf("proxies" to listOf(proxy)))
            }
            ClashYamlConverter.isClashYaml(payload) -> {
                format = "clash-yaml"
                ClashYamlConverter.validateAndNormalizeYaml(payload, warnings)
            }
            XrayJsonConverter.isXrayJson(payload) -> {
                format = "xray"
                return XrayJsonConverter.convert(payload).copy(format = "xray")
            }
            SingBoxConverter.isSingBoxJson(payload) -> {
                format = "sing-box"
                SingBoxConverter.convertSingBoxJsonToMihomoYaml(payload, warnings)
            }
            UriListConverter.isUriList(payload) -> {
                format = "uri-list"
                UriListConverter.convertUriListToMihomoYaml(payload, warnings)
            }
            else -> {
                format = "unknown"
                warnings.add(ConversionWarning.UnknownFormat)
                payload
            }
        }
        return ConversionOutcome(yaml, warnings, format = format)
    }

    /**
     * Converts a Happ routing payload (deep link, base64 or raw JSON) into a
     * rules-only Mihomo YAML. Public for the deep-link import path.
     */
    fun convertHappRoutingToMihomoYaml(content: String): String =
        HappRoutingConverter.convertHappRoutingToMihomoYaml(content)
}
