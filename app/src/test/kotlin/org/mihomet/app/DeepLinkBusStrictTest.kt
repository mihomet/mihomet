package org.mihomet.app

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mihomet.app.core.DeepLinkBus

/**
 * Strict tests for [DeepLinkBus] — the singleton Channel(8) bridge that carries
 * VIEW/SEND deep-link payloads from MainActivity to the Profiles screen.
 *
 * Regression guard: the bus must stay a Channel, never a StateFlow/MutableStateFlow —
 * a conflating holder would silently drop all but the last of several rapid links.
 * Channel.trySend buffers up to capacity 8 with no collector attached, so
 * post-then-collect inside one test cannot lose elements.
 */
class DeepLinkBusStrictTest {

    @Before
    fun drainStalePayloads() = runTest {
        // DeepLinkBus is a process-wide singleton: discard anything another test left
        // buffered so every test below only sees the payloads it posted itself.
        // In runTest the empty-receive timeout fires on virtual time, so this drains
        // instantly and terminates as soon as the channel is empty.
        while (withTimeoutOrNull(1) { DeepLinkBus.flow.first() } != null) {
            // keep draining
        }
    }

    @Test
    fun `test two rapid posts are both delivered in order without conflation`() = runTest {
        // Two successive links before any collector subscribes — a StateFlow would
        // keep only the last value; the Channel must deliver both, FIFO.
        DeepLinkBus.post("happ://add/first-payload")
        DeepLinkBus.post("happ://add/second-payload")

        val received = withTimeout(5_000) {
            DeepLinkBus.flow.take(2).toList()
        }

        assertEquals(
            listOf("happ://add/first-payload", "happ://add/second-payload"),
            received
        )
    }

    @Test
    fun `test post null is ignored and enqueues nothing`() = runTest {
        DeepLinkBus.post(null)
        DeepLinkBus.post(null)

        val leaked = withTimeoutOrNull(100) { DeepLinkBus.flow.first() }

        assertNull("post(null) must not enqueue a payload, but received: $leaked", leaked)
    }

    @Test
    fun `test consume does not throw and bus stays usable`() = runTest {
        // consume() is a no-op today (receiveAsFlow drains on collect) — the contract
        // is only that calling it is safe at any time and breaks nothing.
        DeepLinkBus.consume()

        DeepLinkBus.post("clash://install-config?url=https%3A%2F%2Fafter-consume.example")
        val received = withTimeout(5_000) { DeepLinkBus.flow.first() }

        assertEquals("clash://install-config?url=https%3A%2F%2Fafter-consume.example", received)
    }
}
