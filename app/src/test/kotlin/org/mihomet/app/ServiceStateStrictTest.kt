package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mihomet.app.core.RunningService
import org.mihomet.app.core.ServiceState

/**
 * Strict tests for the global service state machine. JUnit may run tests in any
 * order and [ServiceState] is a process-wide singleton, so every test resets it.
 */
class ServiceStateStrictTest {

    @Before
    fun resetState() {
        ServiceState.setRunning(false)
    }

    @Test
    fun `initial state after reset is stopped with no service`() {
        assertEquals(RunningService.NONE, ServiceState.runningService.value)
        assertFalse(ServiceState.isRunning.value)
    }

    @Test
    fun `setRunning switches runningService and aggregates isRunning`() {
        ServiceState.setRunning(RunningService.VPN)
        assertEquals(RunningService.VPN, ServiceState.runningService.value)
        assertTrue(ServiceState.isRunning.value)

        // Direct transition VPN -> ROOT without an intermediate stop
        ServiceState.setRunning(RunningService.ROOT)
        assertEquals(RunningService.ROOT, ServiceState.runningService.value)
        assertTrue(ServiceState.isRunning.value)

        ServiceState.setRunning(RunningService.NONE)
        assertEquals(RunningService.NONE, ServiceState.runningService.value)
        assertFalse(ServiceState.isRunning.value)
    }

    @Test
    fun `stopService with a mismatched service is a no-op`() {
        ServiceState.setRunning(RunningService.ROOT)

        // ROOT is running; stopping VPN must not tear down the state
        ServiceState.stopService(RunningService.VPN)
        assertEquals(RunningService.ROOT, ServiceState.runningService.value)
        assertTrue(ServiceState.isRunning.value)

        // ...and vice versa
        ServiceState.setRunning(false)
        ServiceState.setRunning(RunningService.VPN)
        ServiceState.stopService(RunningService.ROOT)
        assertEquals(RunningService.VPN, ServiceState.runningService.value)
        assertTrue(ServiceState.isRunning.value)
    }

    @Test
    fun `stopService with the matching service resets to stopped`() {
        ServiceState.setRunning(RunningService.VPN)
        ServiceState.stopService(RunningService.VPN)
        assertEquals(RunningService.NONE, ServiceState.runningService.value)
        assertFalse(ServiceState.isRunning.value)

        ServiceState.setRunning(RunningService.ROOT)
        ServiceState.stopService(RunningService.ROOT)
        assertEquals(RunningService.NONE, ServiceState.runningService.value)
        assertFalse(ServiceState.isRunning.value)
    }

    @Test
    fun `stopService NONE forces a reset regardless of the running service`() {
        ServiceState.setRunning(RunningService.ROOT)
        ServiceState.stopService(RunningService.NONE)
        assertEquals(RunningService.NONE, ServiceState.runningService.value)
        assertFalse(ServiceState.isRunning.value)

        ServiceState.setRunning(RunningService.VPN)
        ServiceState.stopService(RunningService.NONE)
        assertEquals(RunningService.NONE, ServiceState.runningService.value)
        assertFalse(ServiceState.isRunning.value)
    }

    @Test
    fun `boolean setRunning false resets the service, true keeps it`() {
        ServiceState.setRunning(RunningService.VPN)
        ServiceState.setRunning(false)
        assertEquals(RunningService.NONE, ServiceState.runningService.value)
        assertFalse(ServiceState.isRunning.value)

        // setRunning(true) only flips the aggregate flag; the service enum is untouched
        ServiceState.setRunning(RunningService.VPN)
        ServiceState.setRunning(true)
        assertTrue(ServiceState.isRunning.value)
        assertEquals(RunningService.VPN, ServiceState.runningService.value)
    }

    @Test
    fun `state flows are state flows exposing the current value`() {
        // Downstream collectors must always see a value (StateFlow semantics),
        // even before any transition happened in this process.
        ServiceState.setRunning(RunningService.ROOT)
        assertEquals(RunningService.ROOT, ServiceState.runningService.value)
        assertEquals(true, ServiceState.isRunning.value)
    }
}
