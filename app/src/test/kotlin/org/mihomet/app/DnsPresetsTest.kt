package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.core.dns.DnsCategory
import org.mihomet.app.core.dns.DnsPresets

class DnsPresetsTest {

    @Test
    fun `test categories are assigned as expected`() {
        assertEquals(DnsCategory.PLAINTEXT_RU, DnsPresets.categoryOf("77.88.8.8"))
        assertEquals(DnsCategory.PLAINTEXT_RU, DnsPresets.categoryOf("77.88.8.1"))
        assertEquals(DnsCategory.ENCRYPTED_RU, DnsPresets.categoryOf("https://common.doh.dns.yandex.net/dns-query"))
        assertEquals(DnsCategory.ENCRYPTED_RU, DnsPresets.categoryOf("https://dns.comss.one/dns-query"))
        assertEquals(DnsCategory.GLOBAL, DnsPresets.categoryOf("https://dns.adguard-dns.com/dns-query"))
        assertEquals(DnsCategory.GLOBAL, DnsPresets.categoryOf("https://1.1.1.1/dns-query"))
        assertEquals(DnsCategory.GLOBAL, DnsPresets.categoryOf("https://cloudflare-dns.com/dns-query"))
        assertEquals(DnsCategory.GLOBAL, DnsPresets.categoryOf("https://dns.google/dns-query"))
        assertEquals(DnsCategory.GLOBAL, DnsPresets.categoryOf("https://dns.quad9.net/dns-query"))
    }

    @Test
    fun `test blank and unknown uris have no category`() {
        assertNull(DnsPresets.categoryOf(""))
        assertNull(DnsPresets.categoryOf("   "))
        assertNull(DnsPresets.categoryOf("https://my-own-dns.example.com/dns-query"))
    }

    @Test
    fun `test bootstrap is plain russian ips only`() {
        assertEquals(listOf("77.88.8.8", "77.88.8.1"), DnsPresets.BOOTSTRAP_RU_PLAIN)
        val blocked = listOf("1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4", "9.9.9.9", "114.114.114.114")
        assertTrue(
            "Bootstrap must never contain foreign plain DNS blocked by TSPU",
            DnsPresets.BOOTSTRAP_RU_PLAIN.none { it in blocked }
        )
    }

    @Test
    fun `test all presets have unique uris and global presets are encrypted`() {
        val uris = DnsPresets.ALL.map { it.uri }
        assertEquals("Preset URIs must be unique", uris.size, uris.distinct().size)
        for (preset in DnsPresets.ALL) {
            if (preset.category != DnsCategory.PLAINTEXT_RU) {
                assertTrue("Preset ${preset.id} must be an encrypted URL", preset.uri.startsWith("https://"))
            }
        }
    }

    @Test
    fun `test default nameserver is reachable plain russian resolver`() {
        assertEquals(listOf("77.88.8.8"), DnsPresets.DEFAULT_NAMESERVER)
    }
}
