package org.mihomet.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import java.util.zip.GZIPOutputStream

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
