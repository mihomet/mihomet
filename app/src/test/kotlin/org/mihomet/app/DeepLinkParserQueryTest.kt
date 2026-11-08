package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.core.DeepLinkParser

/**
 * Regression for the deep-link import silent failure: the `url` parameter
 * carries an http URL full of '/' — the old path/query split sliced INSIDE
 * that URL, emptying the action and returning the raw deep link instead of
 * the extracted subscription URL (import then never fetched).
 */
class DeepLinkParserQueryTest {

    @Test
    fun `install-sub url parameter is extracted even with slashes in the url`() {
        val extracted = DeepLinkParser.parse(
            "v2rayng://install-sub?url=http://localhost:54503/sub/uiflow"
        )
        assertEquals("http://localhost:54503/sub/uiflow", extracted)
    }

    @Test
    fun `clash install-config with url query extracts`() {
        val extracted = DeepLinkParser.parse(
            "clash://install-config?url=https%3A%2F%2Fpanel.example%2Fsub%2Fabc"
        )
        assertEquals("https://panel.example/sub/abc", extracted)
    }

    @Test
    fun `happ path payloads still parse`() {
        val r = DeepLinkParser.parse("happ://routing/add/eyJhIjoxfQ==")
        assertTrue(r != null && r != "happ://routing/add/eyJhIjoxfQ==")
    }

    @Test
    fun `plain node link passes through as content`() {
        val link = "vless://uuid@1.2.3.4:443?encryption=none#node"
        assertEquals(link, DeepLinkParser.parse(link))
    }
}
