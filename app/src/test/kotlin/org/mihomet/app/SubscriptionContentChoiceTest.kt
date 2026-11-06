package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.data.subs.SubscriptionContentChoice
import org.mihomet.app.data.subs.SubscriptionContentChoice.ChannelQuality

/**
 * Strict coverage of the content-aware UA fallback decision (quality-tiered):
 * a RICH channel (Xray JSON with a balancer, or a Mihomo YAML with url-test
 * groups — the Happ tier) stops the walk; a select-only grouped response is
 * kept as best-so-far while the chain still has entries (live-panel study
 * 06.11: the Happ tier carries a balancer + thousands of routing rules that
 * the Mihomo tier lacks); a group-less response is the last-resort fallback.
 */
class SubscriptionContentChoiceTest {

    private val plain = """
        proxies:
        - {name: a, type: socks5, server: 127.0.0.1, port: 1080}
        - {name: b, type: socks5, server: 127.0.0.1, port: 1081}
    """.trimIndent()

    private val selectOnly = """
        proxies:
        - {name: a, type: socks5, server: 127.0.0.1, port: 1080}
        proxy-groups:
        - {name: PROXY, type: select, proxies: [a]}
    """.trimIndent()

    private val balancer = """
        proxies:
        - {name: a, type: socks5, server: 127.0.0.1, port: 1080}
        proxy-groups:
        - {name: AUTO, type: url-test, proxies: [a]}
        - {name: PROXY, type: select, proxies: [AUTO]}
    """.trimIndent()

    private val xrayJson = """
        {"outbounds":[{"protocol":"freedom"},{"protocol":"vless","settings":{"vnext":[{}]}},
         {"protocol":"vless","settings":{"vnext":[{}]}}],"routing":{"rules":[]}}
    """.trimIndent()

    private fun eval(
        has: Boolean = false,
        saw: ChannelQuality = ChannelQuality.PLAIN,
        encrypted: Boolean = false,
        text: String
    ) = SubscriptionContentChoice.evaluate(has, saw, encrypted, text)

    @Test
    fun `first plain response is accepted but the chain continues`() {
        val d = eval(text = plain)
        assertTrue(d.accept)
        assertFalse(d.stop)
        assertEquals(ChannelQuality.PLAIN, d.sawQuality)
    }

    @Test
    fun `select-only grouped response is kept but does not stop the walk`() {
        val d = eval(text = selectOnly)
        assertTrue(d.accept)
        assertFalse(d.stop)
        assertEquals(ChannelQuality.GROUPED, d.sawQuality)
    }

    @Test
    fun `rich balancer channel replaces stored select-only and stops`() {
        val first = eval(text = selectOnly)
        val second = eval(has = first.accept, saw = first.sawQuality, text = balancer)
        assertTrue(second.accept)
        assertTrue(second.stop)
        assertEquals(ChannelQuality.RICH, second.sawQuality)
    }

    @Test
    fun `xray json with multiple outbounds is rich and stops`() {
        val d = eval(text = xrayJson)
        assertTrue(d.accept)
        assertTrue(d.stop)
        assertEquals(ChannelQuality.RICH, d.sawQuality)
    }

    @Test
    fun `single-outbound json is not rich`() {
        val d = eval(text = """{"outbounds":[{"protocol":"freedom"}]}""")
        assertTrue(d.accept)
        assertFalse(d.stop)
        assertEquals(ChannelQuality.PLAIN, d.sawQuality)
    }

    @Test
    fun `first rich response stops the chain immediately`() {
        val d = eval(text = balancer)
        assertTrue(d.accept)
        assertTrue(d.stop)
        assertEquals(ChannelQuality.RICH, d.sawQuality)
    }

    @Test
    fun `a stored rich response is never replaced by a lower tier`() {
        val d = eval(has = true, saw = ChannelQuality.RICH, text = selectOnly)
        assertFalse(d.accept)
        assertFalse(d.stop)
        assertEquals(ChannelQuality.RICH, d.sawQuality)
    }

    @Test
    fun `a stored select-only response is not replaced by a plain one`() {
        val d = eval(has = true, saw = ChannelQuality.GROUPED, text = plain)
        assertFalse(d.accept)
        assertFalse(d.stop)
        assertEquals(ChannelQuality.GROUPED, d.sawQuality)
    }

    @Test
    fun `encrypted responses are accepted immediately as rich`() {
        val d = eval(encrypted = true, text = plain)
        assertTrue(d.accept)
        assertTrue(d.stop)
        assertEquals(ChannelQuality.RICH, d.sawQuality)
    }

    @Test
    fun `node-less grouped payload is treated as plain`() {
        val emptyGroups = """
            proxies: []
            proxy-groups:
            - {name: g, type: select, proxies: [DIRECT]}
        """.trimIndent()
        val d = eval(text = emptyGroups)
        assertTrue(d.accept)
        assertFalse(d.stop)
        assertEquals(ChannelQuality.PLAIN, d.sawQuality)
    }

    @Test
    fun `countTopLevelList counts nested yaml safely`() {
        assertEquals(2, SubscriptionContentChoice.countTopLevelList(balancer, "proxy-groups"))
        assertEquals(2, SubscriptionContentChoice.countTopLevelList(plain, "proxies"))
        assertEquals(0, SubscriptionContentChoice.countTopLevelList(plain, "proxy-groups"))
        assertEquals(0, SubscriptionContentChoice.countTopLevelList("not: [valid: yaml:", "proxies"))
    }

    @Test
    fun `full live-panel walk ends on the richest channel`() {
        // Remnawave 06.11: mihomet UA → base64 list, mihomo UA → select-only YAML,
        // Happ UA → Xray JSON with a balancer. The walk must finish on Happ's.
        val base64List = java.util.Base64.getEncoder().encodeToString(
            "vless://uuid@1.2.3.4:443?type=tcp#n1\nvless://uuid@5.6.7.8:443?type=tcp#n2".toByteArray()
        )
        val steps = mutableListOf<SubscriptionContentChoice.Decision>()
        var has = false
        var saw = ChannelQuality.PLAIN
        for (text in listOf(base64List, selectOnly, xrayJson)) {
            val d = eval(has = has, saw = saw, text = text)
            if (d.accept) {
                has = true
                saw = d.sawQuality
            }
            steps.add(d)
            if (d.stop) break
        }
        assertEquals(3, steps.size)
        assertTrue(steps.last().stop)
        assertEquals(ChannelQuality.RICH, saw)
    }
}
