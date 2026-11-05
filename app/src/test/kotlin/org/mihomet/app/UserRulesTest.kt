package org.mihomet.app

import org.junit.Assert.*
import org.junit.Test
import org.mihomet.app.core.config.ConfigGenerator
import org.mihomet.app.core.config.UserRules
import org.mihomet.app.data.preferences.AppSettings
import org.yaml.snakeyaml.Yaml

class UserRulesTest {

    // ---- sanitizeDomain ----

    @Test
    fun `plain domains pass through lowercased`() {
        assertEquals("ya.ru", UserRules.sanitizeDomain("Ya.RU"))
        assertEquals("ru", UserRules.sanitizeDomain("RU"))
        assertEquals("mail.example.com", UserRules.sanitizeDomain(" mail.example.com "))
    }

    @Test
    fun `scheme path and port are stripped`() {
        assertEquals("ya.ru", UserRules.sanitizeDomain("https://ya.ru/pkdlcs?a=1"))
        assertEquals("ya.ru", UserRules.sanitizeDomain("http://ya.ru:8080"))
        assertEquals("ya.ru", UserRules.sanitizeDomain("ya.ru/path"))
        assertEquals("ya.ru", UserRules.sanitizeDomain("ya.ru."))
    }

    @Test
    fun `garbage is rejected`() {
        assertNull(UserRules.sanitizeDomain(""))
        assertNull(UserRules.sanitizeDomain("  "))
        assertNull(UserRules.sanitizeDomain("not a domain!"))
        assertNull(UserRules.sanitizeDomain("-bad-.com"))
        assertNull(UserRules.sanitizeDomain("münchen.de"))
        assertNull(UserRules.sanitizeDomain("http://"))
    }

    // ---- directRules ----

    @Test
    fun `rules are generated as prepended DOMAIN-SUFFIX DIRECT`() {
        val rules = UserRules.directRules(setOf("ru", "Tinkoff.ru"))
        assertEquals(listOf("DOMAIN-SUFFIX,ru,DIRECT", "DOMAIN-SUFFIX,tinkoff.ru,DIRECT"), rules)
    }

    @Test
    fun `duplicates and invalid entries are dropped`() {
        val rules = UserRules.directRules(setOf("ru", "RU", "!!", "ya.ru"))
        assertEquals(listOf("DOMAIN-SUFFIX,ru,DIRECT", "DOMAIN-SUFFIX,ya.ru,DIRECT"), rules)
    }

    // ---- ConfigGenerator integration ----

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `generator prepends user direct rules before profile rules`() {
        val profile = """
            proxies:
              - name: "N"
                type: ss
                server: 1.2.3.4
                port: 8388
                cipher: aes-256-gcm
                password: "p"
            rules:
              - MATCH,PROXY
        """.trimIndent()
        val settings = AppSettings(
            controllerSecret = "s",
            userDirectDomains = setOf("ru", "ya.ru")
        )
        val output = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = profile,
            settings = settings,
            tunFd = null
        )
        val parsed = Yaml().load<Map<String, Any>>(output)
        val rules = parsed["rules"] as List<String>
        assertEquals(
            listOf("DOMAIN-SUFFIX,ru,DIRECT", "DOMAIN-SUFFIX,ya.ru,DIRECT", "MATCH,PROXY"),
            rules
        )
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `generator synthesizes rules when profile has none`() {
        val profile = """
            proxies:
              - name: "N"
                type: ss
                server: 1.2.3.4
                port: 8388
                cipher: aes-256-gcm
                password: "p"
        """.trimIndent()
        val settings = AppSettings(controllerSecret = "s", userDirectDomains = setOf("ru"))
        val output = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = profile,
            settings = settings,
            tunFd = null
        )
        val parsed = Yaml().load<Map<String, Any>>(output)
        // The generator also guarantees a terminal MATCH rule for rule-less profiles.
        assertEquals(
            listOf("DOMAIN-SUFFIX,ru,DIRECT", "MATCH,PROXY"),
            parsed["rules"] as List<String>
        )
    }
}
