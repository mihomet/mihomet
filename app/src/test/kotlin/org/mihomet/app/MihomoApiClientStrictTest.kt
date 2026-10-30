package org.mihomet.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mihomet.app.data.api.MihomoApiClient
import org.mihomet.app.data.api.TrafficSample
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Strict tests for [MihomoApiClient] against a real HTTP/WebSocket server
 * (OkHttp MockWebServer): wire format, Authorization header, path encoding,
 * non-2xx mapping, WebSocket streaming with reconnect, and unreachable-server
 * behavior — all without any client refactoring.
 */
class MihomoApiClientStrictTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        // Cancelled WS flows can leave a lingering socket for a moment; a shutdown
        // timeout must not fail otherwise-passing assertions.
        runCatching { server.shutdown() }
    }

    private fun client(secret: String = SECRET) =
        MihomoApiClient(host = "127.0.0.1", port = server.port, secret = secret)

    private fun jsonOk(body: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private fun recordedOrNull() = server.takeRequest(5, TimeUnit.SECONDS)

    // ------------------------------------------------------------------ auth

    @Test
    fun `authorization bearer header is sent when secret is set`() = runTest {
        server.enqueue(jsonOk(VERSION_JSON))

        val result = client().getVersion()

        assertTrue(result.isSuccess)
        val recorded = recordedOrNull()
        assertEquals("Bearer $SECRET", recorded?.getHeader("Authorization"))
    }

    @Test
    fun `authorization header is absent when secret is blank`() = runTest {
        server.enqueue(jsonOk(VERSION_JSON))

        val result = client(secret = "").getVersion()

        assertTrue(result.isSuccess)
        val recorded = recordedOrNull()
        assertNull("Authorization must not be sent for a blank secret", recorded?.getHeader("Authorization"))
    }

    // -------------------------------------------------------------- version

    @Test
    fun `getVersion decodes real core payload and ignores meta`() = runTest {
        server.enqueue(jsonOk(VERSION_JSON))

        val version = client().getVersion().getOrThrow()

        assertEquals("1.19.33", version.version)
        // MihomoVersion has no `meta` field; ignoreUnknownKeys must swallow it.
        assertEquals(false, version.premium)
        val recorded = recordedOrNull()
        assertEquals("GET", recorded?.method)
        assertEquals("/version", recorded?.path)
    }

    // -------------------------------------------------------------- proxies

    @Test
    fun `getProxies decodes realistic core payload with unknown fields`() = runTest {
        server.enqueue(jsonOk(PROXIES_JSON))

        val proxies = client().getProxies().getOrThrow().proxies

        assertEquals(5, proxies.size)
        val proxy = proxies.getValue("PROXY")
        assertEquals("Selector", proxy.type)
        assertEquals("🇩🇪 Германия 01", proxy.now)
        assertEquals(listOf("DIRECT", "🇩🇪 Германия 01", "Auto"), proxy.all)

        val auto = proxies.getValue("Auto")
        assertEquals(2, auto.history.size)
        assertEquals(123, auto.history[0].delay)
        assertEquals("2026-10-28T10:01:00.123456789Z", auto.history[1].time)
        assertEquals(0, auto.history[1].delay) // mihomo reports 0 on timeout

        val node = proxies.getValue("🇩🇪 Германия 01")
        assertEquals("Vless", node.type)
        assertEquals(1, node.history.size)
        assertEquals(187, node.history[0].delay)
        assertTrue(node.udp)

        assertEquals("Direct", proxies.getValue("DIRECT").type)
        assertEquals(false, proxies.getValue("DIRECT").udp)

        val recorded = recordedOrNull()
        assertEquals("GET", recorded?.method)
        assertEquals("/proxies", recorded?.path)
        assertEquals("Bearer $SECRET", recorded?.getHeader("Authorization"))
    }

    // --------------------------------------------------------- selectProxyNode

    @Test
    fun `selectProxyNode sends PUT with percent-encoded path and name body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val group = "Моя группа/A"
        val node = "Узел №2"

        val result = client().selectProxyNode(group, node)

        assertTrue(result.isSuccess)
        val recorded = recordedOrNull()
        assertEquals("PUT", recorded?.method)
        // ktor encodeURLPathPart (2.3.12): space -> %20, '/' -> %2F, UTF-8 -> percent bytes.
        assertEquals(
            "/proxies/%D0%9C%D0%BE%D1%8F%20%D0%B3%D1%80%D1%83%D0%BF%D0%BF%D0%B0%2FA",
            recorded?.path
        )
        val body = Json.parseToJsonElement(recorded?.body?.readUtf8().orEmpty()).jsonObject
        assertEquals(node, body["name"]?.jsonPrimitive?.content)
        assertEquals(
            "application/json",
            recorded?.getHeader("Content-Type")?.substringBefore(';')
        )
    }

    // --------------------------------------------------------- testNodeDelay

    @Test
    fun `testNodeDelay returns delay on 200 and hits url and timeout params`() = runTest {
        server.enqueue(jsonOk("""{"delay":123}"""))

        val result = client().testNodeDelay("N1", testUrl = "http://www.gstatic.com/generate_204", timeoutMs = 5000)

        assertEquals(123, result.getOrThrow())
        val recorded = recordedOrNull()
        assertEquals("GET", recorded?.method)
        assertEquals("/proxies/N1/delay", recorded?.requestUrl?.encodedPath)
        assertEquals("http://www.gstatic.com/generate_204", recorded?.requestUrl?.queryParameter("url"))
        assertEquals("5000", recorded?.requestUrl?.queryParameter("timeout"))
    }

    @Test
    fun `testNodeDelay maps non-2xx to minus one`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(502)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"message":"an error occurred"}""")
        )

        val result = client().testNodeDelay("Broken Node")

        assertTrue("non-2xx must be Result.success(-1), got $result", result.isSuccess)
        assertEquals(-1, result.getOrThrow())
    }

    @Test
    fun `testNodeDelay returns failure without crash when server is unreachable`() = runTest {
        val isolated = MihomoApiClient("127.0.0.1", closedPort(), SECRET)

        val result = isolated.testNodeDelay("N1")

        assertTrue("network error must surface as Result.failure, got $result", result.isFailure)
    }

    // --------------------------------------------------------- testGroupDelay

    @Test
    fun `testGroupDelay decodes delay map`() = runTest {
        server.enqueue(jsonOk("""{"N1":123,"🇩🇪 Германия 01":187}"""))

        val result = client().testGroupDelay("PROXY")

        assertTrue(result.isSuccess)
        assertEquals(mapOf("N1" to 123, "🇩🇪 Германия 01" to 187), result.getOrThrow())
    }

    @Test
    fun `testGroupDelay maps non-2xx to empty map`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = client().testGroupDelay("PROXY")

        assertTrue(result.isSuccess)
        assertEquals(emptyMap<String, Int>(), result.getOrThrow())
    }

    // ----------------------------------------------------------- connections

    @Test
    fun `getConnections decodes realistic payload with metadata and unknown fields`() = runTest {
        server.enqueue(jsonOk(CONNECTIONS_JSON))

        val connections = client().getConnections().getOrThrow()

        assertEquals(1_048_576L, connections.downloadTotal)
        assertEquals(65_536L, connections.uploadTotal)
        assertEquals(1, connections.connections.size)
        val item = connections.connections[0]
        assertEquals("9b7a3f2e-1c4d-4e8f-a2b3-5d6c7e8f9a0b", item.id)
        assertEquals("tcp", item.metadata.network)
        assertEquals("www.gstatic.com", item.metadata.host)
        assertEquals("42424", item.metadata.sourcePort)
        assertEquals("142.250.74.238", item.metadata.destinationIP)
        assertEquals(1_024L, item.upload)
        assertEquals(98_304L, item.download)
        assertEquals(listOf("🇩🇪 Германия 01", "Auto", "PROXY"), item.chains)
        assertEquals("DomainSuffix", item.rule)
        val recorded = recordedOrNull()
        assertEquals("GET", recorded?.method)
        assertEquals("/connections", recorded?.path)
    }

    @Test
    fun `closeConnection sends DELETE to the encoded connection path`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val id = "9b7a3f2e-1c4d-4e8f-a2b3-5d6c7e8f9a0b"

        val result = client().closeConnection(id)

        assertTrue(result.isSuccess)
        val recorded = recordedOrNull()
        assertEquals("DELETE", recorded?.method)
        assertEquals("/connections/$id", recorded?.path)
        assertEquals("Bearer $SECRET", recorded?.getHeader("Authorization"))
    }

    @Test
    fun `closeAllConnections sends DELETE to connections`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        val result = client().closeAllConnections()

        assertTrue(result.isSuccess)
        val recorded = recordedOrNull()
        assertEquals("DELETE", recorded?.method)
        assertEquals("/connections", recorded?.path)
    }

    // ----------------------------------------------------------------- memory

    @Test
    fun `getMemory hits GET memory with auth and decodes core payload`() = runTest {
        server.enqueue(jsonOk("""{"inuse":52428800,"oslimit":0}"""))

        val memory = client().getMemory().getOrThrow()

        assertEquals(52_428_800L, memory.inuse)
        assertEquals(0L, memory.oslimit)
        val recorded = recordedOrNull()
        assertEquals("GET", recorded?.method)
        assertEquals("/memory", recorded?.path)
        assertEquals("Bearer $SECRET", recorded?.getHeader("Authorization"))
    }

    @Test
    fun `getMemory returns failure without crash when server is unreachable`() = runTest {
        val isolated = MihomoApiClient("127.0.0.1", closedPort(), SECRET)

        val result = isolated.getMemory()

        assertTrue(result.isFailure)
    }

    // ------------------------------------------------------------- updateMode

    @Test
    fun `updateMode sends PATCH configs with mode body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        val result = client().updateMode("rule")

        assertTrue(result.isSuccess)
        val recorded = recordedOrNull()
        assertEquals("PATCH", recorded?.method)
        assertEquals("/configs", recorded?.path)
        val body = Json.parseToJsonElement(recorded?.body?.readUtf8().orEmpty()).jsonObject
        assertEquals("rule", body["mode"]?.jsonPrimitive?.content)
        assertEquals("application/json", recorded?.getHeader("Content-Type")?.substringBefore(';'))
    }

    // ------------------------------------------------------------- WebSocket

    @Test
    fun `streamTraffic decodes two frames then stays cancellable`() = runTest {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"up":1,"down":2}""")
                    webSocket.send("""{"up":10,"down":20}""")
                    webSocket.close(1000, "done")
                }
            })
        )

        val samples = withContext(Dispatchers.IO) {
            withTimeout(WS_TIMEOUT_MS) {
                client().streamTraffic().take(2).toList()
            }
        }

        assertEquals(
            listOf(TrafficSample(up = 1, down = 2), TrafficSample(up = 10, down = 20)),
            samples
        )
        // After the flow is cancelled by take(2), the flow must not hang the test.
        val upgrade = recordedOrNull()
        assertEquals("/traffic", upgrade?.path)
        assertEquals("Bearer $SECRET", upgrade?.getHeader("Authorization"))
    }

    @Test
    fun `streamTraffic retries after the server closes the connection`() = runTest {
        // First attempt: handshake succeeds, then the server drops the session.
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.close(1001, "going away")
                }
            })
        )
        // Second attempt (after the 1s reconnect backoff) delivers real data.
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"up":42,"down":24}""")
                    webSocket.close(1000, "done")
                }
            })
        )

        val samples = withContext(Dispatchers.IO) {
            withTimeout(WS_TIMEOUT_MS * 2) {
                client().streamTraffic().take(1).toList()
            }
        }

        assertEquals(listOf(TrafficSample(up = 42, down = 24)), samples)
    }

    // ------------------------------------------------------------- unreachable

    @Test
    fun `getVersion returns failure without crash when server is unreachable`() = runTest {
        val isolated = MihomoApiClient("127.0.0.1", closedPort(), SECRET)

        val result = isolated.getVersion()

        assertTrue(result.isFailure)
    }

    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        const val SECRET = "s3cr3t-t0k3n"
        const val WS_TIMEOUT_MS = 20_000L

        val VERSION_JSON = """{"version":"1.19.33","meta":true}"""

        val PROXIES_JSON = """
        {
          "proxies": {
            "PROXY": {
              "alive": true, "type": "Selector", "name": "PROXY",
              "udp": true, "xudp": true, "tfo": false, "hidden": false,
              "now": "🇩🇪 Германия 01",
              "all": ["DIRECT", "🇩🇪 Германия 01", "Auto"]
            },
            "Auto": {
              "alive": true, "type": "URLTest", "name": "Auto",
              "udp": true, "fixed": 0, "timeout": 5000, "tolerance": 50,
              "now": "🇩🇪 Германия 01",
              "all": ["🇩🇪 Германия 01"],
              "history": [
                {"time": "2026-10-28T10:00:00.123456789Z", "delay": 123},
                {"time": "2026-10-28T10:01:00.123456789Z", "delay": 0}
              ]
            },
            "🇩🇪 Германия 01": {
              "alive": true, "type": "Vless", "name": "🇩🇪 Германия 01",
              "udp": true, "xudp": true, "tls": true, "tfo": false,
              "network": "tcp",
              "history": [{"time": "2026-10-28T10:00:00.123456789Z", "delay": 187}]
            },
            "DIRECT": {"alive": true, "type": "Direct", "name": "DIRECT", "udp": false},
            "REJECT": {"alive": true, "type": "Reject", "name": "REJECT", "udp": false}
          }
        }
        """.trimIndent()

        val CONNECTIONS_JSON = """
        {
          "downloadTotal": 1048576,
          "uploadTotal": 65536,
          "memory": 123456789,
          "connections": [
            {
              "id": "9b7a3f2e-1c4d-4e8f-a2b3-5d6c7e8f9a0b",
              "metadata": {
                "network": "tcp",
                "type": "HTTPS",
                "sourceIP": "10.10.0.2",
                "destinationIP": "142.250.74.238",
                "sourcePort": "42424",
                "destinationPort": "443",
                "host": "www.gstatic.com",
                "process": "",
                "processPath": "",
                "specialProxy": "",
                "remoteDestination": ""
              },
              "upload": 1024,
              "download": 98304,
              "start": "2026-10-28T10:00:00.123456789Z",
              "chains": ["🇩🇪 Германия 01", "Auto", "PROXY"],
              "rule": "DomainSuffix",
              "rulePayload": "gstatic.com"
            }
          ]
        }
        """.trimIndent()
    }
}
