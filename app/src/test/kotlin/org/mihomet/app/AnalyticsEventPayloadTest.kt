package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.data.analytics.AnalyticsConsentGate
import org.mihomet.app.data.analytics.AnalyticsEventPayload
import org.mihomet.app.data.analytics.AnalyticsInitializationGate
import org.mihomet.app.data.analytics.ProcessEventGate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AnalyticsEventPayloadTest {

    @Test
    fun `conversion properties contain only bounded aggregate fields`() {
        val properties = AnalyticsEventPayload.conversionProperties(
            format = "xray",
            success = false,
            warningCount = 999
        )

        assertEquals(
            setOf("format", "success", "warning_count"),
            properties.keys
        )
        assertEquals("xray", properties["format"])
        assertEquals("failure", properties["success"])
        assertEquals(100, properties["warning_count"])
    }

    @Test
    fun `unknown or user shaped formats are normalized`() {
        assertEquals("sing-box", AnalyticsEventPayload.normalizeFormat(" SING-BOX "))
        assertEquals("unknown", AnalyticsEventPayload.normalizeFormat("private-source"))
        assertEquals("unknown", AnalyticsEventPayload.normalizeFormat(null))
    }

    @Test
    fun `only supported public Aptabase app keys are accepted`() {
        assertTrue(AnalyticsEventPayload.isValidAppKey("A-US-0123456789abcdef"))
        assertTrue(AnalyticsEventPayload.isValidAppKey(" A-EU-0123456789abcdef "))
        assertTrue(AnalyticsEventPayload.isValidAppKey("A-US-valid_suffix9"))
        assertFalse(AnalyticsEventPayload.isValidAppKey(""))
        assertFalse(AnalyticsEventPayload.isValidAppKey("A-SH-0123456789abcdef"))
        assertFalse(AnalyticsEventPayload.isValidAppKey("private-key"))
        assertFalse(AnalyticsEventPayload.isValidAppKey("A-US-"))
        assertFalse(AnalyticsEventPayload.isValidAppKey("A-US-a-b"))
        assertFalse(AnalyticsEventPayload.isValidAppKey("A-EU-a-b"))
        assertFalse(AnalyticsEventPayload.isValidAppKey("A-US--suffix"))
        assertFalse(AnalyticsEventPayload.isValidAppKey("A-EU-suffix-"))
    }

    @Test
    fun `failed SDK initialization retries and does not consume an event slot`() {
        val initialization = AnalyticsInitializationGate()
        val events = ProcessEventGate()
        var attempts = 0

        val firstReady = initialization.initializeIfNeeded {
            attempts++
            error("synthetic transient initialization failure")
        }
        if (firstReady) events.claimConversion()
        assertFalse(firstReady)

        val secondReady = initialization.initializeIfNeeded { attempts++ }
        assertTrue(secondReady)
        assertTrue(events.claimConversion())
        assertTrue(initialization.initializeIfNeeded { attempts++ })
        assertEquals(2, attempts)
    }

    @Test
    fun `events captured without consent stay rejected after opt in`() {
        val gate = AnalyticsConsentGate()
        val consent = gate.capture()
        var handoffs = 0

        assertNull(consent)
        assertTrue(gate.updateFromSettings(true))

        assertFalse(gate.handoff(consent) { handoffs++ })
        assertTrue(gate.handoff(gate.capture()) { handoffs++ })
        assertEquals(1, handoffs)
    }

    @Test
    fun `disabling invalidates delayed events across consent periods`() {
        val gate = AnalyticsConsentGate()
        var handoffs = 0
        assertTrue(gate.updateFromSettings(true))
        val oldConsent = gate.capture()

        assertTrue(gate.updateFromSettings(true))
        assertTrue(gate.handoff(oldConsent) { handoffs++ })
        gate.requestFromUser(false)
        assertFalse(gate.updateFromSettings(false))
        assertFalse(gate.handoff(oldConsent) { handoffs++ })
        gate.requestFromUser(true)
        assertTrue(gate.updateFromSettings(true))

        assertFalse(gate.handoff(oldConsent) { handoffs++ })
        assertTrue(gate.handoff(gate.capture()) { handoffs++ })
        assertEquals(2, handoffs)
    }

    @Test
    fun `a consent rejected before handoff does not consume an event slot`() {
        val consent = AnalyticsConsentGate()
        val events = ProcessEventGate()
        assertTrue(consent.updateFromSettings(true))
        val captured = consent.capture()

        consent.requestFromUser(false)
        assertFalse(consent.handoff(captured) { events.claimConversion() })
        assertTrue(events.claimConversion())
    }

    @Test
    fun `disable waits for SDK handoff and prevents subsequent handoffs`() {
        val gate = AnalyticsConsentGate()
        assertTrue(gate.updateFromSettings(true))
        val consent = gate.capture()
        val handoffStarted = CountDownLatch(1)
        val releaseHandoff = CountDownLatch(1)
        val disableRequested = CountDownLatch(1)
        val disabled = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        var handoffs = 0

        try {
            val handoff = executor.submit<Boolean> {
                gate.handoff(consent) {
                    handoffs++
                    handoffStarted.countDown()
                    assertTrue(releaseHandoff.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(handoffStarted.await(5, TimeUnit.SECONDS))
            val disabling = executor.submit {
                disableRequested.countDown()
                gate.requestFromUser(false)
                disabled.countDown()
            }
            assertTrue(disableRequested.await(5, TimeUnit.SECONDS))
            assertFalse(disabled.await(200, TimeUnit.MILLISECONDS))

            releaseHandoff.countDown()
            assertTrue(handoff.get(5, TimeUnit.SECONDS))
            disabling.get(5, TimeUnit.SECONDS)
            assertFalse(gate.handoff(consent) { handoffs++ })
            assertEquals(1, handoffs)
        } finally {
            releaseHandoff.countDown()
            executor.shutdownNow()
        }
    }


    @Test
    fun `stale settings values cannot override a pending user choice`() {
        val gate = AnalyticsConsentGate()

        gate.requestFromUser(false)
        assertFalse(gate.updateFromSettings(true))
        assertNull(gate.capture())
        assertFalse(gate.updateFromSettings(false))

        gate.requestFromUser(true)
        assertFalse(gate.updateFromSettings(false))
        assertNull(gate.capture())
        assertTrue(gate.updateFromSettings(true))
        assertTrue(gate.capture() != null)
    }

    @Test
    fun `app started can only be claimed once during a process`() {
        val gate = ProcessEventGate()

        assertTrue(gate.claimAppStarted())
        assertFalse(gate.claimAppStarted())
        assertFalse(gate.claimAppStarted())
    }

    @Test
    fun `conversion events have a finite per-process cap`() {
        val gate = ProcessEventGate()

        repeat(50) { assertTrue(gate.claimConversion()) }
        assertFalse(gate.claimConversion())
        assertTrue(gate.claimAppStarted())
    }
}
