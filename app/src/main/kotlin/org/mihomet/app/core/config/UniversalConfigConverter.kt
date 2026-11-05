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
        val trimmed = content.trim()
        if (trimmed.isBlank()) return ConversionOutcome(content, emptyList())

        val warnings = mutableListOf<ConversionWarning>()

        // Check for Base64 encoded payload
        val decoded = if (ConverterSupport.isLikelyBase64(trimmed)) {
            runCatching {
                ConverterSupport.decodeBase64(trimmed)
            }.getOrDefault(trimmed)
        } else {
            trimmed
        }

        val yaml = when {
            HappRoutingConverter.isHappRouting(trimmed) -> HappRoutingConverter.convertHappRoutingToMihomoYaml(trimmed)
            HappRoutingConverter.isHappRouting(decoded) -> HappRoutingConverter.convertHappRoutingToMihomoYaml(decoded)
            WireGuardConfParser.isWireGuardConf(decoded) -> {
                // Wrap in a full config shape ("proxies: [ ... ]"): the bare proxy
                // map would fail ProfileValidation (no proxies/rules keys) and the
                // whitelist sanitizer would strip every field at generation time.
                val proxy = WireGuardConfParser.parse(decoded, "WireGuard")
                org.yaml.snakeyaml.Yaml().dump(mapOf("proxies" to listOf(proxy)))
            }
            ClashYamlConverter.isClashYaml(decoded) -> ClashYamlConverter.validateAndNormalizeYaml(decoded, warnings)
            SingBoxConverter.isSingBoxJson(decoded) -> SingBoxConverter.convertSingBoxJsonToMihomoYaml(decoded, warnings)
            XrayJsonConverter.isXrayJson(decoded) -> XrayJsonConverter.convertXrayJsonToMihomoYaml(decoded, warnings)
            UriListConverter.isUriList(decoded) -> UriListConverter.convertUriListToMihomoYaml(decoded, warnings)
            else -> {
                warnings.add(ConversionWarning.UnknownFormat)
                decoded
            }
        }
        return ConversionOutcome(yaml, warnings)
    }

    /**
     * Converts a Happ routing payload (deep link, base64 or raw JSON) into a
     * rules-only Mihomo YAML. Public for the deep-link import path.
     */
    fun convertHappRoutingToMihomoYaml(content: String): String =
        HappRoutingConverter.convertHappRoutingToMihomoYaml(content)
}
