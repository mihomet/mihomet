package org.mihomet.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mihomet.app.core.AppErrorCode
import org.mihomet.app.core.AppException
import org.mihomet.app.data.db.AppDatabase
import org.mihomet.app.data.preferences.SettingsRepository
import org.mihomet.app.data.subs.SubscriptionRepository
import org.mihomet.app.data.subs.SubscriptionDownload
import org.mihomet.app.core.config.UniversalConfigConverter
import org.mihomet.app.data.db.ProfileType
import org.mihomet.app.data.crypto.SecretsCipher
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    private lateinit var db: AppDatabase

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
        db = androidx.room.Room.inMemoryDatabaseBuilder(
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
        db.close()
    }

    private fun enqueueThreeChannels() {
        // Chain order: mihomet → mihomo → Happ (rich stops the walk).
        server.enqueue(MockResponse().setBody(plainBase64))
        server.enqueue(MockResponse().setBody(groupedYaml))
        server.enqueue(MockResponse().setBody(richXray))
    }

    @Test
    fun fallbackChainLandsOnRichChannel() = runBlocking {
        enqueueThreeChannels()
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
        assertTrue(
            "diagnostics must retain the selected source payload",
            download.sourcePayload?.contains("\"n2\"") == true
        )
    }

    @Test
    fun oversizedFallbackDoesNotAttachPreviousUaSourceOrWarnings() = runBlocking {
        val failedSource = "synthetic unsupported subscription token"
        server.enqueue(MockResponse().setBody(failedSource))
        server.enqueue(
            MockResponse()
                .setBody("")
                .setHeader("Content-Length", (SubscriptionRepository.MAX_SUBSCRIPTION_BYTES + 1).toString())
        )

        val failure = runCatching {
            repo.downloadAndConvert(server.url("/sub/fallback").toString())
        }.exceptionOrNull()

        assertTrue("expected a typed size failure, got $failure", failure is AppException)
        failure as AppException
        assertEquals(AppErrorCode.SUBSCRIPTION_TOO_LARGE, failure.code)
        assertNull(failure.sourcePayload)
        assertTrue(failure.conversionWarnings.isEmpty())
        assertTrue("previous UA exceptions must not be retained", failure.suppressed.isEmpty())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun failedUaAttemptsDoNotRetainEarlierResponseBodiesInExceptionChains() = runBlocking {
        val responses = listOf(
            "unsupported synthetic response one",
            "unsupported synthetic response two",
            "unsupported synthetic response three"
        )
        responses.forEach { server.enqueue(MockResponse().setBody(it)) }

        val failure = runCatching {
            repo.downloadAndConvert(server.url("/sub/failures").toString())
        }.exceptionOrNull()

        assertTrue("expected a typed conversion failure, got $failure", failure is AppException)
        failure as AppException
        assertEquals(AppErrorCode.IMPORT_NO_COMPATIBLE_CONFIG, failure.code)
        assertEquals(responses.last(), failure.sourcePayload)
        assertEquals(3, server.requestCount)
        assertTrue("the latest attempt should be the only retained cause", failure.cause?.suppressed?.isEmpty() == true)
    }

    @Test
    fun encryptedResponseIsRetainedWhenHappKeyIsMissing() = runBlocking {
        val encryptedSource = "synthetic encrypted response"
        val missingKeyId = "key-mihomet-export-missing-98765"
        repeat(3) {
            server.enqueue(
                MockResponse()
                    .setBody(encryptedSource)
                    .addHeader("encrypt-tag", "synthetic-tag")
            )
        }

        val failure = runCatching {
            repo.downloadAndConvert(server.url("/sub/export?key=$missingKeyId").toString())
        }.exceptionOrNull()

        assertTrue("expected a typed key failure, got $failure", failure is AppException)
        failure as AppException
        assertEquals(AppErrorCode.ENC_SUB_UNKNOWN_KEY, failure.code)
        assertEquals(encryptedSource, failure.sourcePayload)
    }

    @Test
    fun chainWalksAllThreeUserAgents() = runBlocking {
        enqueueThreeChannels()
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

    @Test
    fun laterLosslessRichChannelReplacesLossyYamlAndItsMetadata() = runBlocking {
        val partial = richXray.replace("\"routing\":{", "\"dns\":{\"servers\":[\"localhost\"]},\"routing\":{")
        server.enqueue(MockResponse().setBody(partial).addHeader("profile-title", "Lossy"))
        server.enqueue(MockResponse().setBody(richXray).addHeader("profile-title", "Complete")
            .addHeader("profile-update-interval", "1").addHeader("Subscription-Userinfo", "upload=12; download=34; total=500"))
        val download = repo.downloadAndConvert(server.url("/sub/improving").toString())
        assertEquals(2, server.requestCount)
        assertTrue(download.outcome.report!!.issues.isEmpty())
        assertEquals("Complete", download.meta.profileTitle)
        val saved = repo.addProfile("Complete", org.mihomet.app.data.db.ProfileType.LOCAL, download)
        val row = db.profileDao().getProfileById(saved.profile.id)!!
        try {
            assertTrue(row.isSelected)
            assertEquals(1, row.updateIntervalHours)
            assertEquals(34L, row.downloadUsed)
            assertEquals(download.outcome.report, org.mihomet.app.core.config.ConversionReport.fromJson(row.conversionReport))
            assertEquals(download.outcome.yaml, java.io.File(row.filePath).readText())
        } finally {
            java.io.File(row.filePath).delete()
        }
    }

    @Test
    fun refreshPreservesChangesMadeWhileTheResponseIsPending() = runBlocking {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                arrived.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return MockResponse().setBody(richXray).addHeader("profile-update-interval", "1")
            }
        }
        val download = SubscriptionDownload(UniversalConfigConverter.convertToMihomoYamlWithOutcome(richXray))
        val first = repo.addProfile("Before", ProfileType.REMOTE, download, server.url("/sub/pending").toString()).profile
        val other = repo.addProfile("Other", ProfileType.LOCAL, download).profile
        val dao = db.profileDao()
        val refresh = async(Dispatchers.IO) { repo.refreshProfileWithOutcome(first) }
        try {
            assertTrue("refresh request did not arrive", arrived.await(10, TimeUnit.SECONDS))
            dao.selectProfile(other.id)
            val edited = dao.getProfileById(first.id)!!.copy(
                name = "Edited", customHeaders = SecretsCipher.encryptIfSecret("""{"X-Fixture":"new"}"""),
                overridePatch = "log-level: warning",
                updateIntervalHours = 24, uploadUsed = 555
            )
            dao.updateProfile(edited)
            release.countDown()
            val updated = refresh.await().getOrThrow().profile
            assertEquals("Edited", updated.name)
            assertEquals(edited.customHeaders, updated.customHeaders)
            assertEquals(edited.overridePatch, updated.overridePatch)
            assertEquals(24, updated.updateIntervalHours)
            assertEquals(555L, updated.uploadUsed)
            assertFalse(updated.isSelected)
            assertEquals(listOf(other.id), dao.getAllProfilesOnce().filter { it.isSelected }.map { it.id })
            assertEquals(updated, dao.getProfileById(first.id))
            assertEquals(download.outcome.yaml, File(first.filePath).readText())
        } finally {
            release.countDown()
            File(first.filePath).delete()
            File(other.filePath).delete()
        }
    }

    @Test
    fun deletedProfileIsNotRecreatedWhenAnOldRefreshCompletes() = runBlocking {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                arrived.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return MockResponse().setBody(richXray)
            }
        }
        val download = SubscriptionDownload(UniversalConfigConverter.convertToMihomoYamlWithOutcome(richXray))
        val profile = repo.addProfile("Deleted", ProfileType.REMOTE, download, server.url("/sub/deleted").toString()).profile
        val refresh = async(Dispatchers.IO) { repo.refreshProfileWithOutcome(profile) }
        try {
            assertTrue("refresh request did not arrive", arrived.await(10, TimeUnit.SECONDS))
            db.profileDao().deleteProfile(profile)
            File(profile.filePath).delete()
            release.countDown()
            assertTrue(refresh.await().isFailure)
            assertNull(db.profileDao().getProfileById(profile.id))
            assertFalse(File(profile.filePath).exists())
        } finally {
            release.countDown()
            File(profile.filePath).delete()
        }
    }
}
