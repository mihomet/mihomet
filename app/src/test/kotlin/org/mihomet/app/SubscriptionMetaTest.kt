package org.mihomet.app

import org.junit.Assert.*
import org.junit.Test
import org.mihomet.app.core.HwidProvider
import org.mihomet.app.ui.screens.profiles.SubscriptionMeta
import org.mihomet.app.ui.screens.profiles.SubscriptionMetaParser
import org.mihomet.app.ui.screens.profiles.SubscriptionUserinfoParser
import java.util.Base64

class SubscriptionMetaTest {

    @Test
    fun `plain profile-title is used as is`() {
        val meta = SubscriptionMetaParser.parse(mapOf("profile-title" to "My Provider"))
        assertEquals("My Provider", meta!!.profileTitle)
    }

    @Test
    fun `base64 prefixed title is decoded (Incy notation)`() {
        val encoded = Base64.getEncoder().encodeToString("Мой профиль".toByteArray(Charsets.UTF_8))
        val meta = SubscriptionMetaParser.parse(mapOf("profile-title" to "base64:$encoded"))
        assertEquals("Мой профиль", meta!!.profileTitle)
    }

    @Test
    fun `base64 title without padding is tolerated`() {
        val encoded = Base64.getEncoder().withoutPadding().encodeToString("Мой профиль".toByteArray(Charsets.UTF_8))
        assertFalse(encoded.endsWith("="))
        val meta = SubscriptionMetaParser.parse(mapOf("profile-title" to "base64:$encoded"))
        assertEquals("Мой профиль", meta!!.profileTitle)
    }

    @Test
    fun `url-safe base64 title is decoded`() {
        val encoded = Base64.getUrlEncoder().encodeToString("Мой профиль".toByteArray(Charsets.UTF_8))
        val meta = SubscriptionMetaParser.parse(mapOf("profile-title" to "base64:$encoded"))
        assertEquals("Мой профиль", meta!!.profileTitle)
    }

    @Test
    fun `undecodable base64 title falls back to raw payload`() {
        val meta = SubscriptionMetaParser.parse(mapOf("profile-title" to "base64:!!!not-base64!!!"))
        assertEquals("!!!not-base64!!!", meta!!.profileTitle)
    }

    @Test
    fun `base64 prefix with empty payload passes through`() {
        val meta = SubscriptionMetaParser.parse(mapOf("profile-title" to "base64:"))
        assertEquals("base64:", meta!!.profileTitle)
    }

    @Test
    fun `header names are matched case-insensitively`() {
        val meta = SubscriptionMetaParser.parse(
            mapOf(
                "PROFILE-TITLE" to "Case Panel",
                "New-URL" to "https://panel.example.com/moved",
                "PROFILE-UPDATE-INTERVAL" to "12"
            )
        )
        assertEquals("Case Panel", meta!!.profileTitle)
        assertEquals("https://panel.example.com/moved", meta.redirectUrl)
        assertEquals(12, meta.updateIntervalHours)
    }

    @Test
    fun `update interval hours are parsed`() {
        val meta = SubscriptionMetaParser.parse(mapOf("profile-update-interval" to "24"))
        assertEquals(24, meta!!.updateIntervalHours)
    }

    @Test
    fun `update interval garbage defaults to zero`() {
        val meta = SubscriptionMetaParser.parse(mapOf("profile-update-interval" to "soon"))
        assertEquals(0, meta!!.updateIntervalHours)
    }

    @Test
    fun `new-url becomes redirect target`() {
        val meta = SubscriptionMetaParser.parse(mapOf("New-Url" to "https://panel.example.com/moved/sub"))
        assertEquals("https://panel.example.com/moved/sub", meta!!.redirectUrl)
    }

    @Test
    fun `fallback-url is used when new-url is absent`() {
        val meta = SubscriptionMetaParser.parse(mapOf("Fallback-Url" to "https://panel.example.com/fallback"))
        assertEquals("https://panel.example.com/fallback", meta!!.redirectUrl)
    }

    @Test
    fun `new-url wins over fallback-url`() {
        val meta = SubscriptionMetaParser.parse(
            mapOf(
                "New-Url" to "https://panel.example.com/new",
                "Fallback-Url" to "https://panel.example.com/fallback"
            )
        )
        assertEquals("https://panel.example.com/new", meta!!.redirectUrl)
    }

    @Test
    fun `no redirect when neither relocation header is present`() {
        val meta = SubscriptionMetaParser.parse(mapOf("profile-title" to "Just a title"))
        assertNull(meta!!.redirectUrl)
    }

    @Test
    fun `non-http redirect targets are ignored`() {
        val meta = SubscriptionMetaParser.parse(mapOf("New-Url" to "javascript:alert(1)"))
        assertNull(meta!!.redirectUrl)
        val meta2 = SubscriptionMetaParser.parse(mapOf("New-Url" to "  "))
        assertNull(meta2!!.redirectUrl)
    }

    @Test
    fun `empty headers yield defaults`() {
        val meta = SubscriptionMetaParser.parse(emptyMap())
        assertEquals(SubscriptionMeta(), meta)
    }

    @Test
    fun `userinfo parsing semantics unchanged`() {
        val info = SubscriptionUserinfoParser.parse(
            "upload=45916139 download=617478191 total=107374182400 expire=1700000000"
        )
        assertNotNull(info)
        assertEquals(45916139L + 617478191L, info!!.trafficUsed)
        assertEquals(107374182400L, info.trafficTotal)
        assertEquals(1700000000L * 1000L, info.expireAt)
        assertNull(SubscriptionUserinfoParser.parse(null))
    }

    @Test
    fun `hwid is 32 lowercase hex characters and deterministic`() {
        val a = HwidProvider.compute("9774d56d682e549c", "Google", "Pixel 8")
        val b = HwidProvider.compute("9774d56d682e549c", "Google", "Pixel 8")
        assertEquals(a, b)
        assertEquals(32, a.length)
        assertTrue(a.matches(Regex("^[0-9a-f]{32}$")))
        // Happ requirement: alphanumeric, 10..64 characters
        assertTrue(a.length in 10..64)
        assertTrue(a.all { it.isLetterOrDigit() })
    }

    @Test
    fun `hwid differs for different device attributes`() {
        val base = HwidProvider.compute("9774d56d682e549c", "Google", "Pixel 8")
        assertNotEquals(base, HwidProvider.compute("deadbeef", "Google", "Pixel 8"))
        assertNotEquals(base, HwidProvider.compute("9774d56d682e549c", "Xiaomi", "Pixel 8"))
        assertNotEquals(base, HwidProvider.compute("9774d56d682e549c", "Google", "Pixel 9"))
    }

    @Test
    fun `hwid of empty input is still valid`() {
        val hwid = HwidProvider.compute()
        assertTrue(hwid.matches(Regex("^[0-9a-f]{32}$")))
    }
}
