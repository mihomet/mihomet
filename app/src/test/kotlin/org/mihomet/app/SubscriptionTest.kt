package org.mihomet.app

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mihomet.app.core.config.ConfigGenerator
import org.mihomet.app.data.preferences.AppSettings
import org.yaml.snakeyaml.Yaml

class SubscriptionTest {

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test user subscription download and config generation`() = runBlocking {
        val testUrl = "https://sub.meow.ac/Ypoh845BZRQDT2Rn"
        val hwid = "e3a89c20-7f5b-4d1e-8a90-3b4c5d6e7f81"

        val client = HttpClient()
        val yamlContent: String = try {
            client.get(testUrl) {
                header("User-Agent", "ClashMeta; mihomo/1.19.33")
                header("x-hwid", hwid)
            }.body()
        } catch (e: Exception) {
            println("Network unavailable in test environment, skipping remote fetch: ${e.message}")
            return@runBlocking
        }

        assertTrue("Expected proxies in subscription", yamlContent.contains("proxies:"))

        // Verify SnakeYAML can parse the subscription
        val yaml = Yaml()
        val parsed = yaml.load<Map<String, Any>>(yamlContent)
        assertNotNull(parsed["proxies"])
        val proxies = parsed["proxies"] as List<Map<String, Any>>
        assertTrue("Expected at least 1 proxy node", proxies.isNotEmpty())

        // Test synthesizing with ConfigGenerator
        val settings = AppSettings(
            tproxyPort = 7893,
            mixedPort = 7890,
            dnsPort = 1053,
            controllerPort = 9090
        )

        val runtimeConfig = ConfigGenerator.generateRuntimeConfig(
            profileYamlContent = yamlContent,
            settings = settings,
            tunFd = null
        )

        val runtimeParsed = yaml.load<Map<String, Any>>(runtimeConfig)
        assertEquals(666, runtimeParsed["routing-mark"])
        assertEquals(7893, runtimeParsed["tproxy-port"])
        assertEquals("127.0.0.1:1053", (runtimeParsed["dns"] as Map<String, Any>)["listen"])

        val runtimeProxies = runtimeParsed["proxies"] as List<Map<String, Any>>
        assertEquals(proxies.size, runtimeProxies.size)
        println("Successfully imported ${runtimeProxies.size} nodes from subscription!")
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test user subscription download with Happ Xray UA and dynamic balancer conversion`() = runBlocking {
        val testUrl = "https://sub.meow.ac/Ypoh845BZRQDT2Rn"
        val hwid = "e3a89c20-7f5b-4d1e-8a90-3b4c5d6e7f81"

        val client = HttpClient()
        val rawResponse: String = try {
            client.get(testUrl) {
                header("User-Agent", "Happ/3.6.0 (Android; Xray)")
                header("x-hwid", hwid)
            }.body()
        } catch (e: Exception) {
            println("Network unavailable in test environment, skipping remote fetch: ${e.message}")
            return@runBlocking
        }

        assertTrue("Expected JSON response for Happ/Xray", rawResponse.trim().startsWith("["))

        val convertedYaml = org.mihomet.app.core.config.UniversalConfigConverter.convertToMihomoYaml(rawResponse)
        val yaml = Yaml()
        val parsed = yaml.load<Map<String, Any>>(convertedYaml)

        val proxies = parsed["proxies"] as List<Map<String, Any>>
        assertTrue("Expected at least 20 proxy nodes", proxies.size >= 20)

        val groups = parsed["proxy-groups"] as List<Map<String, Any>>
        val balancer = groups.firstOrNull { it["type"] == "url-test" }
        assertNotNull("Expected dynamic url-test balancer from Xray config", balancer)
        assertNotNull(balancer!!["name"])
        println("Dynamic balancer name from subscription: ${balancer["name"]}")
        println("Dynamic balancer URL: ${balancer["url"]}")
        println("Dynamic balancer interval: ${balancer["interval"]}")
        assertTrue("Balancer must have proxies", (balancer["proxies"] as List<*>).isNotEmpty())
    }
}
