package org.mihomet.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mihomet.app.core.CoreManager
import org.mihomet.app.core.config.ConfigGenerator
import org.mihomet.app.core.config.UniversalConfigConverter
import org.mihomet.app.data.preferences.AppSettings
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class XrayCoreValidationInstrumentedTest {
    @Test
    fun officialClientFixturesPassTheBundledCoreConfigurationCheck() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val core = CoreManager(context)
        val directory = File(context.cacheDir, "xray-core-validation").apply { mkdirs() }
        try {
            core.ensureGeoDatabases(directory)
            for (fixture in listOf("vless-reality", "vless-xhttp", "vmess-ws", "vless-grpc", "trojan-tls", "routing-logic", "mapping-edges")) {
                val input = instrumentation.context.assets.open("$fixture.jsonc").bufferedReader().use { it.readText() }
                val outcome = UniversalConfigConverter.convertToMihomoYamlWithOutcome(input)
                assertTrue("$fixture: ${outcome.warnings}", outcome.report!!.importedNodes > 0)
                if (fixture == "mapping-edges") assertEquals(3, outcome.report!!.importedNodes)
                if (fixture == "routing-logic") assertEquals(5, outcome.report!!.importedRules)
                File(directory, "config.yaml").writeText(ConfigGenerator.generateRuntimeConfig(
                    outcome.yaml, AppSettings(controllerSecret = "instrumented-fixture-secret")
                ))
                val process = ProcessBuilder(core.getBundledLibraryPath(), "-t", "-d", directory.absolutePath,
                    "-f", File(directory, "config.yaml").absolutePath).redirectErrorStream(true).start()
                try {
                    assertTrue("$fixture: core check timed out", process.waitFor(20, TimeUnit.SECONDS))
                    val output = process.inputStream.bufferedReader().use { it.readText() }
                    assertEquals("$fixture: ${output.take(1200)}", 0, process.exitValue())
                } finally {
                    process.destroyForcibly()
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
