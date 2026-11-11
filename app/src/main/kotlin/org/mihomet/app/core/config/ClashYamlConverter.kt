package org.mihomet.app.core.config

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.StringWriter

/**
 * Clash YAML branch of the converter: detection and the normalization pass.
 *
 * Re-dumps a Clash YAML profile through SnakeYAML for normalization.
 * Never silent: a YAML parsing error is reported through [warnings]
 * ([ConversionWarning.YamlError]) and the original content is returned unchanged,
 * so downstream fail-closed guards (ConfigGenerator / profile validation)
 * stop the invalid profile instead of silently routing everything to DIRECT.
 */
internal object ClashYamlConverter {

    private val PROXIES_REGEX = Regex("""(?m)^[ \t]*proxies\s*:""")
    private val PROXY_GROUPS_REGEX = Regex("""(?m)^[ \t]*proxy-groups\s*:""")

    fun isClashYaml(str: String): Boolean {
        if (str.startsWith("{") || str.startsWith("[")) return false
        return PROXIES_REGEX.containsMatchIn(str) ||
            PROXY_GROUPS_REGEX.containsMatchIn(str) ||
            (str.contains("mixed-port:") && str.contains("rules:"))
    }

    fun validateAndNormalizeYaml(yamlContent: String, warnings: MutableList<ConversionWarning>? = null): String {
        return try {
            val yaml = ConverterSupport.newYaml()
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
            warnings?.add(ConversionWarning.YamlError(e.message))
            yamlContent
        }
    }
}
