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

@RunWith(AndroidJUnit4::class)
class CoreProbeInstrumentedTest {
    @Test
    fun temporaryCoreCanReachLoopbackWithoutRootOrAnActiveVpn() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = MockWebServer().apply { enqueue(MockResponse().setResponseCode(204)); start() }
        try {
            val result = CoreManager(context).runTemporaryCoreProbe("rules: ['MATCH,DIRECT']") {
                it.testNodeDelay("DIRECT", server.url("/probe").toString(), 3000).getOrThrow()
            }
            assertTrue("Unprivileged probe failed: ${result.exceptionOrNull()?.javaClass?.simpleName}", result.getOrThrow() >= 0)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
