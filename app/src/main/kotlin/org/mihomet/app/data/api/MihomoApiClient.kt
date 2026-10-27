package org.mihomet.app.data.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json

class MihomoApiClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 9090,
    private val secret: String = ""
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(json)
        }
        install(WebSockets)
        install(io.ktor.client.plugins.HttpTimeout) {
            requestTimeoutMillis = 15000
            connectTimeoutMillis = 5000
            socketTimeoutMillis = 15000
        }
    }

    private val baseUrl = "http://$host:$port"
    private val wsUrl = "ws://$host:$port"

    private fun io.ktor.client.request.HttpRequestBuilder.applyAuth() {
        if (secret.isNotBlank()) {
            header("Authorization", "Bearer $secret")
        }
    }

    suspend fun getVersion(): Result<MihomoVersion> = runCatching {
        httpClient.get("$baseUrl/version") {
            applyAuth()
        }.body()
    }

    suspend fun getProxies(): Result<ProxiesResponse> = runCatching {
        httpClient.get("$baseUrl/proxies") {
            applyAuth()
        }.body()
    }

    suspend fun selectProxyNode(groupName: String, nodeName: String): Result<Unit> = runCatching {
        val encodedGroup = groupName.encodeURLPathPart()
        httpClient.put("$baseUrl/proxies/$encodedGroup") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(UpdateProxyRequest(name = nodeName))
        }
    }

    suspend fun testGroupDelay(
        groupName: String,
        testUrl: String = "http://www.gstatic.com/generate_204",
        timeoutMs: Int = 5000
    ): Result<Map<String, Int>> = runCatching {
        val encodedGroup = groupName.encodeURLPathPart()
        val response = httpClient.get("$baseUrl/group/$encodedGroup/delay") {
            applyAuth()
            parameter("url", testUrl)
            parameter("timeout", timeoutMs)
        }
        if (response.status.value in 200..299) {
            response.body()
        } else {
            emptyMap()
        }
    }

    suspend fun testNodeDelay(
        nodeName: String,
        testUrl: String = "http://www.gstatic.com/generate_204",
        timeoutMs: Int = 5000
    ): Result<Int> = runCatching {
        val encodedNode = nodeName.encodeURLPathPart()
        val response = httpClient.get("$baseUrl/proxies/$encodedNode/delay") {
            applyAuth()
            parameter("url", testUrl)
            parameter("timeout", timeoutMs)
        }
        if (response.status.value in 200..299) {
            val delayResp: DelayResponse = response.body()
            delayResp.delay
        } else {
            -1
        }
    }

    suspend fun getConnections(): Result<ConnectionsResponse> = runCatching {
        httpClient.get("$baseUrl/connections") {
            applyAuth()
        }.body()
    }

    suspend fun closeConnection(id: String): Result<Unit> = runCatching {
        val encodedId = id.encodeURLPathPart()
        httpClient.delete("$baseUrl/connections/$encodedId") {
            applyAuth()
        }
    }

    suspend fun closeAllConnections(): Result<Unit> = runCatching {
        httpClient.delete("$baseUrl/connections") {
            applyAuth()
        }
    }

    suspend fun updateMode(mode: String): Result<Unit> = runCatching {
        httpClient.patch("$baseUrl/configs") {
            applyAuth()
            contentType(ContentType.Application.Json)
            setBody(ConfigsUpdateRequest(mode = mode))
        }
    }

    private companion object {
        const val WS_RETRY_INITIAL_DELAY_MS = 1_000L
        const val WS_RETRY_MAX_DELAY_MS = 10_000L
    }

    /**
     * Streams decoded [Frame.Text] messages from a Mihomo WebSocket endpoint,
     * reconnecting with exponential backoff (1s → 10s) until the collector is
     * cancelled. Without this, a dropped connection silently ends the flow.
     */
    private fun <T> streamWebSocket(path: String, decode: (String) -> T): Flow<T> = flow {
        var retryDelayMs = WS_RETRY_INITIAL_DELAY_MS
        while (currentCoroutineContext().isActive) {
            try {
                httpClient.webSocket(
                    urlString = "$wsUrl$path",
                    request = { applyAuth() }
                ) {
                    while (isActive) {
                        val frame = incoming.receive()
                        if (frame is Frame.Text) {
                            emit(decode(frame.readText()))
                        }
                    }
                }
                // Session ended normally — start over with the short delay
                retryDelayMs = WS_RETRY_INITIAL_DELAY_MS
            } catch (e: ClosedReceiveChannelException) {
                // WS closed — reconnect
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Connection error or Mihomo not running yet — reconnect
            }
            currentCoroutineContext().ensureActive()
            delay(retryDelayMs)
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(WS_RETRY_MAX_DELAY_MS)
        }
    }.flowOn(Dispatchers.IO)

    fun streamTraffic(): Flow<TrafficSample> = streamWebSocket("/traffic") { text ->
        json.decodeFromString<TrafficSample>(text)
    }

    fun streamLogs(): Flow<LogEntry> = streamWebSocket("/logs") { text ->
        json.decodeFromString<LogEntry>(text)
    }
}
