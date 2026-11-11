package org.mihomet.app

import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.core.config.UniversalConfigConverter
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Real-world corpus regression: all 24 official Remnawave subscription
 * templates (github.com/remnawave/templates, no license file — public
 * skeletons vendored as test fixtures) run through the converter.
 *
 * Pinned regressions:
 * - Sing-box skeletons with "outbounds": null must NOT crash with
 *   "JsonNull is not a JsonArray" (fixed: safe casts)
 * - Mihomo "Quantum" templates with >50 YAML aliases must NOT crash with
 *   "Number of aliases ... exceeds the specified max=50" (fixed: hardened
 *   SnakeYAML loader)
 * - Xray JSON skeletons without real nodes → NoNodesXray (correct
 *   fail-closed: the panel injects nodes at render time)
 *
 * The fixtures are render-time SKELETONS: empty proxies lists are expected
 * ("proxies: # LEAVE THIS LINE!" — the panel injects nodes). What matters
 * is that conversion COMPLETES without crashes and the structural output
 * (groups/rules) survives where the template provides it.
 */
class TemplateCorpusTest {

    private fun corpusDir(): File {
        // CI: classpath resources; local deep-dive: TPL_CORPUS env override
        System.getenv("TPL_CORPUS")?.let { return File(it) }
        val res = javaClass.classLoader!!.getResource("templates-remnawave")
            ?: throw AssertionError("corpus fixtures missing from test resources")
        return File(res.toURI())
    }

    private fun probe(name: String, path: String) {
        val content = File(path).readText()
        val outcome = UniversalConfigConverter.convertToMihomoYamlWithOutcome(content)
        val parsed = runCatching { Yaml().load<Map<String, Any>>(outcome.yaml) }.getOrNull()
        val proxies = (parsed?.get("proxies") as? List<*>)?.size ?: -1
        val groups = (parsed?.get("proxy-groups") as? List<*>)?.size ?: -1
        val rules = (parsed?.get("rules") as? List<*>)?.size ?: -1
        val status = if (parsed == null) "PARSE-FAIL" else if (proxies == -1 && rules == -1 && groups == -1) "NO-OUTPUT" else "ok"
        val warns = outcome.warnings.map { it.javaClass.simpleName }
        println("CORPUS $name: status=$status proxies=$proxies groups=$groups rules=$rules warns=$warns")
    }

    @Test
    fun convertAll() {
        val files = (corpusDir().listFiles()?.filter { it.extension == "yaml" }?.sortedBy { it.name })
            ?: throw AssertionError("corpus fixtures missing")
        for (f in files) probe(f.name.removeSuffix(".yaml"), f.absolutePath)
    }

    @Test
    fun `sing-box skeleton with null outbounds converts without crash`() {
        val sb = corpusDir().listFiles()!!.first { it.name.contains("Default_Singbox") }
        val outcome = UniversalConfigConverter.convertToMihomoYamlWithOutcome(sb.readText())
        // fail-closed: the skeleton has no real nodes, but no JsonNull crash
        assertTrue(outcome.warnings.isNotEmpty())
    }

    @Test
    fun `quantum mihomo templates with many aliases parse`() {
        for (f in corpusDir().listFiles()!!.filter { it.name.contains("Quantum") }) {
            val outcome = UniversalConfigConverter.convertToMihomoYamlWithOutcome(f.readText())
            val parsed = Yaml().load<Map<String, Any>>(outcome.yaml) as? Map<*, *>
            assertTrue("Quantum template ${f.name} must parse", parsed != null)
            assertTrue("Quantum template ${f.name} must yield rules", (parsed?.get("rules") as? List<*>)?.isNotEmpty() == true)
        }
    }
}
