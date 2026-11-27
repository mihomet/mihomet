package org.mihomet.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.mihomet.app.ui.components.D2BackHeader
import org.mihomet.app.ui.design2.*
import org.mihomet.app.ui.theme.MihometTheme
import org.mihomet.app.ui.screens.dashboard.ConnectionHero
import org.mihomet.app.ui.screens.dashboard.DashboardUiState

class CompactLayoutInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun journalAtDefaultFontScale() = assertJournalFits(1f)
    @Test fun journalAtLargeFontScale() = assertJournalFits(1.5f)
    @Test fun journalAtMaximumFontScale() = assertJournalFits(2f)

    @Test fun connectingUsesSelectedAccent() {
        var expected = Color.Unspecified
        compose.setContent {
            MihometTheme(dynamicColor = false, darkTheme = false, accentColorId = "teal") {
                expected = MaterialTheme.colorScheme.primaryContainer
                ConnectionHero(DashboardUiState(isConnecting = true)) {}
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = compose.onNodeWithContentDescription(context.getString(R.string.action_cancel)).captureToImage()
        val pixel = image.toPixelMap()[image.width / 4, image.height / 2]
        assertEquals("Connecting must retain the selected primary accent", expected.toArgb(), pixel.toArgb())
    }

    private fun assertJournalFits(fontScale: Float) {
        val labels = listOf("Все", "Информация", "Предупреждения", "Ошибки", "Отладка")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MihometTheme(dynamicColor = false) {
                    Column(Modifier.width(320.dp).padding(16.dp)) {
                        D2BackHeader("Журнал", "Назад", {}) {
                            D2IconAction(Icons.Default.ContentCopy, "Копировать", {})
                            D2IconAction(Icons.Default.Share, "Поделиться", {})
                            D2IconAction(Icons.Default.Delete, "Очистить", {})
                        }
                        D2Segmented(labels.map { D2SegmentOption(it, it) }, "Все", {})
                    }
                }
            }
        }
        compose.waitForIdle()
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        File(target.getExternalFilesDir(null), "compact-$fontScale.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val titles = textLayouts("Журнал")
        assertEquals("There must be one fully visible title at $fontScale", 1, titles.size)
        assertEquals(1, titles.single().lineCount)
        assertFalse("The title must not clip at $fontScale", titles.single().hasVisualOverflow)
        labels.forEach { label ->
            compose.onNodeWithText(label).assertIsDisplayed()
            val layouts = textLayouts(label)
            assertTrue("Missing text layout: $label", layouts.isNotEmpty())
            layouts.forEach { layout ->
                assertFalse("Button label is clipped at $fontScale: $label; size=${layout.size}; paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}; constraints=${layout.layoutInput.constraints}; width=${layout.didOverflowWidth}; height=${layout.didOverflowHeight}", layout.hasVisualOverflow)
                assertEquals("Button must keep its full label on one line: $label", 1, layout.lineCount)
            }
        }
    }

    private fun textLayouts(text: String): List<TextLayoutResult> =
        compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().flatMap { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            results
        }
}
