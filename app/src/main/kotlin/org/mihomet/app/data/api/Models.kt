package org.mihomet.app.data.api

import kotlinx.serialization.Serializable

@Serializable
data class MihomoVersion(
    val version: String = "",
    val premium: Boolean = false
)

@Serializable
data class TrafficSample(
    val up: Long = 0L,
    val down: Long = 0L
)

@Serializable
data class LogEntry(
    val type: String = "info",
    val payload: String = ""
)

@Serializable
data class ProxiesResponse(
    val proxies: Map<String, ProxyGroupOrNode> = emptyMap()
)

@Serializable
data class ProxyGroupOrNode(
    val name: String = "",
    val type: String = "",
    val now: String? = null,
    val all: List<String>? = null,
    val history: List<DelayHistory> = emptyList(),
    val udp: Boolean = true
)

@Serializable
data class DelayHistory(
    val time: String = "",
    val delay: Int = 0
)

@Serializable
data class DelayResponse(
    val delay: Int = 0
)

@Serializable
data class ConnectionsResponse(
    val downloadTotal: Long = 0L,
    val uploadTotal: Long = 0L,
    val connections: List<ConnectionItem> = emptyList()
)

@Serializable
data class ConnectionItem(
    val id: String = "",
    val metadata: ConnectionMetadata = ConnectionMetadata(),
    val upload: Long = 0L,
    val download: Long = 0L,
    val start: String = "",
    val chains: List<String> = emptyList(),
    val rule: String = ""
)

@Serializable
data class ConnectionMetadata(
    val network: String = "",
    val type: String = "",
    val sourceIP: String = "",
    val destinationIP: String = "",
    val sourcePort: String = "",
    val destinationPort: String = "",
    val host: String = "",
    val process: String = "",
    val processPath: String = ""
)

@Serializable
data class MemoryInfo(
    val inuse: Long = 0L,
    val oslimit: Long = 0L
)

@Serializable
data class UpdateProxyRequest(
    val name: String
)

@Serializable
data class TunState(
    val enable: Boolean = false
)

@Serializable
data class ConfigsState(
    val tun: TunState? = null
) {
    val tunEnabled: Boolean get() = tun?.enable == true
}

@Serializable
data class ConfigsUpdateRequest(
    val mode: String? = null,
    val logLevel: String? = null
)
