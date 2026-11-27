package org.mihomet.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mihomet.app.core.CoreManager
import org.mihomet.app.core.config.UniversalConfigConverter
import java.io.File

/** Opt-in wire tests driven by scripts/test-xray-loopback.py; all traffic stays on the test host. */
@RunWith(AndroidJUnit4::class)
class XrayLoopbackInstrumentedTest {
    @Test
    fun convertedClientsReachTheLocalHttpServerThroughXray() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val fileName = args.getString("xrayLoopbackFixture")
        assumeTrue("Run scripts/test-xray-loopback.py to provide the local Xray server", fileName != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.cacheDir, fileName!!)
        try {
            val root = Json.parseToJsonElement(fixture.readText()).jsonObject
            val destination = root.getValue("destination").jsonPrimitive.content
            val clients = root.getValue("clients").jsonArray
            for (item in clients) {
                val client = item.jsonObject
                val name = client.getValue("name").jsonPrimitive.content
                val config = client.getValue("config")
                val source = if (config is JsonPrimitive) config.content else config.toString()
                val outcome = UniversalConfigConverter.convertToMihomoYamlWithOutcome(source)
                assertTrue("$name: ${outcome.warnings}", outcome.yaml.isNotBlank() && (outcome.report?.importedNodes ?: 1) == 1)
                val result = CoreManager(context).runTemporaryCoreProbe(outcome.yaml) { api ->
                    api.testNodeDelay(name, "$destination/$name", 7000).getOrThrow()
                }
                assertTrue("$name: ${result.exceptionOrNull()?.javaClass?.simpleName}", result.isSuccess)
                val delay = result.getOrThrow()
                assertTrue("$name: native URL test returned $delay", delay >= 0)
            }
        } finally {
            fixture.delete()
        }
    }
}
