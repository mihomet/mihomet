package org.mihomet.app.core.config

import org.mihomet.app.core.CoreConstants
import org.mihomet.app.core.dns.DnsCategory
import org.mihomet.app.core.dns.DnsPresets
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
            configMap["redir-port"] = CoreConstants.DEFAULT_REDIR_PORT
            configMap["routing-mark"] = CoreConstants.CORE_ROUTING_MARK
        } else {
            // Non-root VPN mode: SO_MARK and TProxy/Redir require CAP_NET_ADMIN (not available in non-root)
            configMap.remove("tproxy-port")
            configMap.remove("redir-port")
            configMap.remove("routing-mark")
        }
        configMap["allow-lan"] = false
        configMap["bind-address"] = "127.0.0.1"
        configMap["mode"] = settings.proxyMode.apiValue
        configMap["log-level"] = "info"
        configMap["ipv6"] = settings.ipv6Enabled
        configMap["external-controller"] = "${CoreConstants.DEFAULT_CONTROLLER_HOST}:${settings.controllerPort}"
        configMap["secret"] = settings.controllerSecret.ifBlank { CoreConstants.generateRandomHexSecret() }

        // DNS configuration
        val existingDns = (configMap["dns"] as? Map<String, Any>)?.toMutableMap() ?: mutableMapOf()
        existingDns["enable"] = true
        existingDns["listen"] = "${CoreConstants.DEFAULT_CONTROLLER_HOST}:${settings.dnsPort}"
        existingDns["ipv6"] = settings.ipv6Enabled
        if (!existingDns.containsKey("enhanced-mode")) {
            existingDns["enhanced-mode"] = "fake-ip"
            existingDns["fake-ip-range"] = "198.18.0.1/16"
        }
        // 1. default-nameserver: Bootstrap used by Mihomo to resolve the hostnames of
        //    encrypted (DoH/DoT) upstream endpoints. MUST be plain direct IPs reachable
        //    from Russia — foreign plain DNS (1.1.1.1 / 8.8.8.8) is blocked by TSPU.
        existingDns["default-nameserver"] = DnsPresets.BOOTSTRAP_RU_PLAIN

        // 2. proxy-server-nameserver: Resolves proxy server hostnames (e.g. lv-*.meow.ac)
        //    directly from Russia. Encryption is pointless here (proxy traffic is already
        //    encrypted) and foreign encrypted resolvers are blocked by TSPU — so only the
        //    plain Russian resolvers are used. Without this, Mihomo falls back to
        //    default-nameserver or nameserver.
        existingDns["proxy-server-nameserver"] = DnsPresets.byCategory(DnsCategory.PLAINTEXT_RU).map { it.uri }

        // 3. direct-nameserver: Used for direct connections (bootstrap + system resolver).
        existingDns["direct-nameserver"] = DnsPresets.BOOTSTRAP_RU_PLAIN + "system"

        // 4. nameserver: Used for routed app traffic — follows the user's choice.
        //    - Empty choice → plain Yandex DNS (reachable from Russia by default).
        //    - GLOBAL category (Cloudflare/Google/Quad9/AdGuard DoH) → user URI first,
        //      plus a plain Russian resolver SECOND: Mihomo queries nameservers
        //      concurrently, so when the global DoH endpoint is unreachable from
        //      Russia, the plain resolver answers instead of DNS breaking entirely,
        //      while the user's choice keeps top priority when it does respond.
        //    - RU plaintext/encrypted presets and fully custom servers → as-is.
        val customDns = settings.customDns.trim()
        existingDns["nameserver"] = when (DnsPresets.categoryOf(customDns)) {
            null -> if (customDns.isBlank()) DnsPresets.DEFAULT_NAMESERVER else listOf(customDns)
            DnsCategory.GLOBAL -> listOf(customDns) + DnsPresets.DEFAULT_NAMESERVER
            else -> listOf(customDns)
        }
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
