package org.mihomet.app.core.config

import org.mihomet.app.data.preferences.AppSettings
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.StringWriter

object ConfigGenerator {

    @Suppress("UNCHECKED_CAST")
    fun generateRuntimeConfig(
        profileYamlContent: String,
        settings: AppSettings,
        tunFd: Int? = null
    ): String {
        val yaml = Yaml()
        val configMap: MutableMap<String, Any> = try {
            (yaml.load<Map<String, Any>>(profileYamlContent) ?: emptyMap()).toMutableMap()
        } catch (e: Exception) {
            mutableMapOf()
        }

        // Standard ports & controllers
        configMap["mixed-port"] = settings.mixedPort
        configMap["tproxy-port"] = settings.tproxyPort
        configMap["redir-port"] = 7892
        configMap["allow-lan"] = true
        configMap["bind-address"] = "*"
        configMap["mode"] = settings.proxyMode.apiValue
        configMap["log-level"] = "info"
        configMap["ipv6"] = settings.ipv6Enabled
        configMap["external-controller"] = "127.0.0.1:${settings.controllerPort}"
        configMap["secret"] = settings.controllerSecret

        // Loop prevention mark (CRITICAL for root netfilter bypass)
        configMap["routing-mark"] = 666

        // DNS configuration
        val existingDns = (configMap["dns"] as? Map<String, Any>)?.toMutableMap() ?: mutableMapOf()
        existingDns["enable"] = true
        existingDns["listen"] = "0.0.0.0:${settings.dnsPort}"
        existingDns["ipv6"] = settings.ipv6Enabled
        if (!existingDns.containsKey("enhanced-mode")) {
            existingDns["enhanced-mode"] = "fake-ip"
            existingDns["fake-ip-range"] = "198.18.0.1/16"
        }
        if (!existingDns.containsKey("default-nameserver")) {
            existingDns["default-nameserver"] = listOf(
                "8.8.8.8",
                "1.1.1.1",
                "77.88.8.8"
            )
        }
        if (!existingDns.containsKey("nameserver")) {
            existingDns["nameserver"] = listOf(
                "8.8.8.8",
                "1.1.1.1",
                "77.88.8.8"
            )
        }
        configMap["dns"] = existingDns

        // Built-in DNS outbound proxy and rule for transparent DNS hijacking
        val rawProxies = configMap["proxies"] as? List<*>
        val proxiesList = rawProxies?.filterIsInstance<Map<String, Any>>()?.map { it.toMutableMap() }?.toMutableList() ?: mutableListOf()
        if (proxiesList.none { it["name"] == "dns-out" }) {
            proxiesList.add(0, mutableMapOf("name" to "dns-out", "type" to "dns"))
        }
        configMap["proxies"] = proxiesList

        val rawRules = configMap["rules"] as? List<*>
        val rulesList = rawRules?.filterIsInstance<String>()?.toMutableList() ?: mutableListOf()
        if (rulesList.none { it.contains("53,dns-out") }) {
            rulesList.add(0, "DST-PORT,53,dns-out")
        }
        configMap["rules"] = rulesList

        // TUN configuration for non-root VpnService
        if (tunFd != null && tunFd >= 0) {
            val tunMap = mutableMapOf<String, Any>(
                "enable" to true,
                "stack" to "system",
                "file-descriptor" to tunFd,
                "auto-route" to false,
                "auto-detect-interface" to false
            )
            configMap["tun"] = tunMap
        }

        val dumperOptions = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            indent = 2
        }
        val outputYaml = Yaml(dumperOptions)
        val writer = StringWriter()
        outputYaml.dump(configMap, writer)
        return writer.toString()
    }
}
