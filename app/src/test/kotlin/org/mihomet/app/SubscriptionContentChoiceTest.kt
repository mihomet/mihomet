package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.data.subs.SubscriptionContentChoice

/**
 * Strict coverage of the content-aware UA fallback decision: a grouped response
 * beats an earlier group-less one (panels that strip groups from unknown User-Agents),
 * the first grouped response stops the chain, group-less responses are kept as
 * fallback while entries remain.
 */
class SubscriptionContentChoiceTest {

    private val groupless = """
        proxies:
        - {name: a, type: socks5, server: 127.0.0.1, port: 1080}
        - {name: b, type: socks5, server: 127.0.0.1, port: 1081}
    """.trimIndent()

    private val grouped = """
        proxies:
        - {name: a, type: socks5, server: 127.0.0.1, port: 1080}
        proxy-groups:
        - {name: AUTO, type: url-test, proxies: [a]}
    """.trimIndent()

    @Test
    fun `first group-less response is accepted but the chain continues`() {
        val d = SubscriptionContentChoice.evaluate(
            currentHasResponse = false, currentSawGroups = false,
            attemptEncrypted = false, attemptText = groupless
        )
        assertTrue(d.accept)
        assertFalse(d.stop)
        assertFalse(d.sawGroups)
    }

    @Test
    fun `later grouped response replaces the stored group-less one and stops the chain`() {
        val first = SubscriptionContentChoice.evaluate(
            currentHasResponse = false, currentSawGroups = false,
            attemptEncrypted = false, attemptText = groupless
        )
        val second = SubscriptionContentChoice.evaluate(
            currentHasResponse = first.accept, currentSawGroups = first.sawGroups,
            attemptEncrypted = false, attemptText = grouped
        )
        assertTrue(second.accept)
        assertTrue(second.stop)
        assertTrue(second.sawGroups)
    }

    @Test
    fun `first grouped response stops the chain immediately`() {
        val d = SubscriptionContentChoice.evaluate(
            currentHasResponse = false, currentSawGroups = false,
            attemptEncrypted = false, attemptText = grouped
        )
        assertTrue(d.accept)
        assertTrue(d.stop)
        assertTrue(d.sawGroups)
    }

    @Test
    fun `a stored grouped response is never replaced by a group-less one`() {
        val d = SubscriptionContentChoice.evaluate(
            currentHasResponse = true, currentSawGroups = true,
            attemptEncrypted = false, attemptText = groupless
        )
        assertFalse(d.accept)
        assertFalse(d.stop)
        assertTrue(d.sawGroups)
    }

    @Test
    fun `encrypted responses are accepted immediately without probing`() {
        val d = SubscriptionContentChoice.evaluate(
            currentHasResponse = false, currentSawGroups = false,
            attemptEncrypted = true, attemptText = groupless
        )
        assertTrue(d.accept)
        assertTrue(d.stop)
        assertTrue(d.sawGroups)
    }

    @Test
    fun `node-less grouped payload is treated as group-less`() {
        val emptyGroups = """
            proxies: []
            proxy-groups:
            - {name: g, type: select, proxies: [DIRECT]}
        """.trimIndent()
        val d = SubscriptionContentChoice.evaluate(
            currentHasResponse = false, currentSawGroups = false,
            attemptEncrypted = false, attemptText = emptyGroups
        )
        assertTrue(d.accept)
        assertFalse(d.stop)
        assertFalse(d.sawGroups)
    }

    @Test
    fun `countTopLevelList counts nested yaml safely`() {
        assertEquals(1, SubscriptionContentChoice.countTopLevelList(grouped, "proxy-groups"))
        assertEquals(2, SubscriptionContentChoice.countTopLevelList(groupless, "proxies"))
        assertEquals(0, SubscriptionContentChoice.countTopLevelList(groupless, "proxy-groups"))
        assertEquals(0, SubscriptionContentChoice.countTopLevelList("not: [valid: yaml:", "proxies"))
    }
}
