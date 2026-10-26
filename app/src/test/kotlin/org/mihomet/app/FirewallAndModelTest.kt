package org.mihomet.app

import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.mihomet.app.data.api.ConnectionsResponse
import org.mihomet.app.data.api.LogEntry
import org.mihomet.app.data.api.ProxiesResponse
import org.mihomet.app.data.api.TrafficSample

class FirewallAndModelTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `test traffic sample deserialization`() {
        val rawJson = """{"up": 1048576, "down": 5242880}"""
        val sample = json.decodeFromString<TrafficSample>(rawJson)
        assertEquals(1048576L, sample.up)
        assertEquals(5242880L, sample.down)
    }

    @Test
    fun `test log entry deserialization`() {
        val rawJson = """{"type": "info", "payload": "[TCP] connection to 1.1.1.1:443 matched Rule"}"""
        val entry = json.decodeFromString<LogEntry>(rawJson)
        assertEquals("info", entry.type)
        assertTrue(entry.payload.contains("1.1.1.1:443"))
    }

    @Test
    fun `test connections response deserialization`() {
        val rawJson = """
            {
              "downloadTotal": 1000000,
              "uploadTotal": 50000,
              "connections": [
                {
                  "id": "conn-1234",
                  "metadata": {
                    "network": "tcp",
                    "type": "HTTPS",
                    "sourceIP": "192.168.1.10",
                    "destinationIP": "104.26.10.1",
                    "destinationPort": "443",
                    "host": "cloudflare.com",
                    "process": "com.android.chrome"
                  },
                  "upload": 2048,
                  "download": 81920,
                  "start": "2026-10-25T10:00:00Z",
                  "chains": ["PROXY", "US-Node-1"],
                  "rule": "RuleSet"
                }
              ]
            }
        """.trimIndent()

        val response = json.decodeFromString<ConnectionsResponse>(rawJson)
        assertEquals(1000000L, response.downloadTotal)
        assertEquals(1, response.connections.size)
        assertEquals("conn-1234", response.connections[0].id)
        assertEquals("cloudflare.com", response.connections[0].metadata.host)
        assertEquals("com.android.chrome", response.connections[0].metadata.process)
        assertEquals("US-Node-1", response.connections[0].chains.last())
    }

    @Test
    fun `test proxies response deserialization`() {
        val rawJson = """
            {
              "proxies": {
                "PROXY": {
                  "name": "PROXY",
                  "type": "Selector",
                  "now": "Node-A",
                  "all": ["Node-A", "Node-B"],
                  "history": [
                    {"time": "2026-10-25T10:00:00Z", "delay": 65}
                  ]
                },
                "Node-A": {
                  "name": "Node-A",
                  "type": "Vmess",
                  "history": [
                    {"time": "2026-10-25T10:00:00Z", "delay": 65}
                  ]
                }
              }
            }
        """.trimIndent()

        val response = json.decodeFromString<ProxiesResponse>(rawJson)
        assertEquals(2, response.proxies.size)
        val group = response.proxies["PROXY"]
        assertNotNull(group)
        assertEquals("Selector", group?.type)
        assertEquals("Node-A", group?.now)
        assertEquals(2, group?.all?.size)
        assertEquals(65, group?.history?.firstOrNull()?.delay)
    }

    @Test
    fun `test proxy and group names url encoding for api client`() {
        val groupName = "⚡️ Основной Выбор"
        val nodeName = "🇷🇺 Russia 01"
        val autoGroupName = "⚡ Автовыбор"

        val encodedGroup = groupName.encodeURLPathPart()
        val encodedNode = nodeName.encodeURLPathPart()
        val encodedAuto = autoGroupName.encodeURLPathPart()

        assertFalse("Encoded group must not contain raw spaces", encodedGroup.contains(" "))
        assertFalse("Encoded node must not contain raw spaces", encodedNode.contains(" "))
        assertFalse("Encoded auto must not contain raw spaces", encodedAuto.contains(" "))

        assertTrue(encodedGroup.contains("%20"))
        assertTrue(encodedNode.contains("%20"))
        assertTrue(encodedAuto.contains("%20"))
    }
}
