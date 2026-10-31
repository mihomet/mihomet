package org.mihomet.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.data.subs.ProfileValidation
import org.mihomet.app.ui.screens.profiles.ProfilesViewModel

/**
 * Semantic, fail-closed profile validation (G2): [ProfileValidation.isValid]
 * guards profile persistence (subscription refresh — including the background
 * worker, remote add, clipboard / deep-link import) against payloads the core
 * would turn into "route everything to DIRECT".
 *
 * Key semantics versus the old presence-only check: only a **non-empty**
 * `proxies` / `proxy-providers` / `rules` counts, so a broken subscription
 * returning `proxies: []` + `rules: []` is now rejected.
 */
class ProfileValidationTest {

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

        assertTrue(ProfileValidation.isValid(yaml))
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

        assertTrue(ProfileValidation.isValid(yaml))
    }

    @Test
    fun `proxy-providers without rules is valid`() {
        val yaml = """
            mixed-port: 7890
            proxy-providers:
              sub:
                type: http
                url: "https://example.com/sub"
                path: ./sub.yaml
        """.trimIndent()

        assertTrue(ProfileValidation.isValid(yaml))
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

        assertTrue(ProfileValidation.isValid(yaml))
    }

    @Test
    fun `inline proxies with empty rules is valid as a node library`() {
        // Documented decision: a config with nodes but without rules is a node
        // library; routing is app-managed in that case.
        val yaml = """
            proxies:
              - name: "Node"
                type: ss
                server: 1.2.3.4
                port: 8388
                cipher: aes-256-gcm
                password: "x"
            rules: []
        """.trimIndent()

        assertTrue(ProfileValidation.isValid(yaml))
    }

    @Test
    fun `empty proxies with empty rules is invalid broken subscription`() {
        // The external-audit finding: a presence-only validator accepted this
        // degenerate payload, and the core routed everything to DIRECT while the
        // UI showed "connected".
        assertFalse(ProfileValidation.isValid("proxies: []\nrules: []"))
    }

    @Test
    fun `proxies null is invalid`() {
        assertFalse(ProfileValidation.isValid("proxies: null"))
        assertFalse(ProfileValidation.isValid("proxies:"))
    }

    @Test
    fun `empty proxies list alone is invalid`() {
        assertFalse(ProfileValidation.isValid("proxies: []"))
    }

    @Test
    fun `empty proxy-providers map is invalid`() {
        assertFalse(ProfileValidation.isValid("proxy-providers: {}"))
    }

    @Test
    fun `empty rules list alone is invalid`() {
        assertFalse(ProfileValidation.isValid("rules: []"))
    }

    @Test
    fun `mapping without proxies providers or rules is invalid`() {
        // A keyless shell (e.g. what a failed conversion used to leave behind)
        // makes the core start with an empty rule set routing everything to DIRECT.
        val yaml = "mixed-port: 7890\nlog-level: info"

        assertFalse(ProfileValidation.isValid(yaml))
    }

    @Test
    fun `blank yaml is invalid`() {
        assertFalse(ProfileValidation.isValid(""))
        assertFalse(ProfileValidation.isValid("   \n\t "))
    }

    @Test
    fun `non-mapping yaml is invalid`() {
        assertFalse(ProfileValidation.isValid("just-a-scalar"))
        assertFalse(ProfileValidation.isValid("- a\n- b"))
    }

    @Test
    fun `unparsable yaml is invalid`() {
        assertFalse(ProfileValidation.isValid("proxies: [unclosed"))
    }

    @Test
    fun `ProfilesViewModel companion delegates to ProfileValidation`() {
        // The UI entry points keep their historic name; it must stay a pure
        // delegation to the shared validator (single source of truth).
        val valid = """
            proxies:
              - name: "Node"
                type: ss
                server: 1.2.3.4
                port: 8388
                cipher: aes-256-gcm
                password: "x"
        """.trimIndent()

        assertTrue(ProfilesViewModel.isValidGeneratedConfig(valid))
        assertFalse(ProfilesViewModel.isValidGeneratedConfig("proxies: []\nrules: []"))
        assertFalse(ProfilesViewModel.isValidGeneratedConfig(""))
    }
}
