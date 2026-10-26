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

    fun streamTraffic(): Flow<TrafficSample> = flow {
        try {
            httpClient.webSocket(
                urlString = "$wsUrl/traffic",
                request = { applyAuth() }
            ) {
                while (isActive) {
                    val frame = incoming.receive()
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        val sample = json.decodeFromString<TrafficSample>(text)
                        emit(sample)
                    }
                }
            }
        } catch (e: ClosedReceiveChannelException) {
            // WS closed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Connection error or Mihomo not running yet
        }
    }.flowOn(Dispatchers.IO)

    fun streamLogs(): Flow<LogEntry> = flow {
        try {
            httpClient.webSocket(
                urlString = "$wsUrl/logs",
                request = { applyAuth() }
            ) {
                while (isActive) {
                    val frame = incoming.receive()
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        val entry = json.decodeFromString<LogEntry>(text)
                        emit(entry)
                    }
                }
            }
        } catch (e: ClosedReceiveChannelException) {
            // WS closed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Connection error
        }
    }.flowOn(Dispatchers.IO)
}
