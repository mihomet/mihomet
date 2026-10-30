package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.core.config.UniversalConfigConverter
import org.mihomet.app.core.config.WarningLocalizer

/**
 * Tests for the pure matcher [WarningLocalizer.localizePattern] — no Android
 * context required. The literals under test mirror the `warnings?.add(...)`
 * call sites of [UniversalConfigConverter].
 */
class WarningLocalizerTest {

    private fun requireMatch(warning: String): Pair<Int, List<String>> {
        val match = WarningLocalizer.localizePattern(warning)
        assertNotNull("unmatched warning: $warning", match)
        return match!!
    }

    @Test
    fun `unrecognized node warning maps to resource with uri arg`() {
        val (resId, args) = requireMatch("Не удалось распознать узел: trojan://abc#node")

        assertEquals(R.string.conv_warning_unrecognized, resId)
        assertEquals(listOf("trojan://abc#node"), args)
    }

    @Test
    fun `parse error without details maps to single-arg resource`() {
        val (resId, args) = requireMatch("Ошибка разбора узла: vless://broken")

        assertEquals(R.string.conv_warning_parse_error, resId)
        assertEquals(listOf("vless://broken"), args)
    }

    @Test
    fun `parse error with details maps to two-arg resource`() {
        val (resId, args) = requireMatch("Ошибка разбора узла: ss://x (unexpected end of stream)")

        assertEquals(R.string.conv_warning_parse_error_details, resId)
        assertEquals(listOf("ss://x", "unexpected end of stream"), args)
    }

    @Test
    fun `xray and singbox conversion errors map with message arg`() {
        val xray = requireMatch("Ошибка конвертации Xray JSON: something failed")
        assertEquals(R.string.conv_error_xray, xray.first)
        assertEquals(listOf("something failed"), xray.second)

        val singbox = requireMatch("Ошибка конвертации Sing-Box JSON: bad outbound")
        assertEquals(R.string.conv_error_singbox, singbox.first)
        assertEquals(listOf("bad outbound"), singbox.second)
    }

    @Test
    fun `exact no-nodes and unknown-format warnings map without args`() {
        val cases = mapOf(
            "В подписке не найдено ни одного поддерживаемого узла" to R.string.conv_warning_no_nodes,
            "В Xray-конфиге не найдено ни одного поддерживаемого узла" to R.string.conv_warning_no_nodes_xray,
            "В Sing-Box конфиге не найдено ни одного поддерживаемого узла" to R.string.conv_warning_no_nodes_singbox,
            "Не удалось определить формат конфигурации" to R.string.conv_warning_unknown_format
        )
        for ((literal, expectedResId) in cases) {
            val match = requireMatch(literal)
            assertEquals("literal: $literal", expectedResId, match.first)
            assertEquals("literal: $literal", emptyList<String>(), match.second)
        }
    }

    @Test
    fun `unsupported outbound type maps with type and tag args`() {
        val (resId, args) =
            requireMatch("Тип outbound 'wireguard' ('wg0') не поддерживается конвертером")

        assertEquals(R.string.conv_warning_unsupported_outbound, resId)
        assertEquals(listOf("wireguard", "wg0"), args)
    }

    @Test
    fun `yaml parse error maps with message arg`() {
        val (resId, args) = requireMatch("Ошибка YAML: while parsing a flow node")

        assertEquals(R.string.conv_error_yaml, resId)
        assertEquals(listOf("while parsing a flow node"), args)
    }

    @Test
    fun `unknown warnings pass through as null`() {
        assertNull(WarningLocalizer.localizePattern("Ссылка-информатор о сроке подписки пропущена"))
        assertNull(WarningLocalizer.localizePattern("some totally custom warning"))
        assertNull(WarningLocalizer.localizePattern(""))
    }

    @Test
    fun `every warning produced by the converter is recognized by the matcher`() {
        val inputs = listOf(
            "notaproxy://example.com", // unrecognized format → unknown-format warning
            "{ \"inbounds\": [], \"outbounds\": [{ \"type\": \"http\", \"tag\": \"p\" }] }" // sing-box → unsupported outbound + no nodes
        )
        for (input in inputs) {
            val outcome = UniversalConfigConverter.convertToMihomoYamlWithOutcome(input)
            assertTrue("expected warnings for input: $input", outcome.warnings.isNotEmpty())
            for (warning in outcome.warnings) {
                assertNotNull("unmatched converter warning: $warning", WarningLocalizer.localizePattern(warning))
            }
        }
    }
}
