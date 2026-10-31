package org.mihomet.app

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.data.subs.SubscriptionHeaders
import org.mihomet.app.data.subs.SubscriptionHeaders.Violation
import org.mihomet.app.data.subs.SubscriptionHeaders.HeaderValidationException

/**
 * Strict guards of the user-provided subscription headers
 * ([SubscriptionHeaders]): header-injection (CR/LF / control chars), size
 * caps and the legacy lenient format parsing (JSON object or `Key: Value`
 * lines).
 */
class SubscriptionHeadersGuardsTest {

    // ------------------------------------------------------------ strict parse (happy path)

    @Test
    fun `lines format parses and roundtrips through json`() {
        val raw = """
            User-Agent: Happ/3.6.0 (Android; Xray)
            Authorization: Bearer token:1
            x-custom = value-2
        """.trimIndent()

        val parsed = SubscriptionHeaders.parse(raw)

        assertEquals(
            mapOf(
                "User-Agent" to "Happ/3.6.0 (Android; Xray)",
                "Authorization" to "Bearer token:1",
                "x-custom" to "value-2"
            ),
            parsed
        )
        // JSON roundtrip keeps every entry
        assertEquals(parsed, SubscriptionHeaders.parseLenient(SubscriptionHeaders.toJson(parsed)))
    }

    @Test
    fun `json object input is accepted by the strict parser`() {
        val raw = """{"User-Agent": "Mihomet/1.0", "x-test": "ok"}"""

        val parsed = SubscriptionHeaders.parse(raw)

        assertEquals(mapOf("User-Agent" to "Mihomet/1.0", "x-test" to "ok"), parsed)
    }

    @Test
    fun `empty input parses to empty map`() {
        assertTrue(SubscriptionHeaders.parse("").isEmpty())
        assertTrue(SubscriptionHeaders.parse("   \n  ").isEmpty())
        assertTrue(SubscriptionHeaders.parse("{}").isEmpty())
    }

    @Test
    fun `blank lines without delimiter are skipped`() {
        assertTrue(SubscriptionHeaders.parse("random text with no delimiter").isEmpty())
    }

    // ------------------------------------------------------------ header-injection guard

    @Test
    fun `newline inside a json value is rejected`() {
        val raw = """{"X-Test": "line1\nHost: evil.com"}"""
        val error = runCatching { SubscriptionHeaders.parse(raw) }.exceptionOrNull()

        assertTrue("expected HeaderValidationException", error is HeaderValidationException)
        assertEquals(Violation.FORBIDDEN_CHAR, (error as HeaderValidationException).violation)
    }

    @Test
    fun `carriage return inside a json value is rejected`() {
        val raw = """{"X-Test": "a\rb"}"""

        val violation = runCatching { SubscriptionHeaders.parse(raw) }
            .exceptionOrNull()?.let { it as HeaderValidationException }?.violation

        assertEquals(Violation.FORBIDDEN_CHAR, violation)
    }

    @Test
    fun `control character in a header name is rejected`() {
        val raw = """{"X\u0000Test": "value"}"""

        val violation = runCatching { SubscriptionHeaders.parse(raw) }
            .exceptionOrNull()?.let { it as HeaderValidationException }?.violation

        assertEquals(Violation.FORBIDDEN_CHAR, violation)
    }

    @Test
    fun `raw line with embedded newline inside value splits but stays safe at the wire`() {
        // Lines format physically cannot smuggle a newline into a value (the
        // split is per-line), but the stored value must still be stripped on
        // the request path — sanitizeForRequest is the wire guard.
        val sanitized = SubscriptionHeaders.sanitizeForRequest(
            mapOf("X-Test" to "a\nb\rc\u0000d")
        )
        assertEquals("abcd", sanitized["X-Test"])
    }

    @Test
    fun `sanitizeForRequest drops headers whose name degrades to blank`() {
        val sanitized = SubscriptionHeaders.sanitizeForRequest(
            mapOf("\u0001" to "value", "X-Keep" to "kept")
        )

        assertNull(sanitized[""])
        assertEquals("kept", sanitized["X-Keep"])
        assertEquals(1, sanitized.size)
    }

    // ------------------------------------------------------------ size caps

    @Test
    fun `oversized raw input is rejected`() {
        val raw = "X-Test: " + "a".repeat(SubscriptionHeaders.MAX_RAW_LENGTH)

        val violation = runCatching { SubscriptionHeaders.parse(raw) }
            .exceptionOrNull()?.let { it as HeaderValidationException }?.violation

        assertEquals(Violation.RAW_TOO_LARGE, violation)
    }

    @Test
    fun `too many headers are rejected`() {
        val raw = (1..(SubscriptionHeaders.MAX_HEADERS + 1))
            .joinToString("\n") { "X-H$it: v$it" }

        val violation = runCatching { SubscriptionHeaders.parse(raw) }
            .exceptionOrNull()?.let { it as HeaderValidationException }?.violation

        assertEquals(Violation.TOO_MANY_HEADERS, violation)
    }

    @Test
    fun `exactly max headers count passes`() {
        val raw = (1..SubscriptionHeaders.MAX_HEADERS)
            .joinToString("\n") { "X-H$it: v$it" }

        assertEquals(SubscriptionHeaders.MAX_HEADERS, SubscriptionHeaders.parse(raw).size)
    }

    @Test
    fun `too long header name is rejected`() {
        val raw = "${"n".repeat(SubscriptionHeaders.MAX_NAME_LENGTH + 1)}: v"

        val violation = runCatching { SubscriptionHeaders.parse(raw) }
            .exceptionOrNull()?.let { it as HeaderValidationException }?.violation

        assertEquals(Violation.NAME_TOO_LONG, violation)
    }

    @Test
    fun `too long header value is rejected`() {
        val raw = "X-Test: ${"v".repeat(SubscriptionHeaders.MAX_VALUE_LENGTH + 1)}"

        val violation = runCatching { SubscriptionHeaders.parse(raw) }
            .exceptionOrNull()?.let { it as HeaderValidationException }?.violation

        assertEquals(Violation.VALUE_TOO_LONG, violation)
    }

    @Test
    fun `blank header name is rejected`() {
        val violation = runCatching { SubscriptionHeaders.parse(": value") }
            .exceptionOrNull()?.let { it as HeaderValidationException }?.violation

        assertEquals(Violation.EMPTY_NAME, violation)
    }

    // ------------------------------------------------------------ legacy lenient behavior

    @Test
    fun `json-shaped garbage falls back to line parsing (legacy rows)`() {
        val parsed = SubscriptionHeaders.parseLenient("{not-valid-json: but has colon}")

        assertEquals(mapOf("{not-valid-json" to "but has colon}"), parsed)
    }

    @Test
    fun `toJson produces a decodable json object`() {
        val json = SubscriptionHeaders.toJson(mapOf("X-Test" to "v:1"))
        val decoded = Json.decodeFromString<Map<String, String>>(json)
        assertEquals(mapOf("X-Test" to "v:1"), decoded)
    }

    @Test
    fun `toLines renders name colon value per line`() {
        val lines = SubscriptionHeaders.toLines(
            linkedMapOf("User-Agent" to "X/1", "x-hwid" to "abc")
        )
        assertEquals("User-Agent: X/1\nx-hwid: abc", lines)
    }
}
