package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.data.preferences.AppSettings
import org.mihomet.app.data.preferences.SettingsRepository
import org.mihomet.app.data.subs.SubscriptionIdentitySettings
import org.mihomet.app.data.subs.SubscriptionRepository
import org.mihomet.app.data.subs.SubscriptionRequestPlan

/**
 * Header merge precedence for subscription downloads
 * ([SubscriptionRequestPlan.build], v2rayNG/Happ conventions):
 * per-subscription custom headers > global Settings → Subscriptions identity >
 * built-in defaults.
 */
class SubscriptionRequestPlanTest {

    private val deviceHwid = "device-hwid-0123456789abcdef"

    private fun identity(
        ua: String = "",
        sendHwid: Boolean = true,
        customHwid: String = ""
    ) = SubscriptionIdentitySettings(ua, sendHwid, customHwid)

    // ------------------------------------------------------------ User-Agent precedence

    @Test
    fun `profile UA header replaces the whole chain`() {
        val plan = SubscriptionRequestPlan.build(
            profileHeaders = mapOf("User-Agent" to "MyAgent/9.9"),
            identity = identity(ua = "GlobalAgent/1.0"),
            deviceHwid = deviceHwid
        )

        assertEquals(listOf("MyAgent/9.9"), plan.userAgentsToTry)
    }

    @Test
    fun `profile UA header wins case-insensitively`() {
        val plan = SubscriptionRequestPlan.build(
            profileHeaders = mapOf("user-agent" to "MyAgent/9.9"),
            identity = identity(ua = "GlobalAgent/1.0"),
            deviceHwid = deviceHwid
        )

        assertEquals(listOf("MyAgent/9.9"), plan.userAgentsToTry)
    }

    @Test
    fun `global UA setting wins over the default chain`() {
        val plan = SubscriptionRequestPlan.build(
            profileHeaders = emptyMap(),
            identity = identity(ua = "GlobalAgent/1.0"),
            deviceHwid = deviceHwid
        )

        assertEquals(listOf("GlobalAgent/1.0"), plan.userAgentsToTry)
    }

    @Test
    fun `blank global UA falls back to the honest default chain`() {
        val plan = SubscriptionRequestPlan.build(
            profileHeaders = emptyMap(),
            identity = identity(ua = "   "),
            deviceHwid = deviceHwid
        )

        assertEquals(SubscriptionRepository.UA_FALLBACK_CHAIN, plan.userAgentsToTry)
        assertEquals(
            "the honest Mihomet UA must stay first in the chain",
            SubscriptionRepository.APP_USER_AGENT,
            plan.userAgentsToTry.first()
        )
    }

    @Test
    fun `default chain is used when neither profile nor global UA set`() {
        val plan = SubscriptionRequestPlan.build(
            profileHeaders = mapOf("X-Other" to "v"),
            identity = identity(),
            deviceHwid = deviceHwid
        )

        assertEquals(SubscriptionRepository.UA_FALLBACK_CHAIN, plan.userAgentsToTry)
    }

    // ------------------------------------------------------------ x-hwid precedence

    @Test
    fun `profile x-hwid wins over global custom hwid`() {
        val plan = SubscriptionRequestPlan.build(
            profileHeaders = mapOf("x-hwid" to "profile-hwid"),
            identity = identity(customHwid = "global-hwid"),
            deviceHwid = deviceHwid
        )

        assertEquals("profile-hwid", plan.hwidHeader)
    }

    @Test
    fun `global custom hwid wins over the device id`() {
        val plan = SubscriptionRequestPlan.build(
            profileHeaders = emptyMap(),
            identity = identity(customHwid = "global-hwid"),
            deviceHwid = deviceHwid
        )

        assertEquals("global-hwid", plan.hwidHeader)
    }

    @Test
    fun `sendHwid off removes the header entirely`() {
        val plan = SubscriptionRequestPlan.build(
            profileHeaders = emptyMap(),
            identity = identity(sendHwid = false, customHwid = "global-hwid"),
            deviceHwid = deviceHwid
        )

        assertNull("toggle off must suppress x-hwid even with a custom value", plan.hwidHeader)
    }

    @Test
    fun `sendHwid off still respects a profile-provided x-hwid`() {
        val plan = SubscriptionRequestPlan.build(
            profileHeaders = mapOf("X-HWID" to "profile-hwid"),
            identity = identity(sendHwid = false),
            deviceHwid = deviceHwid
        )

        assertEquals("profile-hwid", plan.hwidHeader)
    }

    @Test
    fun `device id is the final hwid fallback`() {
        val plan = SubscriptionRequestPlan.build(
            profileHeaders = emptyMap(),
            identity = identity(sendHwid = true, customHwid = ""),
            deviceHwid = deviceHwid
        )

        assertEquals(deviceHwid, plan.hwidHeader)
    }

    // ------------------------------------------------------------ extra headers

    @Test
    fun `UA and hwid are excluded from extra headers`() {
        val plan = SubscriptionRequestPlan.build(
            profileHeaders = mapOf(
                "User-Agent" to "A",
                "x-hwid" to "h",
                "Authorization" to "Bearer t",
                "X-Custom" to "v"
            ),
            identity = identity(),
            deviceHwid = deviceHwid
        )

        assertEquals(mapOf("Authorization" to "Bearer t", "X-Custom" to "v"), plan.extraHeaders)
    }

    // ------------------------------------------------------------ settings mapping

    @Test
    fun `identity maps from AppSettings defaults`() {
        val identity = SubscriptionIdentitySettings.of(AppSettings())

        assertEquals("", identity.globalUserAgent)
        assertTrue("sendHwid defaults to on", identity.sendHwid)
        assertEquals("", identity.customHwid)
    }

    @Test
    fun `identity maps custom AppSettings values`() {
        val identity = SubscriptionIdentitySettings.of(
            AppSettings(
                subscriptionUserAgent = "UA/1",
                sendHwid = false,
                customHwid = "hw"
            )
        )

        assertEquals("UA/1", identity.globalUserAgent)
        assertEquals(false, identity.sendHwid)
        assertEquals("hw", identity.customHwid)
    }

    // ------------------------------------------------------------ settings sanitizers

    @Test
    fun `sanitizeUserAgent strips control characters and caps length`() {
        assertEquals(
            "CRLF must degrade to a single-line value (no header injection)",
            "ClashMeta/1.18.0 Host: evil",
            SettingsRepository.sanitizeUserAgent("ClashMeta/1.18.0\r\nHost: evil")
        )
        assertEquals(
            "a b",
            SettingsRepository.sanitizeUserAgent("a\u0000\u0007b")
        )
        assertEquals(
            "u".repeat(SettingsRepository.MAX_USER_AGENT_LENGTH),
            SettingsRepository.sanitizeUserAgent("u".repeat(10_000))
        )
    }

    @Test
    fun `sanitizeHwid strips control characters and caps length`() {
        assertEquals("abc123", SettingsRepository.sanitizeHwid(" abc123 \n"))
        assertEquals(
            "h".repeat(SettingsRepository.MAX_HWID_LENGTH),
            SettingsRepository.sanitizeHwid("h".repeat(999))
        )
    }
}
