package org.mihomet.app.core.config

import org.mihomet.app.core.AppErrorCode
import org.mihomet.app.core.AppException
import org.mihomet.app.core.CoreConstants
import org.mihomet.app.core.dns.DnsCategory
import org.mihomet.app.core.dns.DnsPresets
import org.mihomet.app.data.preferences.AppSettings
import org.mihomet.app.data.preferences.ProfileOverrides
import org.mihomet.app.data.preferences.TunOptions
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.StringWriter

object ConfigGenerator {

    /**
     * Whitelist of top-level profile keys that may pass from the user's (panel-supplied,
     * untrusted) profile YAML into the runtime config (audit P1 §2.1).
     *
     * Everything else — most notably `listeners`, `tunnels`, `external-ui`,
     * `external-controller`, `geox-url`, `geodata-*`, `interface-name`, `secret`,
     * `port`/`ports`, `allow-lan`, `bind-address`, `authentication`,
     * `skip-auth-prefixes`, `lan-allowed-ips` — is DROPPED before the override-patch
     * merge, and every dropped key produces a user-visible typed warning
     * ([ConversionWarning.ProfileKeyDropped], localized at display time by
     * [WarningLocalizer]).
     *
     * Rationale (audit §2.1): a malicious or careless subscription could otherwise
     * return `listeners: [{type: socks, port: 1080, listen: 0.0.0.0}]` or
     * `tunnels:` port-forwards — silently reopening an unauthenticated local proxy
     * or exposing the device's ports to the LAN. Geo-database sources (`geox-url`)
     * could point the core at attacker-controlled download URLs.
     */
    val ALLOWED_PROFILE_KEYS: Set<String> = setOf(
        "proxies", "proxy-groups", "proxy-providers",
        "rules", "rule-providers", "sub-rules",
        "dns", "sniffer", "tun", "hosts"
    )

    /**
     * Top-level keys the app ALWAYS enforces itself right after the profile merge
     * (security defaults, ports, controller, routing mode, log level, …). A profile
     * carrying them loses nothing — the app overwrites them with its own settings —
     * so dropping them is expected and SILENT. Only drops outside this set produce
     * a user-visible warning (listeners, tunnels, external-ui, …).
     */
    val APP_MANAGED_DROPPED_KEYS: Set<String> = setOf(
        "allow-lan", "bind-address",
        "mixed-port", "port", "socks-port", "tproxy-port", "redir-port", "routing-mark",
        "external-controller", "secret",
        "mode", "log-level", "ipv6", "unified-delay", "tcp-concurrent"
    )

    /**
     * Applies the profile-key whitelist in place: removes every top-level key not in
     * [ALLOWED_PROFILE_KEYS] from [configMap] and returns one typed
     * [ConversionWarning.ProfileKeyDropped] per dropped key (sorted for deterministic
     * UI ordering).
     */
    @Suppress("UNCHECKED_CAST")
    internal fun sanitizeProfileKeys(configMap: MutableMap<String, Any>): List<ConversionWarning> {
        val dropped = configMap.keys
            .map { it.toString() }
            .filter { it !in ALLOWED_PROFILE_KEYS }
            .distinct()
            .sorted()
        for (key in dropped) configMap.remove(key)
        return dropped
            .filter { it !in APP_MANAGED_DROPPED_KEYS }
            .map { ConversionWarning.ProfileKeyDropped(it) }
    }

    /**
     * Pure import-time helper: computes which top-level keys of a profile YAML would
     * be dropped by [sanitizeProfileKeys] WITHOUT mutating anything. Used by the
     * Profiles ViewModel to surface the warnings when a subscription is imported or
     * refreshed (the runtime generation drops the keys regardless — defense in depth).
     */
    fun profileKeyWarnings(yaml: String): List<ConversionWarning> {
        if (yaml.isBlank()) return emptyList()
        return try {
            val parsed = Yaml().load<Any>(yaml) ?: return emptyList()
            val map = (parsed as? Map<*, *>) ?: return emptyList()
            map.keys
                .map { it.toString() }
                .filter { it !in ALLOWED_PROFILE_KEYS }
                .filter { it !in APP_MANAGED_DROPPED_KEYS }
                .distinct()
                .sorted()
                .map { ConversionWarning.ProfileKeyDropped(it) }
        } catch (e: Exception) {
            // A profile that cannot even be parsed is reported by the converter /
            // fail-closed validation — no extra warnings here.
            emptyList()
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun generateRuntimeConfig(
        profileYamlContent: String,
        settings: AppSettings,
        tunFd: Int? = null,
        overridePatch: String = ""
    ): String = generateRuntimeConfigWithOutcome(
        profileYamlContent, settings, tunFd, overridePatch
    ).yaml

    /**
     * Same generation as [generateRuntimeConfig], returning the dropped-profile-key
     * warnings alongside the YAML ([ConversionOutcome] — the same warnings channel the
     * converter uses, localized at display time by [WarningLocalizer]).
     */
    @Suppress("UNCHECKED_CAST")
    fun generateRuntimeConfigWithOutcome(
        profileYamlContent: String,
        settings: AppSettings,
        tunFd: Int? = null,
        overridePatch: String = ""
    ): ConversionOutcome {
        // Fail-closed (P0): a broken or blank profile must NEVER degrade into an
        // empty config (no proxies/rules) — the core would start and route ALL
        // traffic to DIRECT while the UI shows "connected". Throwing here lets the
        // services refuse the core start (or fall back to the last known-good
        // runtime config) instead of silently losing the user's routing.
        if (profileYamlContent.isBlank()) {
            throw AppException(AppErrorCode.PROFILE_YAML_EMPTY)
        }
        val yaml = Yaml()
        val configMap: MutableMap<String, Any> = try {
            val parsed = yaml.load<Any>(profileYamlContent)
                ?: throw AppException(AppErrorCode.PROFILE_YAML_EMPTY)
            (parsed as? Map<String, Any>)?.toMutableMap()
                ?: throw AppException(
                    AppErrorCode.PROFILE_YAML_INVALID,
                    "document is ${parsed::class.simpleName}, expected a map"
                )
        } catch (e: AppException) {
            throw e
        } catch (e: Exception) {
            throw AppException(AppErrorCode.PROFILE_YAML_INVALID, e.message ?: "unknown parse error", cause = e)
        }

        // Profile-key whitelist (audit P1 §2.1): drop every top-level key the app does
        // not explicitly allow BEFORE the override patch is merged. Each dropped key
        // produces a user-visible warning (surfaced at import/refresh time through the
        // Profiles ViewModel, and returned by generateRuntimeConfigWithOutcome).
        val droppedKeyWarnings = sanitizeProfileKeys(configMap)

        // Per-profile override patch (P7): the user-authored YAML fragment is deep-merged
        // into the profile config BEFORE the mandatory app-managed keys are written below.
        // Because allow-lan / bind-address / ports / DNS / external-controller / secret are
        // written AFTER this merge, the patch can never weaken security (e.g. it CANNOT
        // enable allow-lan=true or move the controller to 0.0.0.0) — that is by design:
        // the patch may only add or override core-behaviour keys the app does not manage.
        val patch = overridePatch.trim()
        if (patch.isNotEmpty()) {
            try {
                when (val parsedPatch = yaml.load<Any>(patch)) {
                    is Map<*, *> -> {
                        // Skip null values (`key:` with no value ≈ unset in YAML)
                        val patchMap = LinkedHashMap<String, Any>(parsedPatch.size)
                        for ((k, v) in parsedPatch) if (v != null) patchMap[k.toString()] = v
                        deepMerge(configMap, patchMap)
                    }
                    // A valid-but-non-mapping patch (scalar/list) has nothing to merge — ignore it.
                    else -> {}
                }
            } catch (e: Exception) {
                // Malformed patch YAML is ignored (never crashes core start); the UI
                // validates the fragment at save time, so this is a defensive fallback.
            }
        }

        // Standard ports & controllers
        // Security: no unauthenticated local proxy listener. All phone traffic enters
        // via TUN (VPN) or tproxy/redir (root); a mixed-port on 127.0.0.1 would let any
        // local app bypass per-app routing and expose the exit node to VPN detectors.
        configMap.remove("mixed-port")
        configMap.remove("port")
        configMap.remove("socks-port")
        if (tunFd == null || tunFd < 0) {
            // Root mode can use TProxy and Redir, and SO_MARK for iptables loop prevention
            configMap["tproxy-port"] = settings.tproxyPort
            configMap["redir-port"] = CoreConstants.DEFAULT_REDIR_PORT
            configMap["routing-mark"] = CoreConstants.CORE_ROUTING_MARK
            configMap.remove("tun")
        } else {
            // Non-root VPN mode: SO_MARK and TProxy/Redir require CAP_NET_ADMIN (not available in non-root)
            configMap.remove("tproxy-port")
            configMap.remove("redir-port")
            configMap.remove("routing-mark")
            // Opt-in HTTP proxy through the VPN profile (CMFA/FlClash setHttpProxy
            // pattern): vpnHttpProxy re-exposes the mixed-port listener that is
            // otherwise removed above for security. Documented compromise:
            //  - the listener binds to 127.0.0.1 only (allow-lan=false + bind-address
            //    below), so it is never reachable from the LAN;
            //  - it stays UNAUTHENTICATED: any on-device app can dial it directly and
            //    thereby escape per-app split-tunneling (core rules still apply);
            //  - VpnService.setHttpProxy publishes it to apps honoring the system
            //    proxy inside the VPN profile — that is the feature's purpose.
            // The user consciously opted in (Settings → Advanced), so this is the
            // single sanctioned case where mixed-port returns to the runtime config.
            // Root mode NEVER re-enables it (there is no setHttpProxy channel there).
            if (settings.vpnHttpProxy) {
                configMap["mixed-port"] = settings.mixedPort
            }
        }
        configMap["allow-lan"] = false
        configMap["bind-address"] = "127.0.0.1"
        configMap["mode"] = settings.proxyMode.apiValue
        configMap["log-level"] = settings.overrideLogLevel.takeIf { ProfileOverrides.isValidLogLevel(it) }
            ?: ProfileOverrides.DEFAULT_LOG_LEVEL

        // Typed performance override layer: these keys are app-managed and are written
        // unconditionally so they always WIN over the same keys in the user's profile
        // (minimal CMFA-style ConfigurationOverride subset).
        configMap["unified-delay"] = settings.overrideUnifiedDelay
        configMap["tcp-concurrent"] = settings.overrideTcpConcurrent
        configMap["ipv6"] = settings.ipv6Enabled
        configMap["external-controller"] = "${CoreConstants.DEFAULT_CONTROLLER_HOST}:${settings.controllerPort}"
        configMap["secret"] = settings.controllerSecret.ifBlank { CoreConstants.generateRandomHexSecret() }

        // DNS configuration
        // Root (TProxy) mode note: intercepted DNS packets (dst port 53, any family)
        // enter the tunnel and are handled by the tunnel/rules — mihomo's tproxy
        // listener does NOT automatically forward them to dns.listen (no TUN
        // dns-hijack outside VPN mode). dns.listen below serves the DNS entry that
        // apps reach when explicitly pointed at it. Do not treat dnsPort as a
        // firewall parameter — the firewall only needs the tproxy port.
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
        //    The trailing "system" is critical in root TProxy mode: the core's own
        //    queries to 77.88.8.8:53 are transparently redirected by iptables back
        //    into the tproxy chain (loop) and die with "deadline exceeded" →
        //    "dns resolve failed: all DNS requests failed". The system resolver
        //    (router DNS; Private DNS is disabled by RootShellManager in root mode)
        //    bypasses that loop. In VPN mode "system" is the core's own 127.0.0.1
        //    listener — bounded recursion that worked before.
        existingDns["proxy-server-nameserver"] =
            DnsPresets.byCategory(DnsCategory.PLAINTEXT_RU).map { it.uri } + "system"

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

        // Sniffer for domain restoration on TLS/HTTP (enable is an app override)
        configMap["sniffer"] = mapOf(
            "enable" to settings.overrideSnifferEnabled,
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

        // Built-in auto-balancer (TODO.md spec): when the profile has no balancing
        // group of its own, synthesize an AUTO url-test group — mihomo probes the
        // pool and fails over natively. Panel-provided balancers always win.
        if (settings.autoBalancerEnabled && realProxyNames.size >= 2) {
            val panelHasBalancer = groupsList.any { g ->
                val type = g["type"]?.toString()?.lowercase()
                type == "url-test" || type == "load-balance" || type == "fallback"
            }
            if (!panelHasBalancer) {
                val excludes = settings.autoBalancerExcludes.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                val pool = realProxyNames.filter { name -> excludes.none { name.contains(it, ignoreCase = true) } }
                if (pool.size >= 2) {
                    val autoGroup = mutableMapOf<String, Any>(
                        "name" to CoreConstants.AUTO_GROUP_NAME,
                        "type" to "url-test",
                        "udp" to true,
                        "url" to settings.testUrl.ifBlank { CoreConstants.AUTO_PROBE_URL },
                        "interval" to CoreConstants.AUTO_PROBE_INTERVAL_SECONDS,
                        "tolerance" to CoreConstants.AUTO_PROBE_TOLERANCE_MS,
                        "proxies" to pool
                    )
                    val fallbackProxy = groupsList.firstOrNull {
                        it["name"] == "PROXY" && it["type"] == "select"
                    }
                    if (fallbackProxy != null) {
                        // App-generated fallback group: AUTO leads the list (default pick).
                        val list = (fallbackProxy["proxies"] as? List<*>)?.toMutableList()
                            ?: mutableListOf()
                        list.add(0, CoreConstants.AUTO_GROUP_NAME)
                        fallbackProxy["proxies"] = list
                    } else {
                        // Panel-provided select groups: offer AUTO without hijacking
                        // the default pick unless the user opted in.
                        groupsList.firstOrNull { it["type"]?.toString()?.lowercase() == "select" }
                            ?.let { g ->
                                val list = (g["proxies"] as? List<*>)?.toMutableList()
                                    ?: mutableListOf()
                                if (settings.autoBalancerPreferDefault) {
                                    list.add(0, CoreConstants.AUTO_GROUP_NAME)
                                } else {
                                    list.add(CoreConstants.AUTO_GROUP_NAME)
                                }
                                g["proxies"] = list
                            }
                    }
                    groupsList.add(autoGroup)
                }
            }
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
                // Sanitized: only gvisor/system/mixed survive, garbage falls back to gvisor
                "stack" to TunOptions.sanitizeStack(settings.tunStack),
                // Must match the VpnService Builder MTU exactly, otherwise the core's
                // packet reassembly (gVisor/system) disagrees with the kernel device
                "mtu" to TunOptions.sanitizeMtu(settings.tunMtu),
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
        return ConversionOutcome(writer.toString(), droppedKeyWarnings)
    }

    /**
     * Deep-merges [src] into [dst] in place and returns [dst]:
     * - Map + Map → recursive merge by keys (both sides keep their other entries);
     * - any other combination (scalars, lists, type mismatch) → src replaces dst.
     *
     * Used to apply the per-profile override patch: nested blocks (e.g. `hosts`,
     * `tun` sub-keys the app does not manage) merge key-by-key, while sequences
     * (rules, proxies, nameservers) are replaced wholesale.
     */
    @Suppress("UNCHECKED_CAST")
    private fun deepMerge(
        dst: MutableMap<String, Any>,
        src: Map<String, Any>
    ): MutableMap<String, Any> {
        for ((key, srcValue) in src) {
            val dstValue = dst[key]
            if (dstValue is Map<*, *> && srcValue is Map<*, *>) {
                // Re-box the existing map into a mutable String-keyed one before recursing,
                // so snakeyaml's immutable maps can be mutated and non-String keys don't crash.
                val nestedDst = LinkedHashMap<String, Any>(dstValue.size)
                for ((k, v) in dstValue) if (v != null) nestedDst[k.toString()] = v
                dst[key] = deepMerge(nestedDst, srcValue as Map<String, Any>)
            } else {
                dst[key] = srcValue
            }
        }
        return dst
    }
}
