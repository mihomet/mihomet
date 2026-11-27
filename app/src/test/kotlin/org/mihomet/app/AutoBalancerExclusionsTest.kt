package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.data.preferences.SettingsRepository
import org.mihomet.app.core.config.ConfigGenerator
import org.mihomet.app.data.preferences.AppSettings
import org.yaml.snakeyaml.Yaml

class AutoBalancerExclusionsTest {
    @Test fun `normalization preserves separate patterns across read write cycles`() {
        val normalized = SettingsRepository.normalizeAutoBalancerExcludes("  Bridge \r\n Gaming\r\n\r\nUS\tWest\u0000")
        assertEquals("Bridge\nGaming\nUS West", normalized)
        assertEquals(normalized, SettingsRepository.normalizeAutoBalancerExcludes(normalized))
    }

    @Test fun `stored multiline exclusions remove each matching node independently`() {
        val input = "proxies:\n" + listOf("Bridge", "Gaming", "FR", "DE").joinToString("\n") {
            "- {name: $it, type: socks5, server: example.com, port: 1080}"
        }
        val excludes = SettingsRepository.normalizeAutoBalancerExcludes("Bridge\nGaming")
        val yaml = ConfigGenerator.generateRuntimeConfig(input, AppSettings(autoBalancerExcludes = excludes))
        val parsed = Yaml().load<Map<String, Any>>(yaml)
        val groups = parsed["proxy-groups"] as List<*>
        val auto = groups.filterIsInstance<Map<*, *>>().first { it["name"] == "AUTO" }
        assertEquals(listOf("FR", "DE"), auto["proxies"])
        assertTrue(excludes.length <= 2000)
    }
}
