package org.mihomet.app

import org.junit.Test
import org.mihomet.app.core.config.UniversalConfigConverter
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Real-world corpus test: every official Remnawave subscription template
 * (github.com/remnawave/templates, subscription-templates-list.json) run
 * through the converter. These are the configs panels ACTUALLY serve —
 * any conversion failure or silent data loss here is a real user bug.
 */
class TemplateCorpusTest {

    private fun probe(name: String, path: String) {
        val content = File(path).readText()
        val outcome = UniversalConfigConverter.convertToMihomoYamlWithOutcome(content)
        val parsed = runCatching { Yaml().load<Map<String, Any>>(outcome.yaml) }.getOrNull()
        val proxies = (parsed?.get("proxies") as? List<*>)?.size ?: -1
        val groups = (parsed?.get("proxy-groups") as? List<*>)?.size ?: -1
        val rules = (parsed?.get("rules") as? List<*>)?.size ?: -1
        val status = if (parsed == null) "PARSE-FAIL" else if (proxies == -1 && rules == -1 && groups == -1) "NO-OUTPUT" else "ok"
        println("CORPUS $name: status=$status proxies=$proxies groups=$groups rules=$rules warns=${outcome.warnings.size}")
        if (outcome.warnings.isNotEmpty()) {
            println("   warns: " + outcome.warnings.toString().take(200))
        }
    }

    @Test
    fun convertAll() {
        val dir = File(System.getenv("TPL_CORPUS") ?: "/tmp/tpl-corpus")
        val files = dir.listFiles()?.filter { it.extension == "yaml" }?.sortedBy { it.name } ?: return
        for (f in files) probe(f.name.removeSuffix(".yaml"), f.absolutePath)
    }
}
