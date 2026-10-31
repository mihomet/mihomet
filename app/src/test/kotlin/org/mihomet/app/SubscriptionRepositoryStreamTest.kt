package org.mihomet.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mihomet.app.data.subs.SubscriptionRepository
import org.mihomet.app.data.subs.SubscriptionTooLargeException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

/**
 * Streaming body limit (external audit §9.3, P2): a subscription response is
 * read chunk-wise with a hard [SubscriptionRepository.MAX_SUBSCRIPTION_BYTES]
 * cap enforced on the bytes actually received — not on the Content-Length
 * header — so a header-less (chunked) response can no longer be buffered whole
 * into memory. Driven through a real HTTP server (OkHttp MockWebServer) using
 * the same engine stack as production (Ktor over OkHttp).
 */
class SubscriptionRepositoryStreamTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HttpClient

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        client = HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 15000
                requestTimeoutMillis = 60000
            }
        }
    }

    @After
    fun tearDown() {
        runCatching { client.close() }
        runCatching { server.shutdown() }
    }

    private fun subUrl(): String = server.url("/sub").toString()

    private suspend fun readWithProductionLimit(channel: io.ktor.utils.io.ByteReadChannel): String =
        SubscriptionRepository.readBodyWithLimit(
            channel,
            SubscriptionRepository.MAX_SUBSCRIPTION_BYTES,
            "Ответ подписки слишком большой (>20 МБ)"
        )

    // ------------------------------------------------------ real HTTP (MockWebServer)

    @Test
    fun `small chunked response without content-length is read fully`() = runBlocking {
        val body = "proxies:\n  - name: a\n    type: socks5\n    server: 127.0.0.1\n    port: 1080\n"
        server.enqueue(MockResponse().setResponseCode(200).setChunkedBody(body, 16))

        val response = client.get(subUrl())
        val text = readWithProductionLimit(response.bodyAsChannel())

        assertEquals(body, text)
    }

    @Test
    fun `small response with content-length is read fully`() = runBlocking {
        val body = "proxies:\n  - name: b\n    type: socks5\n    server: 127.0.0.1\n    port: 1081\n"
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val response = client.get(subUrl())
        val text = readWithProductionLimit(response.bodyAsChannel())

        assertEquals(body, text)
    }

    @Test
    fun `chunked body larger than the limit fails instead of buffering whole`() = runBlocking {
        // ~21 MiB > 20 MiB cap, transferred with chunked encoding (no Content-Length).
        val payload = Buffer().apply {
            val kilobyte = "x".repeat(1024)
            repeat(21 * 1024) { writeUtf8(kilobyte) }
        }
        assertEquals(21L * 1024 * 1024, payload.size)
        server.enqueue(MockResponse().setResponseCode(200).setChunkedBody(payload, 8192))

        val response = client.get(subUrl())
        val error = assertThrows(SubscriptionTooLargeException::class.java) {
            runBlocking { readWithProductionLimit(response.bodyAsChannel()) }
        }

        assertEquals("Ответ подписки слишком большой (>20 МБ)", error.message)
    }

    @Test
    fun `blocking chunked body is aborted at the limit without draining the whole response`() {
        val limit = SubscriptionRepository.MAX_SUBSCRIPTION_BYTES
        // Raw-socket server (MockWebServer cannot stream a never-ending body):
        // it keeps pushing far beyond the cap, one 64 KiB chunk at a time. If the
        // client buffered the whole response before enforcing the limit (the
        // audited F05 behavior), it would read every byte and the server would
        // deliver all of them. The excess (64 MiB) is deliberately far larger
        // than any loopback socket-buffer headroom, so with streaming
        // enforcement the client aborts at the cap, the connection resets, and
        // the server physically cannot complete the transfer.
        val totalBodyBytes = limit + 64L * 1024 * 1024
        val chunk = ByteArray(64 * 1024) { 'x'.code.toByte() }
        val crlf = "\r\n".toByteArray(Charsets.US_ASCII)
        val sentBytes = AtomicLong(0)
        val serverDone = CountDownLatch(1)

        val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port = serverSocket.localPort
        val serverThread = thread(start = true, isDaemon = true) {
            try {
                val socket = serverSocket.accept()
                try {
                    socket.tcpNoDelay = true
                    val out = socket.getOutputStream()
                    out.write(
                        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray(Charsets.US_ASCII)
                    )
                    out.flush()
                    var total = 0L
                    while (total < totalBodyBytes) {
                        // Minimal chunked framing: <hex size>\r\n<data>\r\n
                        out.write(Integer.toHexString(chunk.size).toByteArray(Charsets.US_ASCII))
                        out.write(crlf)
                        out.write(chunk)
                        out.write(crlf)
                        out.flush()
                        total += chunk.size
                        sentBytes.set(total)
                    }
                } finally {
                    runCatching { socket.close() }
                }
            } catch (_: IOException) {
                // Expected once the client aborts the streaming read: the socket
                // resets and a subsequent write throws.
            } finally {
                serverDone.countDown()
            }
        }

        try {
            val error = assertThrows(SubscriptionTooLargeException::class.java) {
                runBlocking {
                    client.prepareGet("http://127.0.0.1:$port/sub").execute { resp ->
                        readWithProductionLimit(resp.bodyAsChannel())
                    }
                }
            }
            assertEquals("Ответ подписки слишком большой (>20 МБ)", error.message)
            assertTrue("server thread did not finish in time", serverDone.await(15, TimeUnit.SECONDS))
            // The client really did receive past the cap (that is what triggered
            // the abort)...
            assertTrue("server sent ${sentBytes.get()}, expected > $limit", sentBytes.get() > limit)
            // ...but the transfer was cut off long before the full body: the
            // server could not write $totalBodyBytes bytes into a dead connection.
            assertTrue(
                "server sent ${sentBytes.get()}, expected < $totalBodyBytes",
                sentBytes.get() < totalBodyBytes
            )
        } finally {
            runCatching { serverSocket.close() }
            runCatching { serverThread.join(5000) }
        }
    }

    @Test
    fun `gzip content-encoding is decompressed by the transport before the cap`() = runBlocking {
        // OkHttp adds Accept-Encoding: gzip itself and decompresses transparently;
        // the streamed channel must already yield plaintext bytes.
        val body = "proxies:\n  - name: gz\n    type: socks5\n    server: 127.0.0.1\n    port: 1082\n"
        val gzipped = java.io.ByteArrayOutputStream().also { sink ->
            GZIPOutputStream(sink).use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Encoding", "gzip")
                .setBody(Buffer().write(gzipped))
        )

        val response = client.get(subUrl())
        val text = readWithProductionLimit(response.bodyAsChannel())

        assertEquals(body, text)
    }

    @Test
    fun `custom request headers still reach the server`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("proxies: []"))

        client.get(subUrl()) { header("X-Test", "streaming") }
        val recorded = server.takeRequest()

        assertEquals("streaming", recorded.getHeader("X-Test"))
    }

    // ------------------------------------------------------ exact cap boundary (in-memory channels)

    @Test
    fun `body exactly at the limit passes`() = runBlocking {
        val limit = SubscriptionRepository.MAX_SUBSCRIPTION_BYTES
        val channel = ByteReadChannel("a".repeat(limit.toInt()).toByteArray())

        val text = SubscriptionRepository.readBodyWithLimit(channel, limit, "too large")

        assertEquals(limit, text.length.toLong())
    }

    @Test
    fun `body one byte over the limit fails`() {
        val limit = SubscriptionRepository.MAX_SUBSCRIPTION_BYTES
        val channel = ByteReadChannel("a".repeat(limit.toInt() + 1).toByteArray())

        assertThrows(SubscriptionTooLargeException::class.java) {
            runBlocking { SubscriptionRepository.readBodyWithLimit(channel, limit, "too large") }
        }
    }

    @Test
    fun `empty channel yields empty string`() = runBlocking {
        val text = SubscriptionRepository.readBodyWithLimit(ByteReadChannel.Empty, 1024, "too large")

        assertTrue(text.isEmpty())
    }
}
