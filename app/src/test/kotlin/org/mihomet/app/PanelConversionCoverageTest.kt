package org.mihomet.app

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.core.config.UniversalConfigConverter
import org.yaml.snakeyaml.Yaml

/**
 * Panel-fixture conversion coverage (night bench, 2026-11-02): walks EVERY captured
 * subscription fixture from the docker bench panels and asserts the converter yields
 * at least one proxy node, printing a summary table (nodes / groups / warnings) so
 * converter gaps surface as concrete failures.
 */
class PanelConversionCoverageTest {

    private data class Fixture(val panel: String, val file: String, val expectGroups: Boolean)

    private val fixtures: List<Fixture> = listOf(
        Fixture("remnawave", "subscription.txt", false),
        Fixture("remnawave", "subscription.mihomo.yaml", true),
        Fixture("remnawave", "subscription.clash.yaml", true),
        Fixture("remnawave", "subscription.singbox.json", true),
        Fixture("remnawave", "subscription.stash.yaml", true),
        Fixture("remnawave", "subscription.xray.json", true),
        Fixture("marzban", "subscription.txt", false),
        Fixture("marzban", "subscription_clashmeta.yaml", true),
        Fixture("marzban", "subscription_clash.yaml", true),
        Fixture("marzban", "subscription_singbox.json", true),
        Fixture("marzneshin", "subscription.txt", false),
        Fixture("marzneshin", "subscription_links.txt", false),
        Fixture("marzneshin", "subscription_clash.yaml", true),
        Fixture("marzneshin", "subscription_clashmeta.yaml", true),
        Fixture("marzneshin", "subscription_singbox.json", true),
        Fixture("marzneshin", "subscription_xray.json", true),
        Fixture("3x-ui", "subscription.txt", false),
        Fixture("3x-ui", "subscription_json.txt", true),
        Fixture("3x-ui", "subscription_clash.yaml", true),
        Fixture("s-ui", "subscription.txt", false),
        Fixture("s-ui", "subscription_clash.yaml", true),
        Fixture("s-ui", "subscription_singbox.json", true),
        Fixture("hiddify", "subscription.b64.txt", false),
        Fixture("hiddify", "subscription.txt", false),
        Fixture("hiddify", "subscription.clash.yaml", true),
        Fixture("hiddify", "subscription.singbox.json", true),
        Fixture("hiddify", "subscription.xray.json", true),
        Fixture("substore", "subscription.txt", false),
        Fixture("substore", "subscription_clashmeta.yaml", false),
        Fixture("substore", "subscription_clash.yaml", false),
        Fixture("substore", "subscription_singbox.json", true)
    )

    private fun load(panel: String, file: String): String =
        javaClass.getResourceAsStream("/panels/$panel/$file")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("fixture missing: panels/$panel/$file")

    @Suppress("UNCHECKED_CAST")
    private fun convert(fixture: Fixture): Triple<Int, Int, Int> {
        val raw = load(fixture.panel, fixture.file)
        val outcome = UniversalConfigConverter.convertToMihomoYamlWithOutcome(raw)
        val parsed = Yaml().load<Any>(outcome.yaml)
        val map = parsed as? Map<String, Any>
            ?: return Triple(-1, -1, outcome.warnings.size)
        val proxies = (map["proxies"] as? List<*>)?.size ?: 0
        val groups = (map["proxy-groups"] as? List<*>)?.size ?: 0
        return Triple(proxies, groups, outcome.warnings.size)
    }

    @Test
    fun `every bench fixture converts to at least one proxy`() {
        val failures = mutableListOf<String>()
        println("%-12s %-28s %-7s %-7s %-9s %s".format("panel", "fixture", "nodes", "groups", "warnings", "status"))
        for (f in fixtures) {
            val raw = load(f.panel, f.file)
            val (nodes, groups, warnings) = try {
                convert(f)
            } catch (e: Exception) {
                println("%-12s %-28s %-7s %-7s %-9s %s".format(f.panel, f.file, "-", "-", "-", "THROWN: ${e.message?.take(70)}"))
                failures += "${f.panel}/${f.file}: threw ${e.javaClass.simpleName}: ${e.message?.take(120)}"
                continue
            }
            val status = if (nodes > 0) "OK" else "NO NODES"
            println("%-12s %-28s %-7d %-7d %-9d %s".format(f.panel, f.file, nodes, groups, warnings, status))
            if (nodes <= 0) failures += "${f.panel}/${f.file}: 0 nodes parsed"
        }
        assertTrue(
            "converter gaps (see table above):\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    @Test
    fun `group-bearing formats keep their groups through conversion`() {
        for (f in fixtures.filter { it.expectGroups }) {
            val (_, groups, _) = convert(f)
            assertNotNull("fixture ${f.panel}/${f.file} expected groups", groups)
            assertTrue(
                "fixture ${f.panel}/${f.file}: panel provided groups but conversion yielded $groups",
                groups > 0
            )
        }
    }
}
