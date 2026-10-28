package org.mihomet.app

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SubscriptionHeadersStrictTest {

    private fun normalizeHeadersJson(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "{}"
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed
        }
        val map = trimmed.lines()
            .map { it.trim() }
            .filter { it.contains(":") || it.contains("=") }
            .associate { line ->
                val delimiter = if (line.contains(":")) ":" else "="
                val parts = line.split(delimiter, limit = 2)
                parts[0].trim() to parts.getOrElse(1) { "" }.trim()
            }
        return Json.encodeToString(map)
    }

    private fun parseHeaders(headersJson: String): Map<String, String> {
        return runCatching {
            Json.decodeFromString<Map<String, String>>(headersJson)
        }.getOrElse {
            headersJson.lines()
                .map { it.trim() }
                .filter { it.contains(":") || it.contains("=") }
                .associate { line ->
                    val delimiter = if (line.contains(":")) ":" else "="
                    val parts = line.split(delimiter, limit = 2)
                    parts[0].trim() to parts.getOrElse(1) { "" }.trim()
                }
        }
    }

    @Test
    fun `test valid JSON input preserved and decoded`() {
        val input = """{"User-Agent": "Mihomo/1.19.33", "x-hwid": "hwid-12345"}"""
        val normalized = normalizeHeadersJson(input)
        val parsed = parseHeaders(normalized)

        assertEquals("Mihomo/1.19.33", parsed["User-Agent"])
        assertEquals("hwid-12345", parsed["x-hwid"])
    }

    @Test
    fun `test multiline HTTP format converts cleanly to JSON map`() {
        val input = """
            User-Agent: Happ/3.6.0 (Android; Xray)
            x-hwid: e3a89c20-7f5b-4d1e-8a90-3b4c5d6e7f81
            Authorization: Bearer secret_token_xyz
        """.trimIndent()

        val normalized = normalizeHeadersJson(input)
        val parsed = parseHeaders(normalized)

        assertEquals("Happ/3.6.0 (Android; Xray)", parsed["User-Agent"])
        assertEquals("e3a89c20-7f5b-4d1e-8a90-3b4c5d6e7f81", parsed["x-hwid"])
        assertEquals("Bearer secret_token_xyz", parsed["Authorization"])
    }

    @Test
    fun `test headers containing colons in value`() {
        val input = "x-url: https://example.com:8443/sub?token=123"
        val normalized = normalizeHeadersJson(input)
        val parsed = parseHeaders(normalized)

        assertEquals("https://example.com:8443/sub?token=123", parsed["x-url"])
    }

    @Test
    fun `test key equals value syntax`() {
        val input = """
            User-Agent=sing-box/1.10.0
            device=android_phone
        """.trimIndent()

        val normalized = normalizeHeadersJson(input)
        val parsed = parseHeaders(normalized)

        assertEquals("sing-box/1.10.0", parsed["User-Agent"])
        assertEquals("android_phone", parsed["device"])
    }

    @Test
    fun `test blank and malformed input handles safely`() {
        assertEquals("{}", normalizeHeadersJson(""))
        assertEquals("{}", normalizeHeadersJson("   \n\n  "))
        assertEquals("{}", normalizeHeadersJson("random text with no delimiter"))

        val parsed = parseHeaders("")
        assertTrue("Parsed empty should be empty map", parsed.isEmpty())
    }
}
