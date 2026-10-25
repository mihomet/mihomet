package org.mihomet.app.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.yaml.snakeyaml.Yaml
import java.net.InetSocketAddress
import java.net.Socket

data class ProxyNodeInfo(
    val name: String,
    val type: String,
    val server: String,
    val port: Int
)

object NodePingTester {

    @Suppress("UNCHECKED_CAST")
    fun parseNodesFromYaml(yamlContent: String): List<ProxyNodeInfo> {
        return try {
            val yaml = Yaml()
            val parsed = yaml.load<Map<String, Any>>(yamlContent) ?: return emptyList()
            val rawProxies = parsed["proxies"] as? List<*> ?: return emptyList()
            
            rawProxies.filterIsInstance<Map<String, Any>>().mapNotNull { proxyMap ->
                val name = proxyMap["name"]?.toString() ?: return@mapNotNull null
                if (name == "dns-out" || name == "COMPATIBLE") return@mapNotNull null
                val type = proxyMap["type"]?.toString() ?: "unknown"
                val server = proxyMap["server"]?.toString() ?: return@mapNotNull null
                val port = (proxyMap["port"] as? Number)?.toInt() ?: return@mapNotNull null
                
                ProxyNodeInfo(
                    name = name,
                    type = type,
                    server = server,
                    port = port
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun testTcpPing(server: String, port: Int, timeoutMs: Int = 1500): Long = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(server, port), timeoutMs)
            }
            System.currentTimeMillis() - start
        } catch (e: Exception) {
            -1L
        }
    }

    suspend fun testAllNodes(nodes: List<ProxyNodeInfo>, timeoutMs: Int = 2000): Map<String, Long> = withContext(Dispatchers.IO) {
        nodes.map { node ->
            async {
                val latency = testTcpPing(node.server, node.port, timeoutMs)
                node.name to latency
            }
        }.awaitAll().toMap()
    }
}
