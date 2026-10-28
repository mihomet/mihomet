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

    @Test
    fun `test clashmeta and flclash install-config extract url`() {
        assertEquals(
            "https://panel.example.com/sub/cm",
            DeepLinkParser.parse("clashmeta://install-config?url=https%3A%2F%2Fpanel.example.com%2Fsub%2Fcm")
        )
        assertEquals(
            "https://panel.example.com/sub/fc",
            DeepLinkParser.parse("flclash://install-config?url=https%3A%2F%2Fpanel.example.com%2Fsub%2Ffc")
        )
    }

    @Test
    fun `test incy add decodes percent-encoded url from path`() {
        assertEquals(
            "https://panel.example.com/sub/token",
            DeepLinkParser.parse("incy://add/https%3A%2F%2Fpanel.example.com%2Fsub%2Ftoken")
        )
    }

    @Test
    fun `test incy import url variant returns url as is`() {
        assertEquals(
            "https://sub.example.com/s/token",
            DeepLinkParser.parse("incy://import/https://sub.example.com/s/token")
        )
        assertEquals(
            "https://sub.example.com/s/token",
            DeepLinkParser.parse("incy://import/https%3A%2F%2Fsub.example.com%2Fs%2Ftoken")
        )
    }

    @Test
    fun `test incy import base64 variant decodes content`() {
        val payload = java.util.Base64.getEncoder()
            .encodeToString("vless://uuid@i.example.com:443#Incy".toByteArray())
        assertEquals(
            "vless://uuid@i.example.com:443#Incy",
            DeepLinkParser.parse("incy://import/$payload")
        )
    }

    @Test
    fun `test happ routing add and onadd decode base64 json`() {
        val routingJson =
            """{"routing":{"domainStrategy":"IPIfNonMatch","rules":[{"outboundTag":"proxy","domain":["geosite:example"]}]}}"""
        val payload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(routingJson.toByteArray())
        assertEquals(routingJson, DeepLinkParser.parse("happ://routing/add/$payload"))
        assertEquals(routingJson, DeepLinkParser.parse("happ://routing/onadd/$payload"))
    }

    @Test
    fun `test crypto schemes pass through verbatim`() {
        assertEquals("happ://crypt4/AbCdEf123", DeepLinkParser.parse("happ://crypt4/AbCdEf123"))
        assertEquals("happ://crypt5/AbCdEf456", DeepLinkParser.parse("happ://crypt5/AbCdEf456"))
        assertEquals("incy://crypt1/AbCdEf789", DeepLinkParser.parse("incy://crypt1/AbCdEf789"))
    }
}
