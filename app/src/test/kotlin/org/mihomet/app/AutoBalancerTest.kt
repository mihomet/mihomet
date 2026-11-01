package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.core.CoreConstants
import org.mihomet.app.core.config.ConfigGenerator
import org.mihomet.app.data.preferences.AppSettings
import org.yaml.snakeyaml.Yaml

/**
 * Strict coverage for the built-in auto-balancer (TODO.md spec): an AUTO url-test
 * group synthesized into the runtime config when the profile has no balancer of
 * its own. Panel-provided balancers always win; exclusions shape the pool.
 */
class AutoBalancerTest {

    private val threeNodes = """
        proxies:
        - {name: NodeA, type: ss, server: a.example.com, port: 443, cipher: aes-128-gcm, password: pw}
        - {name: NodeB, type: ss, server: b.example.com, port: 443, cipher: aes-128-gcm, password: pw}
        - {name: NodeC, type: ss, server: c.example.com, port: 443, cipher: aes-128-gcm, password: pw}
        rules: [MATCH,PROXY]
    """.trimIndent()

    private val panelSelect = """
        proxies:
        - {name: NodeA, type: ss, server: a.example.com, port: 443, cipher: aes-128-gcm, password: pw}
        - {name: NodeB, type: ss, server: b.example.com, port: 443, cipher: aes-128-gcm, password: pw}
        proxy-groups:
        - {name: PanelMain, type: select, proxies: [NodeA, NodeB]}
        rules: [MATCH,PanelMain]
    """.trimIndent()

    private fun generate(profile: String, settings: AppSettings): Map<String, Any> {
        val yaml = ConfigGenerator.generateRuntimeConfig(profile, settings, tunFd = null)
        @Suppress("UNCHECKED_CAST")
        return Yaml().load<Any>(yaml) as Map<String, Any>
    }

    @Suppress("UNCHECKED_CAST")
    private fun groups(parsed: Map<String, Any>): List<Map<String, Any>> =
        parsed["proxy-groups"] as List<Map<String, Any>>

    @Suppress("UNCHECKED_CAST")
    private fun autoGroup(parsed: Map<String, Any>): Map<String, Any>? =
        groups(parsed).firstOrNull { it["name"] == CoreConstants.AUTO_GROUP_NAME }

    @Test
    fun `synthesizes AUTO url-test group and leads the fallback PROXY group`() {
        val parsed = generate(threeNodes, AppSettings(controllerSecret = "pinned"))

        val auto = autoGroup(parsed)
        assertNotNull("AUTO must be synthesized for a group-less profile", auto)
        assertEquals("url-test", auto!!["type"])
        assertEquals(true, auto["udp"])
        assertEquals(listOf("NodeA", "NodeB", "NodeC"), auto["proxies"])
        assertNotNull(auto["url"])
        assertEquals(CoreConstants.AUTO_PROBE_INTERVAL_SECONDS, auto["interval"])
        assertEquals(CoreConstants.AUTO_PROBE_TOLERANCE_MS, auto["tolerance"])

        val proxy = groups(parsed).first { it["name"] == "PROXY" }
        assertEquals(
            "AUTO must be the default pick of the fallback PROXY group",
            CoreConstants.AUTO_GROUP_NAME,
            (proxy["proxies"] as List<*>).first()
        )
        // All nodes stay manually selectable in PROXY
        assertTrue((proxy["proxies"] as List<*>).containsAll(listOf("NodeA", "NodeB", "NodeC")))
    }

    @Test
    fun `panel-provided url-test group wins and no AUTO is added`() {
        val profile = threeNodes + """
            
            proxy-groups:
            - {name: PanelAuto, type: url-test, proxies: [NodeA, NodeB], url: http://x/204, interval: 120}
        """.trimIndent()
        val parsed = generate(profile, AppSettings(controllerSecret = "pinned"))

        assertNull("panel balancer must win", autoGroup(parsed))
        val panelAuto = groups(parsed).first { it["name"] == "PanelAuto" }
        assertEquals("panel group untouched", "http://x/204", panelAuto["url"])
    }

    @Test
    fun `panel load-balance group also suppresses synthesis`() {
        val profile = threeNodes + """
            
            proxy-groups:
            - {name: LB, type: load-balance, proxies: [NodeA, NodeB], strategy: round-robin}
        """.trimIndent()
        assertNull(autoGroup(generate(profile, AppSettings(controllerSecret = "pinned"))))
    }

    @Test
    fun `exclusions are case-insensitive substrings and pool needs two survivors`() {
        val settings = AppSettings(controllerSecret = "pinned", autoBalancerExcludes = "nodeb")
        val parsed = generate(threeNodes, settings)

        val auto = autoGroup(parsed)
        assertNotNull(auto)
        assertEquals("excluded node must leave the pool", listOf("NodeA", "NodeC"), auto!!["proxies"])
        // Excluded nodes stay manually selectable in PROXY
        val proxy = groups(parsed).first { it["name"] == "PROXY" }
        assertTrue((proxy["proxies"] as List<*>).contains("NodeB"))
    }

    @Test
    fun `pool smaller than two after exclusions suppresses synthesis`() {
        val settings = AppSettings(
            controllerSecret = "pinned",
            autoBalancerExcludes = "NodeA\nNodeC"
        )
        assertNull("one survivor is not a pool", autoGroup(generate(threeNodes, settings)))
    }

    @Test
    fun `single-node profile never gets a balancer`() {
        val single = """
            proxies:
            - {name: NodeA, type: ss, server: a.example.com, port: 443, cipher: aes-128-gcm, password: pw}
        """.trimIndent()
        assertNull(autoGroup(generate(single, AppSettings(controllerSecret = "pinned"))))
    }

    @Test
    fun `disabled feature never synthesizes`() {
        val settings = AppSettings(controllerSecret = "pinned", autoBalancerEnabled = false)
        val parsed = generate(threeNodes, settings)
        assertNull(autoGroup(parsed))
        val proxy = groups(parsed).first { it["name"] == "PROXY" }
        assertFalse(
            (proxy["proxies"] as List<*>).contains(CoreConstants.AUTO_GROUP_NAME)
        )
    }

    @Test
    fun `panel select group gets AUTO appended by default`() {
        val parsed = generate(panelSelect, AppSettings(controllerSecret = "pinned"))

        val auto = autoGroup(parsed)
        assertNotNull(auto)

        val panelMain = groups(parsed).first { it["name"] == "PanelMain" }
        val proxies = panelMain["proxies"] as List<*>
        assertEquals(
            "appended (not prepended): the panel's default pick is preserved",
            "NodeA",
            proxies.first()
        )
        assertEquals(CoreConstants.AUTO_GROUP_NAME, proxies.last())
    }

    @Test
    fun `panel select group gets AUTO prepended when prefer-default is on`() {
        val settings = AppSettings(controllerSecret = "pinned", autoBalancerPreferDefault = true)
        val parsed = generate(panelSelect, settings)

        val panelMain = groups(parsed).first { it["name"] == "PanelMain" }
        assertEquals(
            CoreConstants.AUTO_GROUP_NAME,
            (panelMain["proxies"] as List<*>).first()
        )
    }
}
