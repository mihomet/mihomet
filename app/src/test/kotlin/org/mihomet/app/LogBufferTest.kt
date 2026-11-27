package org.mihomet.app

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mihomet.app.data.api.AppLogBuffer
import org.mihomet.app.data.api.LogEntry
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LogBufferTest {
    @Before fun reset() = AppLogBuffer.clear()
    @After fun clear() = AppLogBuffer.clear()

    @Test fun `received and per-level counts continue after the retained buffer fills`() {
        repeat(1500) { index -> AppLogBuffer.add(LogEntry(if (index % 2 == 0) "INFO" else "error", "entry-$index")) }
        val snapshot = AppLogBuffer.snapshot.value
        assertEquals(1500L, snapshot.count("all"))
        assertEquals(750L, snapshot.count("info"))
        assertEquals(750L, snapshot.count("error"))
        assertEquals(0L, snapshot.count("debug"))
        assertEquals(1000, snapshot.entries.size)
        assertEquals("entry-500", snapshot.entries.first().payload)
        assertEquals("entry-1499", snapshot.entries.last().payload)
    }

    @Test fun `identical messages still advance the snapshot after capacity`() {
        repeat(1000) { AppLogBuffer.add(LogEntry(payload = "same")) }
        val before = AppLogBuffer.snapshot.value
        AppLogBuffer.add(LogEntry(payload = "same"))
        val after = AppLogBuffer.snapshot.value
        assertEquals(before.entries, after.entries)
        assertNotEquals(before, after)
        assertEquals(1001L, after.totalCount)
    }

    @Test fun `concurrent writers cannot lose counts or exceed capacity`() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val writers = (0 until 4).map {
                executor.submit { repeat(700) { AppLogBuffer.add(LogEntry(payload = "concurrent")) } }
            }
            writers.forEach { it.get(15, TimeUnit.SECONDS) }
            assertEquals(2800L, AppLogBuffer.snapshot.value.totalCount)
            assertEquals(1000, AppLogBuffer.snapshot.value.entries.size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun `clear resets entries and both counters atomically`() {
        AppLogBuffer.add(LogEntry(type = "warning"))
        AppLogBuffer.clear()
        assertEquals(0L, AppLogBuffer.snapshot.value.totalCount)
        assertTrue(AppLogBuffer.snapshot.value.entries.isEmpty())
        assertTrue(AppLogBuffer.snapshot.value.levelCounts.isEmpty())
    }
}
