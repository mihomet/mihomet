package org.mihomet.app.core.config

/**
 * WireGuard / AmneziaWG .conf file parser (INI-style format used by the official
 * WG and AmneziaWG apps, distributed by panels as local files).
 *
 * Produces a mihomo wireguard outbound map, including AmneziaWG obfuscation
 * parameters when present (Jc, Jmin, Jmax, S1, S2, H1–H4).
 */
object WireGuardConfParser {

    fun isWireGuardConf(content: String): Boolean {
        val trimmed = content.trim()
        return trimmed.startsWith("[Interface]") || trimmed.startsWith("[interface]") ||
                (trimmed.contains("[Peer]") || trimmed.contains("[peer]")) &&
                trimmed.contains("PrivateKey", ignoreCase = true)
    }

    fun parse(content: String, name: String): Map<String, Any> {
        val interface_ = mutableMapOf<String, String>()
        val peers = mutableListOf<Map<String, String>>()
        var current: MutableMap<String, String>? = null

        for (rawLine in content.lines()) {
            val line = rawLine.trim()
            when {
                line.equals("[Interface]", ignoreCase = true) -> {
                    current = interface_
                }
                line.equals("[Peer]", ignoreCase = true) -> {
                    current = mutableMapOf<String, String>().also { peers.add(it) }
                }
                line.startsWith("[") -> { current = null }
                else -> {
                    val target = current ?: continue
                    val eq = line.indexOf('=')
                    if (eq > 0) {
                        val key = line.substring(0, eq).trim()
                        val value = line.substring(eq + 1).trim()
                        if (key.isNotBlank() && value.isNotBlank()) target[key] = value
                    }
                }
            }
        }

        val map = mutableMapOf<String, Any>(
            "name" to name,
            "type" to "wireguard",
            "server" to "",
            "port" to 51820
        )

        // Interface fields
        interface_["PrivateKey"]?.let { map["private-key"] = it }
        interface_["Address"]?.let { map["ip"] = it.split(",")[0].trim() }
        interface_["DNS"]?.let { map["dns"] = it.split(",").map { d -> d.trim() } }
        interface_["MTU"]?.toIntOrNull()?.let { map["mtu"] = it }

        // AmneziaWG obfuscation parameters (optional)
        interface_["Jc"]?.toIntOrNull()?.let { map["junk-packet-count"] = it }
        interface_["Jmin"]?.toIntOrNull()?.let { map["junk-packet-min-size"] = it }
        interface_["Jmax"]?.toIntOrNull()?.let { map["junk-packet-max-size"] = it }
        interface_["S1"]?.toIntOrNull()?.let { map["init-packet-junk-size"] = it }
        interface_["S2"]?.toIntOrNull()?.let { map["response-packet-junk-size"] = it }
        interface_["H1"]?.toLongOrNull()?.let { map["init-packet-magic-header"] = it }
        interface_["H2"]?.toLongOrNull()?.let { map["response-packet-magic-header"] = it }
        interface_["H3"]?.toLongOrNull()?.let { map["transport-packet-magic-header"] = it }
        interface_["H4"]?.toLongOrNull()?.let { map["under-load-packet-magic-header"] = it }

        // First peer: endpoint becomes server:port, pubkey becomes public-key
        val peer = peers.firstOrNull()
        if (peer != null) {
            peer["PublicKey"]?.let { map["public-key"] = it }
            peer["PresharedKey"]?.let { map["preshared-key"] = it }
            peer["Endpoint"]?.let { endpoint ->
                val (host, port) = parseEndpoint(endpoint)
                map["server"] = host
                map["port"] = port
            }
            peer["AllowedIPs"]?.let { map["allowed-ips"] = it.split(",").map { ip -> ip.trim() } }
        }

        return map
    }

    private fun parseEndpoint(endpoint: String): Pair<String, Int> {
        val lastColon = endpoint.lastIndexOf(':')
        if (lastColon == -1) return endpoint to 51820
        val host = endpoint.substring(0, lastColon).removeSurrounding("[", "]")
        val port = endpoint.substring(lastColon + 1).toIntOrNull() ?: 51820
        return host to port
    }
}
