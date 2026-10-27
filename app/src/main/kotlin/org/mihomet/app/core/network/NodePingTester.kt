package org.mihomet.app.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.yaml.snakeyaml.Yaml
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

enum class PingProtocol(val displayName: String, val shortName: String) {
    TCP("TCP Connect", "TCP"),
    HTTP_GET("HTTP GET (204)", "HTTP"),
    HTTP_HEAD("HTTP HEAD", "HEAD"),
    ICMP("ICMP Ping", "ICMP")
}

data class ProxyNodeInfo(
    val name: String,
    val type: String,
    val server: String,
    val port: Int
)

data class ConnectionTestResult(
    val isSuccess: Boolean,
    val latencyMs: Long = 0,
    val ip: String = "",
    val location: String = "",
    val errorMessage: String? = null
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

    suspend fun testIcmpPing(server: String, timeoutMs: Int = 1500): Long = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val reachable = InetAddress.getByName(server).isReachable(timeoutMs)
            if (reachable) System.currentTimeMillis() - start else -1L
        } catch (e: Exception) {
            -1L
        }
    }

    suspend fun testHttpPing(server: String, port: Int, method: String = "GET", timeoutMs: Int = 2000): Long = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        try {
            if (port != 443 && port != 8443) {
                val url = URL("http://$server:$port")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = false
                }
                val code = conn.responseCode
                // Drain the body (or error stream) so the socket can be released cleanly
                try {
                    (if (code in 200..399) conn.inputStream else conn.errorStream)?.use { it.read() }
                } catch (_: Exception) {
                }
                if (code in 200..399 || code == 404 || code == 405) {
                    return@withContext System.currentTimeMillis() - start
                }
            }
            // For TLS ports, perform real TLS handshake to measure secure connection latency
            testTlsPing(server, port, timeoutMs)
        } catch (e: Exception) {
            -1L
        } finally {
            conn?.disconnect()
        }
    }

    suspend fun testTlsPing(server: String, port: Int, timeoutMs: Int = 2000): Long = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(server, port), timeoutMs)
                val sslFactory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
                (sslFactory.createSocket(socket, server, port, true) as javax.net.ssl.SSLSocket).use { sslSocket ->
                    sslSocket.soTimeout = timeoutMs
                    sslSocket.startHandshake()
                    System.currentTimeMillis() - start
                }
            }
        } catch (e: Exception) {
            -1L
        }
    }

    suspend fun testNode(node: ProxyNodeInfo, protocol: PingProtocol, timeoutMs: Int = 2000): Long {
        return when (protocol) {
            PingProtocol.TCP -> testTcpPing(node.server, node.port, timeoutMs)
            PingProtocol.HTTP_GET -> testHttpPing(node.server, node.port, "GET", timeoutMs)
            PingProtocol.HTTP_HEAD -> testHttpPing(node.server, node.port, "HEAD", timeoutMs)
            PingProtocol.ICMP -> testIcmpPing(node.server, timeoutMs)
        }
    }

    suspend fun testAllNodes(
        nodes: List<ProxyNodeInfo>,
        protocol: PingProtocol = PingProtocol.TCP,
        timeoutMs: Int = 2000
    ): Map<String, Long> = withContext(Dispatchers.IO) {
        // Cap concurrency: large subscriptions would otherwise open hundreds of sockets at once
        val semaphore = Semaphore(8)
        nodes.map { node ->
            async {
                semaphore.withPermit {
                    val latency = testNode(node, protocol, timeoutMs)
                    node.name to latency
                }
            }
        }.awaitAll().toMap()
    }

    /**
     * End-to-end check of current active connection (latency + public IP + location).
     */
    suspend fun testCurrentConnection(timeoutMs: Int = 3000): ConnectionTestResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            // Test reachability to Cloudflare trace / Google 204
            val testUrl = URL("https://1.1.1.1/cdn-cgi/trace")
            val conn = (testUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", "Mihomet-Diagnostics")
            }
            val elapsed = System.currentTimeMillis() - start
            val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            var ip = ""
            var loc = ""
            responseBody.lines().forEach { line ->
                if (line.startsWith("ip=")) ip = line.removePrefix("ip=")
                if (line.startsWith("loc=")) loc = line.removePrefix("loc=")
            }

            ConnectionTestResult(
                isSuccess = true,
                latencyMs = elapsed,
                ip = ip.ifBlank { "OK" },
                location = loc.ifBlank { "Подключено" }
            )
        } catch (e: Exception) {
            // Fallback to simple 204
            try {
                val fbStart = System.currentTimeMillis()
                val fbUrl = URL("http://www.gstatic.com/generate_204")
                val fbConn = (fbUrl.openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                }
                val code = fbConn.responseCode
                fbConn.disconnect()
                if (code == 204 || code == 200) {
                    ConnectionTestResult(
                        isSuccess = true,
                        latencyMs = System.currentTimeMillis() - fbStart,
                        ip = "Активно",
                        location = "Доступно"
                    )
                } else {
                    ConnectionTestResult(isSuccess = false, errorMessage = "HTTP $code")
                }
            } catch (e2: Exception) {
                ConnectionTestResult(isSuccess = false, errorMessage = e2.message ?: "Таймаут")
            }
        }
    }
}
