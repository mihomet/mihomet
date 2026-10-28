package org.mihomet.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.data.db.ProfileEntity
import org.mihomet.app.data.db.ProfileType
import org.mihomet.app.data.subs.SubscriptionRepository
import org.mihomet.app.data.subs.SubscriptionRepository.Companion.MIN_AUTO_UPDATE_HOURS

/**
 * Pure-function tests for the background subscription refresh scheduling
 * predicate ([SubscriptionRepository.isDueForRefresh]) — no Android or network
 * dependencies.
 */
class SubscriptionRefreshPolicyTest {

    private val now = 1_800_000_000_000L

    private fun profile(
        type: ProfileType = ProfileType.REMOTE,
        url: String = "https://panel.example.com/sub?token=x",
        updateIntervalHours: Int = MIN_AUTO_UPDATE_HOURS,
        lastUpdated: Long = now
    ) = ProfileEntity(
        name = "Test profile",
        type = type,
        url = url,
        filePath = "/data/profiles/profile_1.yaml",
        lastUpdated = lastUpdated,
        updateIntervalHours = updateIntervalHours
    )

    private fun hoursAgo(hours: Long): Long = now - hours * 3_600_000L

    @Test
    fun `local profile is never due for refresh`() {
        val local = profile(type = ProfileType.LOCAL, lastUpdated = hoursAgo(1000))
        assertFalse(SubscriptionRepository.isDueForRefresh(local, now))
    }

    @Test
    fun `remote profile without url is never due`() {
        val noUrl = profile(url = "", lastUpdated = hoursAgo(1000))
        assertFalse(SubscriptionRepository.isDueForRefresh(noUrl, now))
    }

    @Test
    fun `zero interval disables background refresh`() {
        val never = profile(updateIntervalHours = 0, lastUpdated = hoursAgo(1_000_000))
        assertFalse(SubscriptionRepository.isDueForRefresh(never, now))
    }

    @Test
    fun `interval below the 6-hour minimum is never due`() {
        val tooFrequent = profile(updateIntervalHours = MIN_AUTO_UPDATE_HOURS - 1, lastUpdated = hoursAgo(1000))
        assertFalse(SubscriptionRepository.isDueForRefresh(tooFrequent, now))
    }

    @Test
    fun `exactly the minimum interval qualifies as eligible`() {
        val eligible = profile(updateIntervalHours = MIN_AUTO_UPDATE_HOURS, lastUpdated = hoursAgo(1000))
        assertTrue(SubscriptionRepository.isDueForRefresh(eligible, now))
    }

    @Test
    fun `not due before the interval has elapsed`() {
        val p = profile(updateIntervalHours = 6, lastUpdated = hoursAgo(5))
        assertFalse(SubscriptionRepository.isDueForRefresh(p, now))
    }

    @Test
    fun `due exactly when the interval has elapsed (boundary inclusive)`() {
        val p = profile(updateIntervalHours = 6, lastUpdated = hoursAgo(6))
        assertTrue(SubscriptionRepository.isDueForRefresh(p, now))
    }

    @Test
    fun `due after the interval has elapsed`() {
        val p = profile(updateIntervalHours = 6, lastUpdated = hoursAgo(7))
        assertTrue(SubscriptionRepository.isDueForRefresh(p, now))
    }

    @Test
    fun `long interval not due after a short wait`() {
        val p = profile(updateIntervalHours = 72, lastUpdated = hoursAgo(24))
        assertFalse(SubscriptionRepository.isDueForRefresh(p, now))
    }

    @Test
    fun `long interval due once fully elapsed`() {
        val p = profile(updateIntervalHours = 72, lastUpdated = hoursAgo(72))
        assertTrue(SubscriptionRepository.isDueForRefresh(p, now))
    }

    @Test
    fun `lastUpdated in the future is never due`() {
        val p = profile(updateIntervalHours = 6, lastUpdated = now + 3_600_000L)
        assertFalse(SubscriptionRepository.isDueForRefresh(p, now))
    }
}
