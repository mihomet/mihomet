package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mihomet.app.ui.screens.proxies.getNodeFlagEmoji

/**
 * Strict tests for [getNodeFlagEmoji] (top-level pure function in ProxiesScreen.kt).
 *
 * The implementation matches ISO codes only as standalone tokens:
 * `(^|[^A-Z])CODE([^A-Z]|$)` — a naive `contains("US")` used to mis-flag
 * "Australia" (A-U-S-T-R-A-L-I-A) as 🇺🇸. These tests pin that regression.
 */
class NodeFlagEmojiStrictTest {

    @Test
    fun `test australia with flag prefix maps to AU not US`() {
        // Regression: "AUSTRALIA" contains the substring "US" — the token regex
        // must reject it, and the AUSTRALIA keyword must win.
        assertEquals("🇦🇺", getNodeFlagEmoji("🇦🇺 Australia"))
    }

    @Test
    fun `test USA with flag prefix maps to US`() {
        // Note: the token regex does NOT match "US" inside "USA" (followed by a letter);
        // the match comes from the "USA" keyword.
        assertEquals("🇺🇸", getNodeFlagEmoji("🇺🇸 USA"))
    }

    @Test
    fun `test Amsterdam maps to NL`() {
        // "AMSTERDAM" has no standalone NL token and "dam" is not a keyword —
        // the match comes from the explicit AMSTERDAM keyword. Verified: no earlier
        // branch (RU/DE/US…) steals it, so the mapping is correct as-is.
        assertEquals("🇳🇱", getNodeFlagEmoji("Amsterdam"))
    }

    @Test
    fun `test Russia flag cyrillic name and latin variants map to RU`() {
        assertEquals("🇷🇺", getNodeFlagEmoji("🇷🇺 Россия"))
        assertEquals("🇷🇺", getNodeFlagEmoji("Russia-1"))
        assertEquals("🇷🇺", getNodeFlagEmoji("Moscow 24"))
    }

    @Test
    fun `test Germany via city and cyrillic country name`() {
        assertEquals("🇩🇪", getNodeFlagEmoji("Frankfurt 1"))
        assertEquals("🇩🇪", getNodeFlagEmoji("Германия"))
    }

    @Test
    fun `test Japan and Singapore by city and country name`() {
        assertEquals("🇯🇵", getNodeFlagEmoji("Tokyo"))
        assertEquals("🇸🇬", getNodeFlagEmoji("Singapore"))
    }

    @Test
    fun `test garbage timestamp string falls back to globe`() {
        // No 2-letter token and no keyword anywhere → 🌐
        assertEquals("🌐", getNodeFlagEmoji("16.53--2026.10.28.time:1653"))
    }

    @Test
    fun `test mixed LV token with hyphen and city maps to LV`() {
        // "LV" at the start bounded by "-" wins before the RIGA keyword branch is reached;
        // RIGA alone would also map to 🇱🇻, so the result is unambiguous.
        assertEquals("🇱🇻", getNodeFlagEmoji("LV-7 Riga"))
    }

    @Test
    fun `test empty string falls back to globe`() {
        assertEquals("🌐", getNodeFlagEmoji(""))
    }

    @Test
    fun `test cyrillic without any keyword falls back to globe`() {
        // Cyrillic letters are outside [A-Z], so no ISO token can match; no keyword hits either.
        assertEquals("🌐", getNodeFlagEmoji("Тестовый узел"))
    }

    @Test
    fun `test United States full name maps to US`() {
        assertEquals("🇺🇸", getNodeFlagEmoji("United States"))
    }
}
