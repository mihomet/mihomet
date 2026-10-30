package org.mihomet.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.ui.screens.profiles.ProfilesViewModel

/**
 * Fail-closed profile validation (P0): [ProfilesViewModel.isValidGeneratedConfig]
 * guards profile persistence (subscription update, remote add, clipboard /
 * deep-link import) against conversion results the core would turn into
 * "route everything to DIRECT" (no proxies, no proxy-providers, no rules).
 */
class ProfilesViewModelValidationTest {

    @Test
    fun `config with inline proxies is valid`() {
        val yaml = """
            proxies:
              - name: "Node"
                type: ss
                server: 1.2.3.4
                port: 8388
                cipher: aes-256-gcm
                password: "x"
            rules:
              - MATCH,PROXY
        """.trimIndent()

        assertTrue(ProfilesViewModel.isValidGeneratedConfig(yaml))
    }

    @Test
    fun `proxy-providers without inline proxies is valid`() {
        // Nodes are fetched by the core at runtime — absence of inline proxies
        // must not reject provider-based subscriptions.
        val yaml = """
            proxy-providers:
              sub:
                type: http
                url: "https://example.com/sub"
                path: ./sub.yaml
            rules:
              - MATCH,PROXY
        """.trimIndent()

        assertTrue(ProfilesViewModel.isValidGeneratedConfig(yaml))
    }

    @Test
    fun `rules-only config without proxies is valid`() {
        // Documented decision: happ://routing deep-link imports produce exactly such
        // rules-only routing profiles (GEOSITE/GEOIP/IP-CIDR targets + MATCH rule).
        // Routing profiles carry no nodes by design and must keep importing.
        val yaml = """
            rules:
              - GEOSITE,CATEGORY-ADS-ALL,REJECT
              - IP-CIDR,192.168.0.0/16,DIRECT,no-resolve
              - MATCH,GLOBAL
        """.trimIndent()

        assertTrue(ProfilesViewModel.isValidGeneratedConfig(yaml))
    }

    @Test
    fun `mapping without proxies providers or rules is invalid`() {
        // A keyless shell (e.g. what a failed conversion used to leave behind)
        // makes the core start with an empty rule set routing everything to DIRECT.
        val yaml = "mixed-port: 7890\nlog-level: info"

        assertFalse(ProfilesViewModel.isValidGeneratedConfig(yaml))
    }

    @Test
    fun `blank yaml is invalid`() {
        assertFalse(ProfilesViewModel.isValidGeneratedConfig(""))
        assertFalse(ProfilesViewModel.isValidGeneratedConfig("   \n\t "))
    }

    @Test
    fun `non-mapping yaml is invalid`() {
        assertFalse(ProfilesViewModel.isValidGeneratedConfig("just-a-scalar"))
        assertFalse(ProfilesViewModel.isValidGeneratedConfig("- a\n- b"))
    }

    @Test
    fun `unparsable yaml is invalid`() {
        assertFalse(ProfilesViewModel.isValidGeneratedConfig("proxies: [unclosed"))
    }
}
