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
        configMap.remove("port")
        configMap.remove("socks-port")
        if (tunFd == null || tunFd < 0) {
            // Root mode can use TProxy and Redir, and SO_MARK for iptables loop prevention
            configMap["tproxy-port"] = settings.tproxyPort
            configMap["redir-port"] = 7892
            configMap["routing-mark"] = 666
        } else {
            // Non-root VPN mode: SO_MARK and TProxy/Redir require CAP_NET_ADMIN (not available in non-root)
            configMap.remove("tproxy-port")
            configMap.remove("redir-port")
            configMap.remove("routing-mark")
        }
        configMap["allow-lan"] = true
        configMap["bind-address"] = "*"
        configMap["mode"] = settings.proxyMode.apiValue
        configMap["log-level"] = "info"
        configMap["ipv6"] = settings.ipv6Enabled
        configMap["external-controller"] = "127.0.0.1:${settings.controllerPort}"
        configMap["secret"] = settings.controllerSecret

        // DNS configuration
        val existingDns = (configMap["dns"] as? Map<String, Any>)?.toMutableMap() ?: mutableMapOf()
        existingDns["enable"] = true
        existingDns["listen"] = "0.0.0.0:${settings.dnsPort}"
        existingDns["ipv6"] = settings.ipv6Enabled
        if (!existingDns.containsKey("enhanced-mode")) {
            existingDns["enhanced-mode"] = "fake-ip"
            existingDns["fake-ip-range"] = "198.18.0.1/16"
        }
        // 1. Direct bootstrap DNS: Used exclusively to resolve DoH hostnames and proxy server hostnames.
        // MUST NEVER contain 1.1.1.1 or 8.8.8.8 over plain UDP 53 because Russian TSPU blocks them!
        // Yandex DNS (77.88.8.8 / 77.88.8.1) and system (local Wi-Fi / mobile carrier) are 100% accessible.
        val unblockedBootstrap = listOf("77.88.8.8", "77.88.8.1", "system")

        // 2. default-nameserver: Used by Mihomo to bootstrap DNS servers. Must only be reachable direct IPs / system.
        existingDns["default-nameserver"] = unblockedBootstrap

        // 3. proxy-server-nameserver: The CRITICAL setting Mihomo uses to resolve proxy server hostnames (e.g. lv-*.meow.ac).
        // Without this, Mihomo falls back to default-nameserver or nameserver.
        existingDns["proxy-server-nameserver"] = unblockedBootstrap

        // 4. direct-nameserver: Used for direct connections.
        existingDns["direct-nameserver"] = unblockedBootstrap

        // 5. nameserver: Used for routed app traffic.
        // Use Encrypted DNS over HTTPS (DoH) which works everywhere and bypasses ISP censorship,
        // plus Yandex fallback. Remove plain UDP 1.1.1.1 / 8.8.8.8 / 114.114.114.114.
        val userNameserver = (existingDns["nameserver"] as? List<*>)
            ?.mapNotNull { it?.toString() }
            ?.filter { it != "1.1.1.1" && it != "1.0.0.1" && it != "8.8.8.8" && it != "8.8.4.4" && it != "114.114.114.114" }
            ?: emptyList()

        val primaryDoH = listOf(
            "https://77.88.8.8/dns-query",
            "https://1.1.1.1/dns-query",
            "https://dns.google/dns-query",
            "77.88.8.8"
        )
        existingDns["nameserver"] = (primaryDoH + userNameserver).distinct()
        configMap["dns"] = existingDns

        // Sniffer for domain restoration on TLS/HTTP
        configMap["sniffer"] = mapOf(
            "enable" to true,
            "sniff" to mapOf(
                "TLS" to mapOf("ports" to listOf(443, 8443)),
                "HTTP" to mapOf("ports" to listOf(80, "8080-8880"))
            )
        )

        // Extract real proxies (exclude reserved built-in proxy names) and ensure UDP is active
        val rawProxies = configMap["proxies"] as? List<*>
        val proxiesList = rawProxies?.filterIsInstance<Map<String, Any>>()
            ?.filter {
                val name = it["name"]?.toString() ?: ""
                name != "DIRECT" && name != "REJECT" && name != "GLOBAL" && name != "COMPATIBLE" && name != "dns-out"
            }
            ?.map {
                val p = it.toMutableMap()
                p["udp"] = true // Force UDP enabled for Telegram MTProto, gaming, and QUIC
                p
            }?.toMutableList() ?: mutableListOf()
        configMap["proxies"] = proxiesList

        // Preserve subscription proxy-groups if present, otherwise create defaults
        val realProxyNames = proxiesList
            .mapNotNull { it["name"]?.toString() }
            .filter { it != "dns-out" && it != "DIRECT" && it != "REJECT" && it != "COMPATIBLE" }

        val rawGroups = configMap["proxy-groups"] as? List<*>
        val groupsList = rawGroups?.filterIsInstance<Map<String, Any>>()?.map {
            val g = it.toMutableMap()
            g["udp"] = true // Force UDP enabled on all proxy groups
            g
        }?.toMutableList() ?: mutableListOf()

        if (groupsList.isEmpty() && realProxyNames.isNotEmpty()) {
            val mainGroup = mutableMapOf<String, Any>(
                "name" to "PROXY",
                "type" to "select",
                "udp" to true,
                "proxies" to realProxyNames
            )
            groupsList.add(mainGroup)
        }
        configMap["proxy-groups"] = groupsList

        val rawRules = configMap["rules"] as? List<*>
        val rulesList = rawRules?.filterIsInstance<String>()?.toMutableList() ?: mutableListOf()
        if (groupsList.isNotEmpty() && rulesList.none { it.startsWith("MATCH,") }) {
            val defaultTarget = groupsList.firstOrNull { it["type"] == "select" }?.get("name")?.toString()
                ?: groupsList.firstOrNull()?.get("name")?.toString()
                ?: "DIRECT"
            rulesList.add("MATCH,$defaultTarget")
        }
        configMap["rules"] = rulesList

        // TUN configuration for non-root VpnService
        if (tunFd != null && tunFd >= 0) {
            val tunMap = mutableMapOf<String, Any>(
                "enable" to true,
                "stack" to "gvisor",
                "file-descriptor" to tunFd,
                "auto-route" to false,
                "auto-detect-interface" to false, // Must be false in non-root: Netlink route monitor blocked by Android SELinux
                "dns-hijack" to listOf("any:53", "tcp://any:53")
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
