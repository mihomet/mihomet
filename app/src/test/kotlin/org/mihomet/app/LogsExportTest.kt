package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.data.api.LogEntry
import org.mihomet.app.ui.screens.logs.buildLogExportText
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Strict tests for the log export format produced by [buildLogExportText]
 * (used by LogsViewModel.buildLogExport / exportLogs): header with app version,
 * export timestamp and entry count, followed by one `[LEVEL] payload` line per
 * buffered entry, in order.
 */
class LogsExportTest {

    private fun dateOf(timestamp: String) =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(timestamp)!!

    @Test
    fun `export writes header then one uppercase level line per entry in order`() {
        val logs = listOf(
            LogEntry(type = "info", payload = "Start initial routine in http://127.0.0.1:9090"),
            LogEntry(type = "warning", payload = "dns resolve failed"),
            LogEntry(type = "error", payload = "dial tcp 1.2.3.4:443: connect: connection refused"),
            LogEntry(type = "debug", payload = "match RuleSet(proxy) using PROXY")
        )

        val text = buildLogExportText(
            logs = logs,
            now = dateOf("2026-10-30 12:34:56"),
            appVersion = "1.1.0"
        )

        val lines = text.lines()
        // Header: app + version, export timestamp, entry count
        assertTrue(lines[0].startsWith("Mihomet"))
        assertTrue("version must be in the header, got: ${lines[0]}", lines[0].contains("v1.1.0"))
        assertEquals("2026-10-30 12:34:56", lines[1])
        assertEquals("Entries: 4", lines[2])
        // Entries: [LEVEL] payload, order preserved, level uppercased
        assertEquals("[INFO] Start initial routine in http://127.0.0.1:9090", lines[3])
        assertEquals("[WARNING] dns resolve failed", lines[4])
        assertEquals("[ERROR] dial tcp 1.2.3.4:443: connect: connection refused", lines[5])
        assertEquals("[DEBUG] match RuleSet(proxy) using PROXY", lines[6])
        // Trailing newline terminates the last entry
        assertTrue(text.endsWith("\n"))
    }

    @Test
    fun `empty log keeps header only`() {
        val text = buildLogExportText(
            logs = emptyList(),
            now = dateOf("2026-10-30 00:00:00"),
            appVersion = "1.1.0"
        )

        val lines = text.lines()
        assertTrue(lines[0].startsWith("Mihomet"))
        assertTrue(lines[0].contains("v1.1.0"))
        assertEquals("2026-10-30 00:00:00", lines[1])
        assertEquals("Entries: 0", lines[2])
        assertEquals(4, lines.size) // header (3 lines) + trailing empty line
    }

    @Test
    fun `missing entry type falls back to uppercase INFO and multiline payload is preserved`() {
        val text = buildLogExportText(
            logs = listOf(
                LogEntry(payload = "start initial routine"),
                LogEntry(type = "info", payload = "line one\nline two")
            ),
            now = dateOf("2026-10-30 10:00:00"),
            appVersion = "9.9.9"
        )

        assertTrue(text.contains("[INFO] start initial routine"))
        assertTrue(text.contains("[INFO] line one\nline two"))
        assertTrue(text.contains("Entries: 2"))
    }
}
