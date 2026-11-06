package org.mihomet.app.core.config

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.StringWriter

/**
 * Plain URI-list branch of the converter (the most common panel subscription
 * format): every line is dispatched through [UriListParser] and the collected
 * proxies are wrapped into a minimal single-group Mihomo YAML.
 */
internal object UriListConverter {

    fun isUriList(str: String): Boolean {
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
                firstLine.startsWith("wg://") ||
                firstLine.startsWith("awg://")
    }

    fun convertUriListToMihomoYaml(uriList: String, warnings: MutableList<ConversionWarning>? = null): String {
        val proxies = mutableListOf<Map<String, Any>>()
        val names = mutableListOf<String>()

        uriList.lineSequence().map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { !it.startsWith("#") }
            .forEach { uriStr ->
                val proxy = UriListParser.parseUriToMihomoProxy(uriStr, warnings)
                if (proxy != null) {
                    proxies.add(proxy)
                    names.add(proxy["name"].toString())
                }
            }

        if (proxies.isEmpty()) {
            warnings?.add(ConversionWarning.NoNodes)
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
}
