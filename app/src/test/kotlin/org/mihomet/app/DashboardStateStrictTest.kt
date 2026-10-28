package org.mihomet.app

import org.junit.Assert.*
import org.junit.Test
import org.mihomet.app.data.api.TrafficSample
import org.mihomet.app.ui.screens.dashboard.DashboardUiState

class DashboardStateStrictTest {

    @Test
    fun `test initial state defaults`() {
        val state = DashboardUiState()
        assertFalse(state.isRunning)
        assertFalse(state.isConnecting)
        assertFalse(state.isDisconnecting)
        // Default profile name is resolved in the UI layer via stringResource(R.string.dashboard_default_profile_name)
        assertEquals("", state.activeProfileName)
        assertTrue(state.trafficHistory.isEmpty())
    }

    @Test
    fun `test connection transition lifecycle flags`() {
        // 1. Idle -> User taps Connect
        val connectingState = DashboardUiState(
            isRunning = false,
            isConnecting = true,
            isDisconnecting = false
        )
        assertTrue(connectingState.isConnecting)
        assertFalse(connectingState.isRunning)
        assertFalse(connectingState.isDisconnecting)

        // 2. Service successfully started -> Active
        val runningState = connectingState.copy(
            isRunning = true,
            isConnecting = false,
            isDisconnecting = false
        )
        assertTrue(runningState.isRunning)
        assertFalse(runningState.isConnecting)
        assertFalse(runningState.isDisconnecting)

        // 3. User taps Disconnect -> Disconnecting animation state
        val disconnectingState = runningState.copy(
            isRunning = true,
            isConnecting = false,
            isDisconnecting = true
        )
        assertTrue(disconnectingState.isDisconnecting)
        assertFalse(disconnectingState.isConnecting)

        // 4. Service stopped -> Idle
        val idleState = disconnectingState.copy(
            isRunning = false,
            isConnecting = false,
            isDisconnecting = false
        )
        assertFalse(idleState.isRunning)
        assertFalse(idleState.isConnecting)
        assertFalse(idleState.isDisconnecting)
    }

    @Test
    fun `test traffic history sliding buffer maintains maximum 30 samples`() {
        val buffer = mutableListOf<TrafficSample>()

        for (i in 1..45) {
            val sample = TrafficSample(up = i * 1024L, down = i * 2048L)
            buffer.add(sample)
            if (buffer.size > 30) {
                buffer.removeAt(0)
            }
        }

        assertEquals(30, buffer.size)
        // First element in buffer should be sample #16 (index 16..45 = 30 samples)
        assertEquals(16 * 1024L, buffer.first().up)
        // Last element in buffer should be sample #45
        assertEquals(45 * 1024L, buffer.last().up)
    }
}
