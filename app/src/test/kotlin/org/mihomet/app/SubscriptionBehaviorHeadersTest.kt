package org.mihomet.app

import org.junit.Assert.*
import org.junit.Test
import org.mihomet.app.ui.screens.profiles.SubscriptionMeta
import org.mihomet.app.ui.screens.profiles.SubscriptionMetaParser

/**
 * Tests for the Happ/Incy behavior headers (the panel remotely configures the
 * client): `subscription-autoconnect`, `per-app-proxy-mode`, `per-app-proxy-list`
 * and `tun-mode` — parsed by [SubscriptionMetaParser] alongside the classic
 * profile-title / update-interval / relocation headers.
 */
class SubscriptionBehaviorHeadersTest {

    // region subscription-autoconnect

    @Test
    fun `autoconnect true is recognized`() {
        val meta = SubscriptionMetaParser.parse(mapOf("subscription-autoconnect" to "true"))
        assertTrue(meta!!.autoConnect)
    }

    @Test
    fun `autoconnect numeric 1 is recognized`() {
        val meta = SubscriptionMetaParser.parse(mapOf("subscription-autoconnect" to "1"))
        assertTrue(meta!!.autoConnect)
    }

    @Test
    fun `autoconnect is case-insensitive`() {
        assertTrue(SubscriptionMetaParser.parse(mapOf("subscription-autoconnect" to "TRUE"))!!.autoConnect)
        assertTrue(SubscriptionMetaParser.parse(mapOf("SUBSCRIPTION-AUTOCONNECT" to "True"))!!.autoConnect)
    }

    @Test
    fun `autoconnect false and garbage stay false`() {
        assertFalse(SubscriptionMetaParser.parse(mapOf("subscription-autoconnect" to "false"))!!.autoConnect)
        assertFalse(SubscriptionMetaParser.parse(mapOf("subscription-autoconnect" to "0"))!!.autoConnect)
        assertFalse(SubscriptionMetaParser.parse(mapOf("subscription-autoconnect" to "yes"))!!.autoConnect)
        assertFalse(SubscriptionMetaParser.parse(mapOf("subscription-autoconnect" to "  "))!!.autoConnect)
    }

    @Test
    fun `autoconnect defaults to false when header is absent`() {
        val meta = SubscriptionMetaParser.parse(mapOf("profile-title" to "No behavior"))
        assertFalse(meta!!.autoConnect)
    }

    // endregion

    // region per-app-proxy-mode

    @Test
    fun `per-app mode values are kept as sent`() {
        assertEquals("whitelist", SubscriptionMetaParser.parse(mapOf("per-app-proxy-mode" to "whitelist"))!!.perAppModeHint)
        assertEquals("blacklist", SubscriptionMetaParser.parse(mapOf("per-app-proxy-mode" to "blacklist"))!!.perAppModeHint)
        assertEquals("all", SubscriptionMetaParser.parse(mapOf("per-app-proxy-mode" to "all"))!!.perAppModeHint)
    }

    @Test
    fun `per-app mode is not lowercased by the parser`() {
        val meta = SubscriptionMetaParser.parse(mapOf("Per-App-Proxy-Mode" to "  Whitelist  "))
        assertEquals("Whitelist", meta!!.perAppModeHint)
    }

    @Test
    fun `per-app mode absent or blank stays null`() {
        assertNull(SubscriptionMetaParser.parse(mapOf("profile-title" to "x"))!!.perAppModeHint)
        assertNull(SubscriptionMetaParser.parse(mapOf("per-app-proxy-mode" to "   "))!!.perAppModeHint)
    }

    // endregion

    // region per-app-proxy-list

    @Test
    fun `per-app list parses a JSON array`() {
        val meta = SubscriptionMetaParser.parse(
            mapOf("per-app-proxy-list" to """["com.example.app","com.foo.bar"]""")
        )
        assertEquals(listOf("com.example.app", "com.foo.bar"), meta!!.perAppPackages)
    }

    @Test
    fun `per-app list parses a JSON array with whitespace and empty entries`() {
        val meta = SubscriptionMetaParser.parse(
            mapOf("per-app-proxy-list" to """[ "com.example.app" , "" , "com.foo.bar" ]""")
        )
        assertEquals(listOf("com.example.app", "com.foo.bar"), meta!!.perAppPackages)
    }

    @Test
    fun `per-app list parses a multiline value`() {
        val meta = SubscriptionMetaParser.parse(
            mapOf("per-app-proxy-list" to "com.example.app\ncom.foo.bar\n\ncom.baz.qux")
        )
        assertEquals(listOf("com.example.app", "com.foo.bar", "com.baz.qux"), meta!!.perAppPackages)
    }

    @Test
    fun `per-app list parses a comma-separated value`() {
        val meta = SubscriptionMetaParser.parse(
            mapOf("per-app-proxy-list" to "com.example.app, com.foo.bar")
        )
        assertEquals(listOf("com.example.app", "com.foo.bar"), meta!!.perAppPackages)
    }

    @Test
    fun `per-app list collapses duplicates`() {
        val meta = SubscriptionMetaParser.parse(
            mapOf("per-app-proxy-list" to "com.example.app\ncom.example.app")
        )
        assertEquals(listOf("com.example.app"), meta!!.perAppPackages)
    }

    @Test
    fun `per-app list falls back to line splitting on malformed JSON`() {
        val meta = SubscriptionMetaParser.parse(
            mapOf("per-app-proxy-list" to "[com.example.app, com.foo.bar]")
        )
        assertEquals(listOf("com.example.app", "com.foo.bar"), meta!!.perAppPackages)
    }

    @Test
    fun `per-app list absent empty or garbage yields empty`() {
        assertEquals(emptyList<String>(), SubscriptionMetaParser.parse(mapOf("profile-title" to "x"))!!.perAppPackages)
        assertEquals(emptyList<String>(), SubscriptionMetaParser.parse(mapOf("per-app-proxy-list" to "   \n  "))!!.perAppPackages)
    }

    // endregion

    // region tun-mode

    @Test
    fun `tun-mode explicit true and false are parsed`() {
        assertEquals(true, SubscriptionMetaParser.parse(mapOf("tun-mode" to "true"))!!.tunModeHint)
        assertEquals(false, SubscriptionMetaParser.parse(mapOf("tun-mode" to "false"))!!.tunModeHint)
    }

    @Test
    fun `tun-mode is case-insensitive on name and value`() {
        assertEquals(true, SubscriptionMetaParser.parse(mapOf("TUN-MODE" to "TRUE"))!!.tunModeHint)
        assertEquals(false, SubscriptionMetaParser.parse(mapOf("Tun-Mode" to "False"))!!.tunModeHint)
    }

    @Test
    fun `tun-mode garbage or absent stays null`() {
        assertNull(SubscriptionMetaParser.parse(mapOf("tun-mode" to "1"))!!.tunModeHint)
        assertNull(SubscriptionMetaParser.parse(mapOf("tun-mode" to "yes"))!!.tunModeHint)
        assertNull(SubscriptionMetaParser.parse(mapOf("tun-mode" to "  "))!!.tunModeHint)
        assertNull(SubscriptionMetaParser.parse(mapOf("profile-title" to "x"))!!.tunModeHint)
    }

    // endregion

    @Test
    fun `all behavior headers parse together with the classic meta`() {
        val meta = SubscriptionMetaParser.parse(
            mapOf(
                "profile-title" to "Panel",
                "profile-update-interval" to "12",
                "subscription-autoconnect" to "1",
                "per-app-proxy-mode" to "whitelist",
                "per-app-proxy-list" to """["com.a.b","com.c.d"]""",
                "tun-mode" to "true"
            )
        )
        assertEquals("Panel", meta!!.profileTitle)
        assertEquals(12, meta.updateIntervalHours)
        assertTrue(meta.autoConnect)
        assertEquals("whitelist", meta.perAppModeHint)
        assertEquals(listOf("com.a.b", "com.c.d"), meta.perAppPackages)
        assertEquals(true, meta.tunModeHint)
    }

    @Test
    fun `empty headers yield behavior defaults`() {
        assertEquals(
            SubscriptionMeta(autoConnect = false, perAppModeHint = null, perAppPackages = emptyList(), tunModeHint = null),
            SubscriptionMetaParser.parse(emptyMap())
        )
    }
}
