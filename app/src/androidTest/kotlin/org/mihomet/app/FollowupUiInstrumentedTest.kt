package org.mihomet.app

import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.mihomet.app.data.api.AppLogBuffer
import org.mihomet.app.data.api.LogEntry
import org.mihomet.app.ui.MainActivity

/** Regressions exercised through the real app shell and navigation graph. */
class FollowupUiInstrumentedTest {
    @get:Rule(order = 0)
    val permissionRule: TestRule = if (Build.VERSION.SDK_INT >= 33) {
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    } else TestRule { base, _ -> base }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun openDashboard() {
        compose.waitForIdle()
        val later = context.getString(R.string.batt_later)
        if (compose.onAllNodesWithText(later).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText(later).performClick()
        }
        tab(R.string.nav_dashboard).performClick()
        AppLogBuffer.clear()
    }

    @After fun clearLogs() {
        compose.mainClock.autoAdvance = true
        AppLogBuffer.clear()
    }

    private fun tab(label: Int) = compose.onNode(hasText(context.getString(label)) and hasClickAction())

    @Test fun dashboardEntersFromLeftWhenReturningFromRightmostTab() {
        val status = context.getString(R.string.dashboard_status_disconnected)
        val restingX = compose.onNodeWithText(status).fetchSemanticsNode().boundsInRoot.center.x
        tab(R.string.nav_settings).performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        tab(R.string.nav_dashboard).performClick()
        compose.mainClock.advanceTimeBy(64)
        val enteringX = compose.onNodeWithText(status).fetchSemanticsNode().boundsInRoot.center.x
        assertTrue("Dashboard must enter from the left: $enteringX vs $restingX", enteringX < restingX - 1f)
        compose.mainClock.advanceTimeBy(400)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText(status).assertIsDisplayed()
    }

    @Test fun journalCountsBeyondCapacityAndKeepsReadingPosition() {
        repeat(1500) { index ->
            AppLogBuffer.add(LogEntry(if (index % 2 == 0) "warning" else "error", "entry-$index"))
        }
        compose.onNodeWithContentDescription(context.getString(R.string.nav_logs)).performClick()
        compose.onNodeWithText(context.getString(R.string.logs_retained_count, 1000, 1500L)).assertIsDisplayed()
        compose.onNodeWithText("entry-1499").assertIsDisplayed()
        AppLogBuffer.add(LogEntry("error", "entry-1500"))
        compose.onNodeWithText(context.getString(R.string.logs_retained_count, 1000, 1501L)).assertIsDisplayed()
        compose.onNodeWithText("entry-1500").assertIsDisplayed()

        compose.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        compose.waitForIdle()
        val reading = compose.onAllNodes(hasText("entry-", substring = true)).fetchSemanticsNodes()
            .first { it.boundsInRoot.height > 0f }.config[SemanticsProperties.Text].single().text
        compose.onNodeWithText("entry-1500").assertIsNotDisplayed()
        AppLogBuffer.add(LogEntry("error", "entry-1501"))
        compose.onNodeWithText(reading).assertIsDisplayed()

        compose.onNodeWithText(context.getString(R.string.logs_level_error)).performClick()
        compose.onNodeWithText(context.getString(R.string.logs_retained_count, 501, 752L)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.cd_clear)).performClick()
        compose.onNodeWithText(context.getString(R.string.logs_retained_count, 0, 0L)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.logs_empty)).assertIsDisplayed()
    }
}
