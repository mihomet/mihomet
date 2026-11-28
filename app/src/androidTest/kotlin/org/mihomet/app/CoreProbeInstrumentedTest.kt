package org.mihomet.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mihomet.app.core.CoreManager
import java.net.InetAddress

@RunWith(AndroidJUnit4::class)
class CoreProbeInstrumentedTest {
    @Test
    fun temporaryCoreCanReachLoopbackWithoutRootOrAnActiveVpn() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(204))
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        try {
            val result = CoreManager(context).runTemporaryCoreProbe("rules: ['MATCH,DIRECT']") {
                it.testNodeDelay("DIRECT", "http://127.0.0.1:${server.port}/probe", 3000).getOrThrow()
            }
            assertTrue(
                "Unprivileged probe failed: error=${result.exceptionOrNull()?.javaClass?.simpleName}, " +
                    "delay=${result.getOrNull()}, requests=${server.requestCount}",
                result.getOrThrow() >= 0
            )
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
