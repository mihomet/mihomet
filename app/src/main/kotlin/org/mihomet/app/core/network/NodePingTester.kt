package org.mihomet.app.core.network

import kotlinx.coroutines.Dispatchers
import org.mihomet.app.core.CoreConstants
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.net.HttpURLConnection
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
    val port: Int,
    val network: String = ""
)

data class ConnectionTestResult(
    val isSuccess: Boolean,
    val latencyMs: Long = 0,
    val ip: String = "",
    val location: String = "",
    val errorMessage: String? = null
)

object NodePingTester {

    /**
     * Sentinel latency returned by [testIcmpPing] when ICMP as such is not
     * usable on this device (no `ping` binary or the OS refuses the exec) —
     * distinct from the ordinary `-1` "node did not answer" failure.
     */
    const val ICMP_UNAVAILABLE: Long = -2L

    /** Outcome of one `/system/bin/ping` probe (audit P2.8: honest ICMP). */
    sealed interface IcmpResult {
        /** Host answered; [latencyMs] is the parsed avg RTT. */
        data class Success(val latencyMs: Long, val minMs: Long, val maxMs: Long) : IcmpResult

        /** The binary ran but the host did not answer (down, filtered, unknown name). */
        data object Unreachable : IcmpResult

        /** ICMP is unavailable on this device — missing binary or permission failure. */
        data object Unavailable : IcmpResult
    }

    /** Candidate locations of the platform `ping` binary, probed in order. */
    private val PING_BINARY_CANDIDATES = listOf("/system/bin/ping", "/system/xbin/ping")

    /** Feature detection: ICMP mode is only offered/run when a `ping` binary exists. */
    fun icmpBinaryPath(): String? =
        PING_BINARY_CANDIDATES.firstOrNull { runCatching { File(it).canExecute() }.getOrDefault(false) }

    /**
     * Pure parser of `ping` output, JVM-unit-testable. Handles the toybox
     * (`rtt min/avg/max/mdev = a/b/c/d ms`), iputils (`rtt min/avg/max/mdev`)
     * and busybox (`round-trip min/avg/max = ...`) summary formats.
     */
    fun parseIcmpPingOutput(exitCode: Int, output: String): IcmpResult {
        val lower = output.lowercase()
        // Permission failures mean the device forbids ICMP — a capability gap,
        // not the node being down.
        if ("operation not permitted" in lower || "permission denied" in lower) return IcmpResult.Unavailable
        if ("unknown host" in lower || "name or service not known" in lower) return IcmpResult.Unreachable

        val received = Regex("(\\d+)\\s+packets?\\s+received", RegexOption.IGNORE_CASE).find(output)
            ?.groupValues?.get(1)?.toIntOrNull()
            // busybox prints "1 received" without the word "packets"
            ?: Regex("(\\d+)\\s+received", RegexOption.IGNORE_CASE).find(output)
                ?.groupValues?.get(1)?.toIntOrNull()

        val rttMatch = Regex(
            "(?:rtt|round-trip)\\s*(?:min/avg/max(?:/(?:mdev|stddev))?)?\\s*=\\s*" +
                "([0-9.]+)/([0-9.]+)/([0-9.]+)",
            RegexOption.IGNORE_CASE
        ).find(output)

        if (exitCode == 0 && rttMatch != null) {
            val (min, avg, max) = rttMatch.destructured
            val minMs = (min.toDoubleOrNull() ?: return IcmpResult.Unreachable).toLong()
            val avgMs = (avg.toDoubleOrNull() ?: return IcmpResult.Unreachable).toLong()
            val maxMs = (max.toDoubleOrNull() ?: return IcmpResult.Unreachable).toLong()
            return IcmpResult.Success(avgMs.coerceAtLeast(1), minMs, maxMs)
        }
        if (received == 0) return IcmpResult.Unreachable
        if (exitCode == 0 && received != null && received > 0) {
            // 1 received but no rtt summary line: fall back to a minimal honest success.
            return IcmpResult.Success(latencyMs = 1, minMs = 1, maxMs = 1)
        }
        return IcmpResult.Unreachable
    }

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

                val rawNetwork = proxyMap["network"]?.toString()?.lowercase()
                val isTls = proxyMap["tls"] == true || proxyMap["tls"] == "true"
                val isReality = proxyMap.containsKey("reality-opts") || proxyMap["security"] == "reality"
                val network = when {
                    isReality -> "reality"
                    rawNetwork != null && rawNetwork.isNotBlank() && rawNetwork != "tcp" -> rawNetwork
                    proxyMap.containsKey("ws-opts") -> "ws"
                    proxyMap.containsKey("grpc-opts") -> "grpc"
                    proxyMap.containsKey("h2-opts") -> "h2"
                    proxyMap.containsKey("http-opts") -> "http"
                    type.equals("hysteria2", ignoreCase = true) || type.equals("tuic", ignoreCase = true) -> "quic"
                    type.equals("wireguard", ignoreCase = true) -> "udp"
                    isTls -> "tls"
                    else -> rawNetwork ?: "tcp"
                }

                ProxyNodeInfo(
                    name = name,
                    type = type,
                    server = server,
                    port = port,
                    network = network
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

    /**
     * ICMP probe via the platform `ping` binary (audit P2.8). The previous
     * `InetAddress.isReachable` implementation silently degraded to a TCP:7
     * connect on Android (no raw-socket privileges), so "ICMP" results were
     * really TCP results. Returns [ICMP_UNAVAILABLE] when the device cannot do
     * ICMP at all, -1 when the node did not answer, else the avg RTT in ms.
     */
    suspend fun testIcmpPing(server: String, timeoutMs: Int = 1500): Long =
        when (val result = testIcmpDetailed(server, timeoutMs)) {
            is IcmpResult.Success -> result.latencyMs
            IcmpResult.Unavailable -> ICMP_UNAVAILABLE
            IcmpResult.Unreachable -> -1L
        }

    suspend fun testIcmpDetailed(server: String, timeoutMs: Int = 1500): IcmpResult =
        withContext(Dispatchers.IO) {
            val binary = icmpBinaryPath() ?: return@withContext IcmpResult.Unavailable
            // toybox/iputils -W takes whole seconds; clamp so short timeouts still get one second.
            val waitSeconds = ((timeoutMs + 999) / 1000).coerceAtLeast(1)
            val sanitized = server.trim().take(253)
            if (sanitized.isEmpty() || sanitized.any { it.isWhitespace() || it == ';' || it == '&' || it == '|' }) {
                return@withContext IcmpResult.Unreachable
            }
            val process = try {
                ProcessBuilder(binary, "-c", "1", "-W", waitSeconds.toString(), sanitized)
                    .redirectErrorStream(true)
                    .start()
            } catch (_: Exception) {
                // exec refused (SELinux/OEM) — capability gap, not node state.
                return@withContext IcmpResult.Unavailable
            }
            try {
                val finished = process.waitFor((timeoutMs + 1500).toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                val output = runCatching { process.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("")
                if (!finished) {
                    return@withContext IcmpResult.Unreachable
                }
                when (val parsed = parseIcmpPingOutput(process.exitValue(), output)) {
                    is IcmpResult.Success -> parsed.copy(
                        latencyMs = parsed.latencyMs.coerceAtLeast(1)
                    )
                    else -> parsed
                }
            } catch (_: InterruptedException) {
                IcmpResult.Unreachable
            } finally {
                // No-op for an already-exited process; reaps a hung one.
                process.destroyForcibly()
            }
        }

    suspend fun testHttpPing(server: String, port: Int, method: String = "GET", timeoutMs: Int = 2000): Long = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        try {
            if (port != 443 && port != 8443) {
                val host = if (server.contains(":") && !server.startsWith("[")) "[$server]" else server
                val url = URL("http://$host:$port")
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
        timeoutMs: Int = 2000,
        onProgress: ((nodeName: String, latency: Long) -> Unit)? = null
    ): Map<String, Long> = withContext(Dispatchers.IO) {
        // Cap concurrency: large subscriptions would otherwise open hundreds of sockets at once
        val semaphore = Semaphore(8)
        nodes.map { node ->
            async {
                semaphore.withPermit {
                    val latency = testNode(node, protocol, timeoutMs)
                    onProgress?.invoke(node.name, latency)
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
                val fbUrl = URL(CoreConstants.AUTO_PROBE_URL)
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
