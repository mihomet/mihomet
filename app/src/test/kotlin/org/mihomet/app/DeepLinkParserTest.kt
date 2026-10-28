package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mihomet.app.core.DeepLinkParser

class DeepLinkParserTest {

    @Test
    fun `test clash install-config extracts url parameter`() {
        val link = "clash://install-config?url=https%3A%2F%2Fpanel.example.com%2Fsub%2Ftoken"
        assertEquals("https://panel.example.com/sub/token", DeepLinkParser.parse(link))
    }

    @Test
    fun `test v2rayng and singbox import links extract url`() {
        assertEquals(
            "https://sub.example.com/s/token",
            DeepLinkParser.parse("v2rayng://install-sub?url=https%3A%2F%2Fsub.example.com%2Fs%2Ftoken")
        )
        assertEquals(
            "https://sub.example.com/s/token2",
            DeepLinkParser.parse("sing-box://import-remote?url=https%3A%2F%2Fsub.example.com%2Fs%2Ftoken2")
        )
    }

    @Test
    fun `test happ add decodes base64 payload`() {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("vless://uuid@h.example.com:443#Node".toByteArray())
        assertEquals(
            "vless://uuid@h.example.com:443#Node",
            DeepLinkParser.parse("happ://add/$payload")
        )
    }

    @Test
    fun `test happ routing and unknown schemes pass through verbatim`() {
        val routing = "happ://routing/crypto%3A%2F%2Fxyz"
        assertEquals(routing, DeepLinkParser.parse(routing))
        assertEquals("incy://weird-payload", DeepLinkParser.parse("incy://weird-payload"))
    }

    @Test
    fun `test plain https url and null safety`() {
        assertEquals("https://example.com/sub", DeepLinkParser.parse("https://example.com/sub"))
        assertNull(DeepLinkParser.parse(null))
        assertNull(DeepLinkParser.parse("   "))
    }
}
