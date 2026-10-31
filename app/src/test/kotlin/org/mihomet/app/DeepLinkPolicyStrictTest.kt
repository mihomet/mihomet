package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.core.DeepLinkPolicy

/**
 * Strict tests for the deep-link accept/reject policy (audit P0 §1.1).
 *
 * Contracts pinned here:
 *  - control links (start/stop/toggle) are NEVER executable from a browser
 *    (BROWSABLE) or a third-party app — only from this app's own PendingIntents;
 *  - imports require the confirmation dialog state for EVERY origin — there is
 *    no silent-import path;
 *  - origin resolution fails closed: without proof of SELF, an intent is
 *    treated as external.
 */
class DeepLinkPolicyStrictTest {

    private val self = "org.mihomet.app"

    // ---------------------------------------------------------------------
    // Origin resolution
    // ---------------------------------------------------------------------

    @Test
    fun `browser link is classified BROWSABLE even with a web referrer`() {
        assertEquals(
            DeepLinkPolicy.IntentOrigin.BROWSABLE,
            DeepLinkPolicy.resolveOrigin(
                browsable = true,
                selfPackage = self,
                referrer = "https://evil.example/sub",
                callingPackage = null
            )
        )
    }

    @Test
    fun `own widget pendingintent referrer resolves to SELF`() {
        assertEquals(
            DeepLinkPolicy.IntentOrigin.SELF,
            DeepLinkPolicy.resolveOrigin(
                browsable = false,
                selfPackage = self,
                referrer = "android-app://org.mihomet.app",
                callingPackage = null
            )
        )
        assertEquals(
            DeepLinkPolicy.IntentOrigin.SELF,
            DeepLinkPolicy.resolveOrigin(
                browsable = false,
                selfPackage = self,
                referrer = null,
                callingPackage = self
            )
        )
    }

    @Test
    fun `another app launching explicitly is EXTERNAL_APP`() {
        assertEquals(
            DeepLinkPolicy.IntentOrigin.EXTERNAL_APP,
            DeepLinkPolicy.resolveOrigin(
                browsable = false,
                selfPackage = self,
                referrer = "android-app://com.evil.panel",
                callingPackage = null
            )
        )
    }

    @Test
    fun `origin resolution fails closed without any evidence`() {
        assertEquals(
            DeepLinkPolicy.IntentOrigin.EXTERNAL_APP,
            DeepLinkPolicy.resolveOrigin(false, self, null, null)
        )
        // Missing self package name: even a "self" referrer cannot be proven.
        assertEquals(
            DeepLinkPolicy.IntentOrigin.EXTERNAL_APP,
            DeepLinkPolicy.resolveOrigin(false, "", "android-app://", null)
        )
    }

    // ---------------------------------------------------------------------
    // Control links: browser-originated toggle rejected, own toggle accepted
    // ---------------------------------------------------------------------

    @Test
    fun `browser-originated toggle is rejected`() {
        val origin = DeepLinkPolicy.resolveOrigin(true, self, "https://evil.example", null)
        assertEquals(
            DeepLinkPolicy.ControlDecision.REJECT,
            DeepLinkPolicy.decideControl(origin)
        )
    }

    @Test
    fun `third-party app toggle is rejected`() {
        val origin = DeepLinkPolicy.resolveOrigin(false, self, "android-app://com.evil.panel", null)
        assertEquals(
            DeepLinkPolicy.ControlDecision.REJECT,
            DeepLinkPolicy.decideControl(origin)
        )
    }

    @Test
    fun `own-widget toggle is accepted`() {
        val origin = DeepLinkPolicy.resolveOrigin(false, self, "android-app://org.mihomet.app", null)
        assertEquals(
            DeepLinkPolicy.ControlDecision.ALLOW,
            DeepLinkPolicy.decideControl(origin)
        )
    }

    @Test
    fun `every control verb is recognized case-insensitively`() {
        for (verb in DeepLinkPolicy.CONTROL_VERBS) {
            assertTrue(DeepLinkPolicy.isControlVerb(verb))
            assertTrue(DeepLinkPolicy.isControlVerb(verb.uppercase()))
        }
        assertFalse(DeepLinkPolicy.isControlVerb("add"))
        assertFalse(DeepLinkPolicy.isControlVerb("onadd"))
        assertFalse(DeepLinkPolicy.isControlVerb("autoroutingadd"))
        assertFalse(DeepLinkPolicy.isControlVerb(""))
    }

    // ---------------------------------------------------------------------
    // Imports: confirmation is unconditional
    // ---------------------------------------------------------------------

    @Test
    fun `import always requires confirmation for every origin`() {
        for (origin in DeepLinkPolicy.IntentOrigin.entries) {
            assertTrue(
                "import from $origin must require the confirmation dialog",
                DeepLinkPolicy.requiresImportConfirmation(origin)
            )
        }
    }
}
