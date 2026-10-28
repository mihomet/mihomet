package org.mihomet.app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mihomet.app.core.config.UniversalConfigConverter
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.Base64

/**
 * Fixture-based converter tests against REAL subscription payloads captured from
 * live panels running in the docker test bench (compose lives outside the repo,
 * see /mnt/Data/mihomet-panel-bench and TODO.md "Panel Compatibility" milestone).
 *
 * Any fixture dropped into src/test/resources/panels/<panel>/ is picked up
 * automatically. The invariant under test: no node may be silently lost or
 * malformed by UniversalConfigConverter.
 */
class PanelsSubscriptionTest {

    private val panelsDir = File("src/test/resources/panels")

    // Schemes the URI parser is required to understand
    private val supportedSchemes = setOf(
        "vless", "vmess", "trojan", "ss", "hysteria2", "hy2", "hysteria", "tuic", "ssh", "snell", "wg"
    )

    @Test
    fun `test real panel fixtures convert without losing nodes`() {
        val panelDirs = panelsDir.listFiles()?.filter { it.isDirectory }.orEmpty()
        assertTrue("No panel fixtures found under $panelsDir", panelDirs.isNotEmpty())

        val problems = mutableListOf<String>()
        var panelsWithData = 0
        for (panel in panelDirs.sortedBy { it.name }) {
            val fixtures = panel.listFiles()!!
                .filter { it.isFile }
                .filter { it.name.startsWith("subscription") }   // skip captured headers / user metadata
                .filterNot { it.name.endsWith(".info.json") }    // subscription-userinfo metadata, not a config
                .filter { it.length() > 0L }
            // Panel directory being populated by the test bench — skip while empty
            if (fixtures.isEmpty()) continue
            panelsWithData++

            for (fixture in fixtures) {
                val content = fixture.readText().trim()
                if (content.isEmpty()) continue
                runCatching { assertFixture(panel.name, fixture.name, content) }
                    .onFailure { problems.add("[${panel.name}/${fixture.name}] ${it.message}") }
            }
        }
        assertTrue(
            "Converter failed on real panel fixtures ($panelsWithData panels checked):\n${problems.joinToString("\n")}",
            problems.isEmpty()
        )
    }

    private fun assertFixture(panel: String, name: String, content: String) {
        val outcome = UniversalConfigConverter.convertToMihomoYamlWithOutcome(content)
        val parsed: Map<String, Any> = Yaml().load(outcome.yaml)
            ?: throw AssertionError("converter output is not a YAML mapping (warnings=${outcome.warnings})")

        val proxies = (parsed["proxies"] as? List<Map<String, Any>>).orEmpty()
        if (proxies.isEmpty()) {
            fail("no proxies produced (warnings=${outcome.warnings})")
        }

        // Every proxy must be structurally valid for mihomo
        for ((index, proxy) in proxies.withIndex()) {
            for (field in listOf("name", "type", "server", "port")) {
                if (proxy[field] == null) {
                    fail("proxy #$index is missing required field '$field'")
                }
            }
        }

        // The URI invariant applies only to URI-list payloads (base64 or plain links):
        // every URI either becomes a proxy or is dropped with an explicit warning —
        // nothing may vanish silently. Schemes mihomo cannot represent (naive, mieru, …)
        // are legitimately warning-dropped. Hiddify additionally embeds a pseudo-link
        // showing the expiry date (trojan://…--YYYY.MM.DD.time:<port>) — display metadata.
        val decoded = decodeMaybeBase64(content)
        val firstRealLine = decoded.lineSequence()
            .firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("#") }
            ?.trim()?.lowercase()
        val isUriList = firstRealLine != null &&
                supportedSchemes.any { firstRealLine.startsWith("$it://") }
        if (!isUriList) return

        val uriLines = decoded.lineSequence().map { it.trim() }
            .filter { it.contains("://") && !it.startsWith("http") }
            .toList()
        val supported = uriLines.count { it.substringBefore("://").lowercase() in supportedSchemes }
        val unsupported = uriLines.size - supported
        val pseudoLinks = uriLines.count {
            Regex("^trojan://.*--\\d{4}\\.\\d{2}\\.\\d{2}\\.time:\\d+").containsMatchIn(it)
        }

        if (supported - pseudoLinks > 0 && proxies.size != supported - pseudoLinks) {
            fail(
                "expected ${supported - pseudoLinks} nodes but conversion produced ${proxies.size} " +
                    "(warnings=${outcome.warnings})"
            )
        }
        val drops = outcome.warnings.count {
            it.startsWith("Не удалось распознать узел") || it.startsWith("Ошибка разбора узла")
        }
        if (drops != unsupported + pseudoLinks) {
            fail(
                "expected ${unsupported + pseudoLinks} dropped URIs (unsupported/pseudo) to be " +
                    "reported via warnings, but found $drops — nodes may be vanishing silently " +
                    "(warnings=${outcome.warnings})"
            )
        }
    }

    private fun countSupportedUris(content: String): Int =
        content.lineSequence()
            .map { it.trim() }
            .filter { it.contains("://") && !it.startsWith("http") }
            .count { it.substringBefore("://").lowercase() in supportedSchemes }

    private fun decodeMaybeBase64(content: String): String {
        if (content.startsWith("{") || content.startsWith("[") ||
            content.startsWith("proxies:") || content.startsWith("port:") ||
            content.startsWith("mixed-port:") || content.contains("://")
        ) {
            return content
        }
        return runCatching {
            val clean = content.replace("\n", "").replace("\r", "").replace(" ", "")
            val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
            String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
        }.getOrElse { content }
    }
}
