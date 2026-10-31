package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.core.config.ConversionWarning
import org.mihomet.app.core.config.UniversalConfigConverter
import org.mihomet.app.core.config.WarningLocalizer

/**
 * Tests for the pure type-driven mapping [WarningLocalizer.resolve] — no
 * Android context required. Every [ConversionWarning] kind the producers
 * emit must map to the documented string resource with the expected format
 * args; the stable-key round trip pins the serializable form.
 */
class WarningLocalizerTest {

    @Test
    fun `unrecognized node warning maps to resource with uri arg`() {
        val (resId, args) = WarningLocalizer.resolve(ConversionWarning.UnrecognizedNode("trojan://abc#node"))

        assertEquals(R.string.conv_warning_unrecognized, resId)
        assertEquals(listOf<String?>("trojan://abc#node"), args)
    }

    @Test
    fun `parse error without reason maps to single-arg resource`() {
        val (resId, args) = WarningLocalizer.resolve(ConversionWarning.ProxyParseError("vless://broken"))

        assertEquals(R.string.conv_warning_parse_error, resId)
        assertEquals(listOf<String?>("vless://broken"), args)
    }

    @Test
    fun `parse error with reason maps to two-arg resource`() {
        val (resId, args) =
            WarningLocalizer.resolve(ConversionWarning.ProxyParseError("ss://x", "unexpected end of stream"))

        assertEquals(R.string.conv_warning_parse_error_details, resId)
        assertEquals(listOf<String?>("ss://x", "unexpected end of stream"), args)
    }

    @Test
    fun `parse error with null reason keeps the details variant only when reason exists`() {
        // A null reason must NOT select the details variant (that variant would
        // render an empty "()" detail) — it maps to the plain parse error.
        assertEquals(
            R.string.conv_warning_parse_error,
            WarningLocalizer.resolve(ConversionWarning.ProxyParseError("ss://x", null)).first
        )
    }

    @Test
    fun `xray and singbox conversion errors map with message arg`() {
        val xray = WarningLocalizer.resolve(ConversionWarning.XrayError("something failed"))
        assertEquals(R.string.conv_error_xray, xray.first)
        assertEquals(listOf<String?>("something failed"), xray.second)

        val singbox = WarningLocalizer.resolve(ConversionWarning.SingBoxError("bad outbound"))
        assertEquals(R.string.conv_error_singbox, singbox.first)
        assertEquals(listOf<String?>("bad outbound"), singbox.second)
    }

    @Test
    fun `exact no-nodes and unknown-format warnings map without args`() {
        val cases = listOf(
            ConversionWarning.NoNodes to R.string.conv_warning_no_nodes,
            ConversionWarning.NoNodesXray to R.string.conv_warning_no_nodes_xray,
            ConversionWarning.NoNodesSingBox to R.string.conv_warning_no_nodes_singbox,
            ConversionWarning.UnknownFormat to R.string.conv_warning_unknown_format
        )
        for ((warning, expectedResId) in cases) {
            val match = WarningLocalizer.resolve(warning)
            assertEquals("warning: $warning", expectedResId, match.first)
            assertEquals("warning: $warning", emptyList<String?>(), match.second)
        }
    }

    @Test
    fun `unsupported outbound type maps with type and tag args`() {
        val (resId, args) =
            WarningLocalizer.resolve(ConversionWarning.UnsupportedOutbound("wireguard", "wg0"))

        assertEquals(R.string.conv_warning_unsupported_outbound, resId)
        assertEquals(listOf<String?>("wireguard", "wg0"), args)
    }

    @Test
    fun `profile key dropped maps with key arg`() {
        val (resId, args) = WarningLocalizer.resolve(ConversionWarning.ProfileKeyDropped("listeners"))

        assertEquals(R.string.conv_warning_profile_key_dropped, resId)
        assertEquals(listOf<String?>("listeners"), args)
    }

    @Test
    fun `yaml parse error maps with message arg`() {
        val (resId, args) = WarningLocalizer.resolve(ConversionWarning.YamlError("while parsing a flow node"))

        assertEquals(R.string.conv_error_yaml, resId)
        assertEquals(listOf<String?>("while parsing a flow node"), args)
    }

    @Test
    fun `null error messages stay null so localize substitutes the unknown-error resource`() {
        // The pure mapping keeps the arg null; the Context-based localize()
        // replaces null with R.string.profiles_unknown_error (EN "Unknown error").
        assertEquals(listOf<String?>(null), WarningLocalizer.resolve(ConversionWarning.YamlError(null)).second)
        assertEquals(listOf<String?>(null), WarningLocalizer.resolve(ConversionWarning.XrayError(null)).second)
        assertEquals(listOf<String?>(null), WarningLocalizer.resolve(ConversionWarning.SingBoxError(null)).second)
    }

    @Test
    fun `mapping is type-driven - different warning kinds never share a resource`() {
        val resIds = setOf(
            WarningLocalizer.resolve(ConversionWarning.UnrecognizedNode("x")).first,
            WarningLocalizer.resolve(ConversionWarning.ProxyParseError("x")).first,
            WarningLocalizer.resolve(ConversionWarning.YamlError("x")).first,
            WarningLocalizer.resolve(ConversionWarning.XrayError("x")).first,
            WarningLocalizer.resolve(ConversionWarning.SingBoxError("x")).first,
            WarningLocalizer.resolve(ConversionWarning.UnsupportedOutbound("x", "y")).first,
            WarningLocalizer.resolve(ConversionWarning.NoNodes).first,
            WarningLocalizer.resolve(ConversionWarning.NoNodesXray).first,
            WarningLocalizer.resolve(ConversionWarning.NoNodesSingBox).first,
            WarningLocalizer.resolve(ConversionWarning.UnknownFormat).first,
            WarningLocalizer.resolve(ConversionWarning.ProfileKeyDropped("x")).first
        )
        assertEquals("every warning kind maps to its own resource", resIds.size, 11)
        // Regression guard for the deleted Russian-prefix matching: a wording
        // change in the converter can no longer break localization, because no
        // mapping depends on warning text.
        assertNotEquals(R.string.conv_warning_unrecognized, R.string.conv_warning_parse_error)
    }

    @Test
    fun `stable keys round-trip through fromStableKey`() {
        val warnings = listOf(
            ConversionWarning.UnrecognizedNode("naive://x"),
            ConversionWarning.ProxyParseError("vless://broken"),
            ConversionWarning.ProxyParseError("ss://x", "boom"),
            ConversionWarning.YamlError("bad yaml"),
            ConversionWarning.YamlError(null),
            ConversionWarning.XrayError("xerr"),
            ConversionWarning.SingBoxError(null),
            ConversionWarning.UnsupportedOutbound("http", "p"),
            ConversionWarning.NoNodes,
            ConversionWarning.NoNodesXray,
            ConversionWarning.NoNodesSingBox,
            ConversionWarning.UnknownFormat,
            ConversionWarning.ProfileKeyDropped("listeners")
        )
        for (warning in warnings) {
            val key = warning.toStableKey()
            // The warning CODE is a stable ASCII token; the params are raw
            // technical data (URIs / exception messages), never localized text.
            assertTrue("stable key must start with an ascii code: $key", key.substringBefore('|').all { it.code in 33..126 })
            assertEquals("round-trip failed for $warning", warning, ConversionWarning.fromStableKey(key))
        }
        // Unknown codes are rejected instead of guessed
        assertEquals(null, ConversionWarning.fromStableKey("totally_unknown|x"))
    }

    @Test
    fun `every warning produced by the converter resolves to a known resource`() {
        val inputs = listOf(
            "notaproxy://example.com", // unrecognized format → unknown-format warning
            "{ \"inbounds\": [], \"outbounds\": [{ \"type\": \"http\", \"tag\": \"p\" }] }" // sing-box → unsupported outbound + no nodes
        )
        for (input in inputs) {
            val outcome = UniversalConfigConverter.convertToMihomoYamlWithOutcome(input)
            assertTrue("expected warnings for input: $input", outcome.warnings.isNotEmpty())
            for (warning in outcome.warnings) {
                val (resId, _) = WarningLocalizer.resolve(warning)
                assertTrue("unresolved converter warning: $warning", resId != 0)
            }
        }
    }

    @Test
    fun `converter emits the expected typed warning kinds`() {
        val unknownFormat =
            UniversalConfigConverter.convertToMihomoYamlWithOutcome("notaproxy://example.com").warnings
        assertTrue(ConversionWarning.UnknownFormat in unknownFormat)

        val singBox = UniversalConfigConverter.convertToMihomoYamlWithOutcome(
            "{ \"inbounds\": [], \"outbounds\": [{ \"type\": \"http\", \"tag\": \"p\" }] }"
        ).warnings
        assertNotNull(singBox.find { it == ConversionWarning.UnsupportedOutbound("http", "p") })
        assertNotNull(singBox.find { it == ConversionWarning.NoNodesSingBox })
    }
}
