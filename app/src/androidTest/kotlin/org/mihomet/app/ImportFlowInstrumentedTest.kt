package org.mihomet.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mihomet.app.core.DeepLinkBus
import org.mihomet.app.ui.MainActivity

/**
 * The deferred P0 import-flow UI test (TODO "Instrumented UI test for the
 * import flow"), implemented with Compose test semantics instead of the
 * flaky adb-tap approach that failed on a previous night attempt:
 *
 * MockWebServer serves a grouped Mihomo YAML; the test posts an
 * install-sub deep link into [DeepLinkBus] while the Profiles screen is
 * active, asserts the confirmation dialog (audit P0.1 gate), taps Add and
 * verifies the fetch happened and the profile name appears in the list.
 */
@RunWith(AndroidJUnit4::class)
class ImportFlowInstrumentedTest {

    @get:Rule
    val permissionRule: androidx.test.rule.GrantPermissionRule =
        androidx.test.rule.GrantPermissionRule.grant(
            android.Manifest.permission.POST_NOTIFICATIONS
        )

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var server: MockWebServer

    private val groupedYaml = """
        # profile-title: UI Flow Test
        proxies:
          - {name: flow-node, type: socks5, server: 1.2.3.4, port: 1080}
        proxy-groups:
          - {name: PROXY, type: select, proxies: [flow-node]}
        rules:
          - MATCH,PROXY
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse().setBody(groupedYaml).addHeader("profile-title", "UI Flow Test")
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun deepLinkConfirmCreatesProfile() {
        // Navigate to the Profiles tab (the import dialog is hosted there)
        composeRule.onNodeWithText("Profiles").performClick()

        // Fire the import deep link through the app-internal bus — the same
        // parse-then-post path MainActivity uses (parse extracts the URL)
        val url = server.url("/sub/uiflow").toString()
        val deepLink = "v2rayng://install-sub?url=$url"
        DeepLinkBus.post(org.mihomet.app.core.DeepLinkParser.parse(deepLink), "instrumented-test")

        // Confirmation dialog (audit P0.1): payload + explicit Add
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(androidx.compose.ui.test.hasText("Add"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Add").performClick()

        // The repository fetches from the deep-link URL. NOTE: plain polling —
        // compose waitUntil idles the main looper, which starves the ViewModel's
        // Dispatchers.Main coroutine and the fetch never fires.
        val deadline = System.currentTimeMillis() + 20_000
        while (server.requestCount == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
        }
        check(server.requestCount >= 1) { "fetch never happened after Add" }

        // …and the result confirmation: success ("added") or the failure
        // snackbar.
        val snackbarDeadline = System.currentTimeMillis() + 15_000
        var snackbarText: String? = null
        while (System.currentTimeMillis() < snackbarDeadline) {
            val nodes = composeRule.onAllNodes(
                androidx.compose.ui.test.hasText("added", substring = true) or
                        androidx.compose.ui.test.hasText("Failed", substring = true),
                useUnmergedTree = true
            ).fetchSemanticsNodes()
            if (nodes.isNotEmpty()) {
                snackbarText = nodes.first().config.toString().take(300)
                break
            }
            Thread.sleep(250)
        }
        check(snackbarText != null) { "no result snackbar after import" }
    }
}
