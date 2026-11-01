package org.mihomet.app

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mihomet.app.core.DeepLinkPolicy

/**
 * Manifest + policy assertions for the deep-link surface (audit P1.3).
 *
 * Threat model (audit P0 §1.1): import schemes are intentionally BROWSABLE
 * (browser import links must work), while the `mihomet://` control scheme must
 * NOT be reachable from a browser — it lives on a non-BROWSABLE activity-alias
 * and is gated again at runtime by DeepLinkPolicy. These checks pin the
 * manifest contract on a real device.
 */
@RunWith(AndroidJUnit4::class)
class DeepLinkManifestInstrumentedTest {

    private val pm = InstrumentationRegistry.getInstrumentation().context.packageManager
    private val selfPackage: String = InstrumentationRegistry.getInstrumentation().context.packageName

    private fun browsableResolutions(uri: String): List<String> {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(uri)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.activityInfo.name }
            .filter { it.first == selfPackage }
            .map { it.second }
    }

    private fun appResolutions(uri: String): List<String> {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(uri)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.activityInfo.name }
            .filter { it.first == selfPackage }
            .map { it.second }
    }

    @Test
    fun importSchemesAreBrowsable() {
        for (uri in listOf(
            "clash://install-config?url=https%3A%2F%2Fexample.invalid%2Fsub",
            "clashmeta://install-config?url=https%3A%2F%2Fexample.invalid%2Fsub",
            "flclash://install-config?url=https%3A%2F%2Fexample.invalid%2Fsub",
            "happ://add/c29tZXBheWxvYWQ=",
            "incy://add/c29tZXBheWxvYWQ=",
            "v2rayng://install-sub?url=https%3A%2F%2Fexample.invalid%2Fsub",
            "sing-box://import-remote?url=https%3A%2F%2Fexample.invalid%2Fsub"
        )) {
            val hits = browsableResolutions(uri)
            assertTrue("import link $uri must resolve BROWSABLE", hits.isNotEmpty())
            assertTrue(
                "import link $uri must land on MainActivity",
                hits.any { it.endsWith(".ui.MainActivity") || it.contains("MainActivity") }
            )
        }
    }

    @Test
    fun controlSchemeIsNotBrowsable() {
        val browsable = browsableResolutions("mihomet://start")
        assertTrue(
            "mihomet:// must NOT be reachable from a browser (BROWSABLE category)",
            browsable.isEmpty()
        )

        // The non-browser path still resolves — the widget/tile PendingIntents
        // use exactly this filter (DEFAULT, no BROWSABLE).
        val appOnly = appResolutions("mihomet://start")
        assertTrue("mihomet:// must resolve for in-app PendingIntents", appOnly.isNotEmpty())
    }

    @Test
    fun importAndControlEntryPointsDiffer() {
        val import = browsableResolutions("happ://add/cGF5bG9hZA==")
        val control = appResolutions("mihomet://start")
        assertTrue(import.isNotEmpty())
        assertTrue(control.isNotEmpty())
        assertNotEquals(
            "control links must enter through a different component than import links",
            import.toSet(),
            control.toSet()
        )
    }

    /** Runtime gate (second layer of the manifest split) — on-device mirror of the JVM tests. */
    @Test
    fun controlLinksAreFailClosed() {
        for (origin in listOf(
            DeepLinkPolicy.IntentOrigin.BROWSABLE,
            DeepLinkPolicy.IntentOrigin.EXTERNAL_APP
        )) {
            assertEquals(
                "non-self origin must never control the tunnel",
                DeepLinkPolicy.ControlDecision.REJECT,
                DeepLinkPolicy.decideControl(origin)
            )
        }
        assertEquals(
            DeepLinkPolicy.ControlDecision.ALLOW,
            DeepLinkPolicy.decideControl(DeepLinkPolicy.IntentOrigin.SELF)
        )

        // Origin resolution: own referrer → SELF; everything else fails closed.
        assertEquals(
            DeepLinkPolicy.IntentOrigin.SELF,
            DeepLinkPolicy.resolveOrigin(
                browsable = false,
                selfPackage = selfPackage,
                referrer = "android-app://$selfPackage",
                callingPackage = null
            )
        )
        assertEquals(
            DeepLinkPolicy.IntentOrigin.BROWSABLE,
            DeepLinkPolicy.resolveOrigin(
                browsable = true,
                selfPackage = selfPackage,
                referrer = "https://evil.example/page",
                callingPackage = null
            )
        )
        assertEquals(
            DeepLinkPolicy.IntentOrigin.EXTERNAL_APP,
            DeepLinkPolicy.resolveOrigin(
                browsable = false,
                selfPackage = selfPackage,
                referrer = "android-app://com.other.app",
                callingPackage = "com.other.app"
            )
        )

        // Every import — self-originated included — needs explicit confirmation.
        for (origin in DeepLinkPolicy.IntentOrigin.entries) {
            assertTrue(DeepLinkPolicy.requiresImportConfirmation(origin))
        }
        assertFalse(DeepLinkPolicy.isControlVerb("import"))
        assertFalse(DeepLinkPolicy.isControlVerb("add"))
        assertTrue(DeepLinkPolicy.isControlVerb("start"))
        assertTrue(DeepLinkPolicy.isControlVerb("toggle"))
        assertTrue(DeepLinkPolicy.isControlVerb("STOP")) // case-insensitive verb match
    }
}
