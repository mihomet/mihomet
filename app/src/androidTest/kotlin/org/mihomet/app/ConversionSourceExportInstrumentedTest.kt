package org.mihomet.app

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.MultiParagraph
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mihomet.app.ui.components.DiagnosticExportActions
import org.mihomet.app.ui.theme.MihometTheme
import java.io.File
import java.io.Writer
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ConversionSourceExportInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resultRegistry = RecordingActivityResultRegistry()
    private val resultRegistryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = resultRegistry
    }

    @Test
    fun safeSaveWritesReportWithoutSourcePayload() {
        val payload = "PRIVATE_SOURCE_TOKEN=do-not-export"
        val safeReport = "Conversion report\nFailure: INVALID_CONFIG"
        showExportActions(safeReport, "$safeReport\nSource payload:\n$payload")

        compose.onNodeWithText(context.getString(R.string.diagnostic_save)).performClick()
        compose.waitForIdle()
        assertEquals(1, resultRegistry.launches.size)
        assertTrue(resultRegistry.launches.single().contract is androidx.activity.result.contract.ActivityResultContracts.CreateDocument)

        val saved = completeSave(0)
        compose.waitUntil(5_000) { saved.exists() && saved.readText() == safeReport }
        assertFalse(saved.readText().contains(payload))
        saved.delete()
    }

    @Test
    fun cancelDoesNotLaunchCreateDocumentAndConfirmationSavesExactSourceReport() {
        val payload = "PRIVATE_SOURCE_TOKEN=exact-source-value"
        val safeReport = "Conversion report\nFailure: INVALID_CONFIG"
        val sourceReport = "$safeReport\n\nSource payload:\n$payload\n"
        val sourceBuilds = AtomicInteger(0)
        showExportActions(safeReport, sourceReport) { sourceBuilds.incrementAndGet() }

        compose.onNodeWithText(context.getString(R.string.diagnostic_export_source)).performClick()
        compose.onNodeWithText(context.getString(R.string.diagnostic_source_cancel)).performClick()
        compose.waitForIdle()
        assertTrue("Cancel must not launch CreateDocument", resultRegistry.launches.isEmpty())
        assertEquals("Source must not be built before confirmation", 0, sourceBuilds.get())

        compose.onNodeWithText(context.getString(R.string.diagnostic_export_source)).performClick()
        compose.onNodeWithText(context.getString(R.string.diagnostic_source_confirm)).performClick()
        compose.waitForIdle()
        assertEquals(1, resultRegistry.launches.size)
        assertTrue(resultRegistry.launches.single().contract is androidx.activity.result.contract.ActivityResultContracts.CreateDocument)
        assertEquals("Source must not be built before a file is selected", 0, sourceBuilds.get())

        compose.runOnIdle {
            resultRegistry.dispatchResult(resultRegistry.launches[0].requestCode, Activity.RESULT_CANCELED, null)
        }
        assertEquals("Cancelling file selection must not build the source", 0, sourceBuilds.get())
        compose.onNodeWithText(context.getString(R.string.diagnostic_export_source)).performClick()
        compose.onNodeWithText(context.getString(R.string.diagnostic_source_confirm)).performClick()
        compose.waitForIdle()
        assertEquals(2, resultRegistry.launches.size)

        val saved = completeSave(1)
        compose.waitUntil(5_000) { saved.exists() && saved.readText() == sourceReport }
        assertEquals(1, sourceBuilds.get())
        assertEquals(sourceReport, saved.readText())
        assertTrue(saved.readText().contains(payload))
        saved.delete()
    }

    @Test
    fun restoredSourceExportRequiresConfirmationAgainEvenWhenSourceIsStillAvailable() {
        val safeReport = "Conversion report\nFailure: INVALID_CONFIG"
        val sourceReport = "$safeReport\n\nSource payload:\nPRIVATE_SOURCE_TOKEN=restored-source\n"
        val sourceBuilds = AtomicInteger(0)
        val restorationTester = StateRestorationTester(compose)
        restorationTester.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides resultRegistryOwner) {
                DiagnosticExportActions(text = safeReport, sensitiveText = { writer ->
                    sourceBuilds.incrementAndGet()
                    writer.write(sourceReport)
                })
            }
        }

        compose.onNodeWithText(context.getString(R.string.diagnostic_export_source)).performClick()
        compose.onNodeWithText(context.getString(R.string.diagnostic_source_confirm)).performClick()
        compose.waitForIdle()
        assertEquals(1, resultRegistry.launches.size)
        assertEquals("Confirmation must not build the source before file selection", 0, sourceBuilds.get())

        restorationTester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals("Restoration must not relaunch CreateDocument", 1, resultRegistry.launches.size)
        assertEquals("Restoration must not build the source before file selection", 0, sourceBuilds.get())

        val saved = completeSave(0)
        try {
            compose.onNodeWithText(context.getString(R.string.diagnostic_export_failed)).assertIsDisplayed()
            assertEquals("Restoration must require fresh confirmation", "", saved.readText())
            assertEquals("The lost builder must not be replaced after restoration", 0, sourceBuilds.get())
        } finally {
            saved.delete()
        }

        compose.onNodeWithText(context.getString(R.string.diagnostic_export_source)).performClick()
        compose.onNodeWithText(context.getString(R.string.diagnostic_source_confirm)).performClick()
        compose.waitForIdle()
        assertEquals(2, resultRegistry.launches.size)
        val retry = completeSave(1)
        try {
            compose.waitUntil(5_000) { retry.readText() == sourceReport }
            assertEquals(1, sourceBuilds.get())
        } finally {
            retry.delete()
        }
    }

    @Test
    fun restoredSourceExportCannotSubstituteAnotherProfilesSource() {
        val sourceBuilds = AtomicInteger(0)
        var sourceReport = "Source payload:\nPRIVATE_SOURCE_TOKEN=profile-A\n"
        val restorationTester = StateRestorationTester(compose)
        restorationTester.setContent {
            val currentSource = sourceReport
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides resultRegistryOwner) {
                DiagnosticExportActions(text = "Synthetic report", sensitiveText = { writer ->
                    sourceBuilds.incrementAndGet()
                    writer.write(currentSource)
                })
            }
        }

        compose.onNodeWithText(context.getString(R.string.diagnostic_export_source)).performClick()
        compose.onNodeWithText(context.getString(R.string.diagnostic_source_confirm)).performClick()
        compose.waitForIdle()
        assertEquals(1, resultRegistry.launches.size)
        assertEquals(0, sourceBuilds.get())

        compose.runOnIdle { sourceReport = "Source payload:\nPRIVATE_SOURCE_TOKEN=profile-B\n" }
        restorationTester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals("Restoration must not relaunch CreateDocument", 1, resultRegistry.launches.size)

        val saved = completeSave(0)
        try {
            compose.onNodeWithText(context.getString(R.string.diagnostic_export_failed)).assertIsDisplayed()
            assertEquals("An export confirmed for A must never write B", "", saved.readText())
            assertEquals("Restoration must not build either source", 0, sourceBuilds.get())
        } finally {
            saved.delete()
        }
    }

    @Test
    fun missingSourceAfterRestorationShowsErrorAndLeavesFileEmpty() {
        val safeReport = "Conversion report\nFailure: INVALID_CONFIG"
        val sourceReport = "$safeReport\n\nSource payload:\nPRIVATE_SOURCE_TOKEN=lost-source\n"
        val sourceBuilds = AtomicInteger(0)
        var sourceAvailable = true
        val restorationTester = StateRestorationTester(compose)
        restorationTester.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides resultRegistryOwner) {
                val sensitiveText: ((Writer) -> Unit)? = if (sourceAvailable) {
                    { writer ->
                        sourceBuilds.incrementAndGet()
                        writer.write(sourceReport)
                    }
                } else null
                DiagnosticExportActions(text = safeReport, sensitiveText = sensitiveText)
            }
        }

        compose.onNodeWithText(context.getString(R.string.diagnostic_export_source)).performClick()
        compose.onNodeWithText(context.getString(R.string.diagnostic_source_confirm)).performClick()
        compose.waitForIdle()
        assertEquals(1, resultRegistry.launches.size)
        assertEquals("Confirmation must not build the source before file selection", 0, sourceBuilds.get())

        compose.runOnIdle { sourceAvailable = false }
        restorationTester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.diagnostic_export_source)).assertDoesNotExist()
        assertEquals("Restoration must not relaunch CreateDocument", 1, resultRegistry.launches.size)
        assertEquals("Restoration must not build the missing source", 0, sourceBuilds.get())

        val saved = completeSave(0)
        try {
            compose.onNodeWithText(context.getString(R.string.diagnostic_export_failed)).assertIsDisplayed()
            assertEquals("Missing source must not fall back to the safe report", "", saved.readText())
            assertEquals("Missing source must not invoke the original builder", 0, sourceBuilds.get())
        } finally {
            saved.delete()
        }
    }

    @Test
    fun russianExportButtonsFitAt320DpAndDoubleFontScale() {
        val russian = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale("ru"))
        })
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides russian,
                LocalDensity provides Density(LocalDensity.current.density, 2f),
                LocalActivityResultRegistryOwner provides resultRegistryOwner
            ) {
                MihometTheme(dynamicColor = false) {
                    Column(Modifier.width(320.dp).padding(16.dp)) {
                        DiagnosticExportActions(text = "Synthetic report", sensitiveText = { it.write("Synthetic source") })
                    }
                }
            }
        }
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), "conversion-export-ru-2x.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        listOf(R.string.diagnostic_save, R.string.diagnostic_share, R.string.diagnostic_export_source).forEach { id ->
            val label = russian.getString(id)
            compose.onNodeWithText(label).assertIsDisplayed()
            val layouts = compose.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes().flatMap { node ->
                val results = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
                results
            }
            assertTrue("Missing text layout: $label", layouts.isNotEmpty())
            layouts.forEach { layout ->
                // String semantics can rebuild a paragraph at the wider incoming constraints.
                val atMeasuredWidth = TextLayoutResult(
                    layoutInput = layout.layoutInput,
                    size = layout.size,
                    multiParagraph = MultiParagraph(
                        intrinsics = layout.multiParagraph.intrinsics,
                        constraints = Constraints(maxWidth = layout.size.width),
                        maxLines = layout.layoutInput.maxLines,
                        overflow = layout.layoutInput.overflow
                    )
                )
                assertFalse(
                    "Export label must not clip: $label " +
                        "(size=${layout.size}, paragraphWidth=${atMeasuredWidth.multiParagraph.width}, " +
                        "paragraphHeight=${atMeasuredWidth.multiParagraph.height})",
                    atMeasuredWidth.hasVisualOverflow
                )
            }
        }
    }

    private fun showExportActions(safeReport: String, sourceReport: String, sourceBuilt: () -> Unit = {}) {
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides resultRegistryOwner) {
                DiagnosticExportActions(text = safeReport, sensitiveText = { writer ->
                    sourceBuilt()
                    writer.write(sourceReport)
                })
            }
        }
    }

    private fun completeSave(index: Int): File {
        val launch = resultRegistry.launches[index]
        val file = File(context.cacheDir, "conversion-reports/export-${System.nanoTime()}.txt")
        file.parentFile?.mkdirs()
        file.createNewFile()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        compose.runOnIdle {
            assertTrue(
                "CreateDocument result must be delivered",
                resultRegistry.dispatchResult(launch.requestCode, Activity.RESULT_OK, Intent().setData(uri))
            )
        }
        return file
    }

    private class RecordingActivityResultRegistry : ActivityResultRegistry() {
        data class Launch(
            val requestCode: Int,
            val contract: ActivityResultContract<*, *>,
            val input: Any?
        )

        val launches = mutableListOf<Launch>()

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?
        ) {
            launches += Launch(requestCode, contract, input)
        }
    }
}
