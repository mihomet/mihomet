package org.mihomet.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.mihomet.app.data.db.ProfileDao
import org.mihomet.app.ui.MainActivity
import java.io.File

/** Real VIEW intents → navigation → confirmation → HTTP → encrypted metadata and profile file. */
@RunWith(AndroidJUnit4::class)
class ImportFlowInstrumentedTest {
    @get:Rule(order = 0)
    val permissionRule: TestRule = if (Build.VERSION.SDK_INT >= 33) {
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    } else TestRule { base, _ -> base }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dao get() = GlobalContext.get().get<ProfileDao>()
    private val prefix = "ImportFlow-${System.nanoTime()}"
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val title = "$prefix-${request.path?.substringAfterLast('/')}"
                    return MockResponse().addHeader("profile-title", title).setBody("""
                        proxies:
                          - {name: flow-node, type: socks5, server: 127.0.0.1, port: 1080}
                        proxy-groups:
                          - {name: AUTO, type: url-test, proxies: [flow-node], url: 'https://example.com', interval: 300}
                        rules:
                          - MATCH,AUTO
                    """.trimIndent())
                }
            }
            start()
        }
        compose.waitForIdle()
        val later = context.getString(R.string.batt_later)
        if (compose.onAllNodesWithText(later).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText(later).performClick()
        }
        compose.onNode(hasText(context.getString(R.string.nav_dashboard)) and hasClickAction()).performClick()
    }

    @After
    fun tearDown() {
        server.shutdown()
        runBlocking {
            dao.getAllProfilesOnce().filter { it.name.startsWith(prefix) }.forEach {
                dao.deleteProfile(it)
                File(it.filePath).delete()
            }
        }
    }

    private fun sendImport(path: String): String {
        val url = server.url("/sub/$path").toString()
        compose.activityRule.scenario.onActivity { activity ->
            activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("v2rayng://install-sub?url=${Uri.encode(url)}")
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            })
        }
        return url
    }

    private fun waitForText(text: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun externalImportNavigatesFromDashboardAndCancelDoesNotFetch() {
        val url = sendImport("cancel")
        waitForText(url)
        assertEquals(0, server.requestCount)
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        compose.waitForIdle()
        assertEquals(0, server.requestCount)
        assertFalse(runBlocking { dao.getAllProfilesOnce().any { it.name.startsWith(prefix) } })
    }

    @Test
    fun rapidImportsKeepTheirOwnConfirmationsAndPersistExactlyOnce() {
        val first = sendImport("first")
        waitForText(first)
        val second = sendImport("second")
        compose.waitForIdle()
        assertEquals(0, server.requestCount)
        assertEquals(0, compose.onAllNodesWithText(second).fetchSemanticsNodes().size)

        compose.onNodeWithText(context.getString(R.string.action_add)).performClick()
        waitForText(second)
        compose.waitUntil(15_000) { runBlocking { dao.getAllProfilesOnce().any { it.name == "$prefix-first" } } }
        assertEquals(1, server.requestCount)
        compose.onNodeWithText(context.getString(R.string.action_add)).performClick()
        compose.waitUntil(15_000) { runBlocking { dao.getAllProfilesOnce().count { it.name.startsWith(prefix) } == 2 } }
        assertEquals(2, server.requestCount)
        runBlocking {
            dao.getAllProfilesOnce().filter { it.name.startsWith(prefix) }.forEach {
                assertEquals(true, it.url.startsWith("enc1:"))
                assertEquals(true, File(it.filePath).readText().contains("flow-node"))
            }
        }
    }
}
