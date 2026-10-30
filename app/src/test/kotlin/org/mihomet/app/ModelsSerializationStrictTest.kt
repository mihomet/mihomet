package org.mihomet.app

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.data.api.ConfigsUpdateRequest
import org.mihomet.app.data.api.LogEntry
import org.mihomet.app.data.api.MemoryInfo
import org.mihomet.app.data.api.MihomoVersion
import org.mihomet.app.data.api.ProxiesResponse
import org.mihomet.app.data.api.ProxyGroupOrNode
import org.mihomet.app.data.api.TrafficSample
import org.mihomet.app.data.api.UpdateProxyRequest

/**
 * Strict serialization tests for the Mihomo API models, mirroring the exact
 * [Json] configuration used inside [org.mihomet.app.data.api.MihomoApiClient]
 * (ignoreUnknownKeys, isLenient, encodeDefaults): representative core payloads
 * with nested history, unicode node names and unknown extra fields.
 */
class ModelsSerializationStrictTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Test
    fun `representative proxies payload decodes with unicode names and nested history`() {
        val payload = """
        {
          "proxies": {
            "PROXY": {
              "alive": true, "type": "Selector", "name": "PROXY",
              "udp": true, "xudp": true, "tfo": false, "hidden": false,
              "icon": "https://example.com/proxy.png",
              "now": "🇩🇪 Германия 01",
              "all": ["DIRECT", "🇩🇪 Германия 01", "Auto"]
            },
            "Auto": {
              "alive": true, "type": "URLTest", "name": "Auto",
              "udp": true, "fixed": 0, "timeout": 5000, "tolerance": 50,
              "now": "🇩🇪 Германия 01",
              "all": ["🇩🇪 Германия 01"],
              "history": [
                {"time": "2026-10-28T10:00:00.123456789Z", "delay": 123},
                {"time": "2026-10-28T10:01:00.123456789Z", "delay": 0},
                {"time": "2026-10-28T10:02:00.123456789Z", "delay": 214}
              ]
            },
            "🇩🇪 Германия 01": {
              "alive": true, "type": "Vless", "name": "🇩🇪 Германия 01",
              "udp": true, "xudp": true, "tls": true, "tfo": false,
              "network": "tcp", "flow": "xtls-rprx-vision",
              "history": [{"time": "2026-10-28T10:00:00.123456789Z", "delay": 187}]
            },
            "🇯🇵 Tokyo — IEPL #2": {
              "alive": false, "type": "Hysteria2", "name": "🇯🇵 Tokyo — IEPL #2",
              "udp": true, "ports": "443,8443",
              "history": [
                {"time": "2026-10-28T09:59:00.123456789Z", "delay": 0},
                {"time": "2026-10-28T10:00:00.123456789Z", "delay": 0}
              ]
            },
            "DIRECT": {"alive": true, "type": "Direct", "name": "DIRECT", "udp": false},
            "REJECT": {"alive": true, "type": "Reject", "name": "REJECT", "udp": false}
          }
        }
        """.trimIndent()

        val proxies = json.decodeFromString<ProxiesResponse>(payload).proxies

        assertEquals(6, proxies.size)

        val selector = proxies.getValue("PROXY")
        assertEquals("Selector", selector.type)
        assertEquals("🇩🇪 Германия 01", selector.now)
        assertEquals(listOf("DIRECT", "🇩🇪 Германия 01", "Auto"), selector.all)
        assertTrue(selector.history.isEmpty())

        val auto = proxies.getValue("Auto")
        assertEquals(3, auto.history.size)
        assertEquals(123, auto.history[0].delay)
        assertEquals(0, auto.history[1].delay)
        assertEquals(214, auto.history[2].delay)
        assertEquals("2026-10-28T10:02:00.123456789Z", auto.history[2].time)

        val germany = proxies.getValue("🇩🇪 Германия 01")
        assertEquals("Vless", germany.type)
        assertEquals("🇩🇪 Германия 01", germany.name)
        assertEquals(187, germany.history.single().delay)
        assertTrue(germany.udp)

        val japan = proxies.getValue("🇯🇵 Tokyo — IEPL #2")
        assertEquals("Hysteria2", japan.type)
        assertTrue(japan.history.all { it.delay == 0 })
    }

    @Test
    fun `proxy node with missing optional fields falls back to model defaults`() {
        val payload = """{"proxies":{"Muted":{"type":"Shadowsocks"}}}"""

        val node: ProxyGroupOrNode = json.decodeFromString<ProxiesResponse>(payload)
            .proxies.getValue("Muted")

        assertEquals("Shadowsocks", node.type)
        assertEquals("", node.name)
        assertNull(node.now)
        assertNull(node.all)
        assertTrue(node.history.isEmpty())
        assertTrue("udp defaults to true in the model", node.udp)
    }

    @Test
    fun `traffic sample ignores extra fields`() {
        val payload = """{"up":1024,"down":2048,"cpu":12,"memory":999,"extra":{"a":1}}"""

        val sample = json.decodeFromString<TrafficSample>(payload)

        assertEquals(1024L, sample.up)
        assertEquals(2048L, sample.down)
    }

    @Test
    fun `version payload ignores meta field from the core`() {
        val version = json.decodeFromString<MihomoVersion>("""{"version":"1.19.33","meta":true}""")

        assertEquals("1.19.33", version.version)
        assertEquals(false, version.premium)
    }

    @Test
    fun `version payload decodes premium flag`() {
        val version = json.decodeFromString<MihomoVersion>("""{"version":"1.19.2","premium":true}""")

        assertEquals(true, version.premium)
    }

    @Test
    fun `memory payload decodes inuse and oslimit and ignores unknown fields`() {
        val payload = """{"inuse":52428800,"oslimit":0,"extra":{"foo":1},"deprecated":true}"""

        val memory = json.decodeFromString<MemoryInfo>(payload)

        assertEquals(52_428_800L, memory.inuse)
        assertEquals(0L, memory.oslimit)
    }

    @Test
    fun `memory payload with a non-zero oslimit and missing fields`() {
        val memory = json.decodeFromString<MemoryInfo>("""{"inuse":1048576,"oslimit":268435456}""")
        assertEquals(1_048_576L, memory.inuse)
        assertEquals(268_435_456L, memory.oslimit)

        val defaulted = json.decodeFromString<MemoryInfo>("{}")
        assertEquals(0L, defaulted.inuse)
        assertEquals(0L, defaulted.oslimit)
    }

    @Test
    fun `log entry decodes and falls back to info type`() {
        val entry = json.decodeFromString<LogEntry>("""{"type":"warning","payload":"dns resolve failed"}""")
        assertEquals("warning", entry.type)
        assertEquals("dns resolve failed", entry.payload)

        val defaulted = json.decodeFromString<LogEntry>("""{"payload":"start initial routine"}""")
        assertEquals("info", defaulted.type)
    }

    @Test
    fun `request models encode to exact wire format`() {
        val updateProxy = json.encodeToString(UpdateProxyRequest.serializer(), UpdateProxyRequest(name = "Узел №2"))
        assertEquals("""{"name":"Узел №2"}""", updateProxy)

        val configs = json.encodeToString(ConfigsUpdateRequest.serializer(), ConfigsUpdateRequest(mode = "rule"))
        // encodeDefaults = true -> the null logLevel is written explicitly, exactly
        // as MihomoApiClient sends it to the core.
        assertEquals("""{"mode":"rule","logLevel":null}""", configs)
    }
}
