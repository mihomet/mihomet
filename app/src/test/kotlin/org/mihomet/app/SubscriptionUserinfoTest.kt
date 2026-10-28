package org.mihomet.app

import org.junit.Assert.*
import org.junit.Test
import org.mihomet.app.ui.screens.profiles.SubscriptionUserinfoParser
import org.mihomet.app.ui.screens.profiles.formatTrafficBytes
import java.util.Locale

class SubscriptionUserinfoTest {

    @Test
    fun `parses full standard header`() {
        val info = SubscriptionUserinfoParser.parse(
            "upload=45916139 download=617478191 total=107374182400 expire=1700000000"
        )
        assertNotNull(info)
        info!!
        assertEquals(45916139L, info.uploadUsed)
        assertEquals(617478191L, info.downloadUsed)
        assertEquals(107374182400L, info.trafficTotal)
        // expire is Unix seconds -> stored as epoch millis
        assertEquals(1700000000L * 1000L, info.expireAt)
        assertEquals(45916139L + 617478191L, info.trafficUsed)
    }

    @Test
    fun `parses header with missing fields`() {
        val info = SubscriptionUserinfoParser.parse("total=107374182400")
        assertNotNull(info)
        info!!
        assertEquals(0L, info.uploadUsed)
        assertEquals(0L, info.downloadUsed)
        assertEquals(107374182400L, info.trafficTotal)
        assertEquals(0L, info.expireAt)
    }

    @Test
    fun `parses header case-insensitively and with extra separators`() {
        val info = SubscriptionUserinfoParser.parse("Upload=1, DOWNLOAD=2;Total=3 , expire=1700000000")
        assertNotNull(info)
        info!!
        assertEquals(1L, info.uploadUsed)
        assertEquals(2L, info.downloadUsed)
        assertEquals(3L, info.trafficTotal)
        assertEquals(1700000000L * 1000L, info.expireAt)
    }

    @Test
    fun `header with no usable data returns null`() {
        assertNull(SubscriptionUserinfoParser.parse(null))
        assertNull(SubscriptionUserinfoParser.parse(""))
        assertNull(SubscriptionUserinfoParser.parse("   "))
        assertNull(SubscriptionUserinfoParser.parse("garbage without pairs"))
        assertNull(SubscriptionUserinfoParser.parse("foo=bar baz=qux"))
    }

    @Test
    fun `negative and unparseable values are tolerated`() {
        val info = SubscriptionUserinfoParser.parse("upload=abc download=-5 total=100 expire=0")
        assertNotNull(info)
        info!!
        assertEquals(0L, info.uploadUsed)
        assertEquals(0L, info.downloadUsed)
        assertEquals(100L, info.trafficTotal)
        assertEquals(0L, info.expireAt)
    }

    @Test
    fun `expire already in millis is kept as is`() {
        val info = SubscriptionUserinfoParser.parse("expire=1700000000000")
        assertNotNull(info)
        assertEquals(1700000000000L, info!!.expireAt)
    }

    @Test
    fun `traffic bytes formatting`() {
        val us = Locale.US
        assertEquals("0\u00A0B", formatTrafficBytes(0L, us))
        assertEquals("42\u00A0B", formatTrafficBytes(42L, us))
        assertEquals("1.0\u00A0KB", formatTrafficBytes(1024L, us))
        assertEquals("1.5\u00A0MB", formatTrafficBytes((1024L * 1024L * 3L / 2L), us))
        assertEquals("3.2\u00A0GB", formatTrafficBytes((3.2 * 1024 * 1024 * 1024).toLong(), us))
        assertEquals("1.00\u00A0TB", formatTrafficBytes(1024L * 1024L * 1024L * 1024L, us))
        assertEquals("0\u00A0B", formatTrafficBytes(-5L, us))
    }
}
