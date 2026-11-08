package org.mihomet.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mihomet.app.data.db.AppDatabase
import org.mihomet.app.data.preferences.SettingsRepository
import org.mihomet.app.data.subs.SubscriptionRepository

/**
 * On-device verification of the content-aware UA fallback: the panel serves a
 * different channel per User-Agent (plain base64 → grouped select YAML → rich
 * Xray JSON), and the repository must land on the RICH channel instead of
 * stopping at the first grouped one. Exercises the real Ktor client + DataStore
 * + Room stack on an Android runtime (unit tests cannot: org.json/yaml-heavy
 * paths and the DataStore/Keystore wiring differ on the JVM).
 */
@RunWith(AndroidJUnit4::class)
class SubscriptionUaFallbackInstrumentedTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: SubscriptionRepository

    private val plainBase64 = java.util.Base64.getEncoder().encodeToString(
        "vless://11111111-2222-3333-4444-555555555555@1.2.3.4:443?encryption=none#n1".toByteArray()
    )

    private val groupedYaml = """
        proxies:
          - {name: a, type: socks5, server: 1.2.3.4, port: 1080}
        proxy-groups:
          - {name: PROXY, type: select, proxies: [a]}
        rules:
          - MATCH,PROXY
    """.trimIndent()

    private val richXray = """
        {"remarks":"Node","outbounds":[
          {"tag":"n1","protocol":"vless","settings":{"vnext":[{"address":"1.2.3.4","port":443,
            "users":[{"id":"11111111-2222-3333-4444-555555555555","encryption":"none"}]}]}},
          {"tag":"n2","protocol":"vless","settings":{"vnext":[{"address":"5.6.7.8","port":443,
            "users":[{"id":"11111111-2222-3333-4444-555555555555","encryption":"none"}]}]}},
          {"tag":"direct","protocol":"freedom"}],
         "routing":{"rules":[{"type":"field","outboundTag":"direct","domain":["geosite:private"]},
           {"type":"field","outboundTag":"n1","network":"tcp,udp"}]}}
    """.trimIndent()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        server = MockWebServer()
        server.start()
        // Chain order: mihomet → mihomo → Happ (rich stops the walk)
        server.enqueue(MockResponse().setBody(plainBase64))
        server.enqueue(MockResponse().setBody(groupedYaml))
        server.enqueue(MockResponse().setBody(richXray))
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context, AppDatabase::class.java
        ).allowMainThreadQueries().build()
        val dao = db.profileDao()
        repo = SubscriptionRepository(
            context,
            dao,
            SettingsRepository(context, dao)
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun fallbackChainLandsOnRichChannel() = runBlocking {
        val url = server.url("/sub/test").toString()
        val download = repo.downloadAndConvert(url)
        // The final stored response must be the RICH Xray channel: two vless
        // proxies + the balancer/routing converted — not the grouped YAML.
        val yaml = download.outcome.yaml
        assertTrue(
            "expected the rich Xray channel (n1+n2), requests=${server.requestCount}, " +
                "warnings=${download.outcome.warnings}, got: ${yaml.take(200)}",
            yaml.contains("name: n1") && yaml.contains("name: n2")
        )
        assertTrue(
            "expected at least 2 vless proxies in the rich conversion",
            Regex("type: vless").findAll(yaml).count() >= 2
        )
    }

    @Test
    fun chainWalksAllThreeUserAgents() = runBlocking {
        val url = server.url("/sub/walk").toString()
        repo.downloadAndConvert(url)
        val req1 = server.takeRequest()
        val req2 = server.takeRequest()
        val req3 = server.takeRequest()
        // APP_USER_AGENT is "Mihomet/<version>..." — case-insensitive contains
        assertTrue(req1.getHeader("User-Agent")!!.contains("mihomet", ignoreCase = true))
        assertTrue(req2.getHeader("User-Agent")!!.contains("mihomo", ignoreCase = true))
        assertTrue(req3.getHeader("User-Agent")!!.contains("Happ", ignoreCase = true))
    }
}
