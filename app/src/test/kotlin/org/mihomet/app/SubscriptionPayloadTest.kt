package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.core.AppErrorCode
import org.mihomet.app.core.AppException
import org.mihomet.app.data.subs.SubscriptionRepository
import org.mihomet.app.core.config.ConversionOutcome
import org.mihomet.app.data.db.ProfileEntity
import org.mihomet.app.data.db.ProfileType
import org.mihomet.app.data.subs.ProfileRefreshResult
import org.mihomet.app.data.subs.SubscriptionDownload

class SubscriptionPayloadTest {

    @Test
    fun `download and refresh results retain their source payload`() {
        val outcome = ConversionOutcome("proxies:\n  - name: node")
        val source = "proxies:\n  - name: node"
        val download = SubscriptionDownload(outcome = outcome, sourcePayload = source)
        val profile = ProfileEntity(
            name = "test",
            type = ProfileType.LOCAL,
            filePath = "/tmp/test.yaml"
        )
        val refresh = ProfileRefreshResult(profile = profile, outcome = outcome, sourcePayload = source)

        assertEquals(source, download.sourcePayload)
        assertEquals(source, refresh.sourcePayload)
        assertFalse(download.toString().contains(source))
        assertFalse(download.toString().contains(outcome.yaml))
        assertFalse(refresh.toString().contains(source))
    }

    @Test
    fun `download cap and scheduling helpers remain callable from unit tests`() {
        val profile = ProfileEntity(
            name = "remote",
            type = ProfileType.REMOTE,
            url = "enc1:url",
            filePath = "/tmp/remote.yaml",
            lastUpdated = 0,
            updateIntervalHours = 1
        )

        assertTrue(SubscriptionRepository.isDueForRefresh(profile, 3_600_000L))
    }

    @Test
    fun `typed error retains a source payload without putting it in its message`() {
        val payload = "vless://secret@example.com:443"
        val error = AppException(AppErrorCode.IMPORT_NO_COMPATIBLE_CONFIG, sourcePayload = payload)

        assertEquals(payload, error.sourcePayload)
        assertEquals(-1, error.message?.indexOf("secret"))
    }
}
