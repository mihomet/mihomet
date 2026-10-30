package org.mihomet.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.core.shouldCopyBundledCore

/**
 * Strict tests for the pure bootstrap decision helper [shouldCopyBundledCore].
 *
 * Contract (audit P2 §8): a core installed via the in-app updater must never be
 * rolled back to the bundled binary on the next start, while legacy installs
 * (no version metadata) and same-version installs keep the old size-based
 * comparison. The caller only invokes the helper when an installed binary
 * exists, but the function also guards the first-bootstrap case itself.
 */
class CoreManagerLogicStrictTest {

    private val bundled = "v1.19.33-mihomet.1"
    private val updated = "v1.19.13"
    private val bundledSize = 57_765_448L
    private val updatedSize = 54_000_000L

    @Test
    fun `first bootstrap with no installed binary always copies`() {
        // installedSize <= 0 means there is nothing usable installed: the bundled
        // binary is the only guaranteed-working core, regardless of metadata.
        assertTrue(shouldCopyBundledCore(installedVersion = null, bundledVersion = bundled, installedSize = 0L, bundledSize = bundledSize))
        assertTrue(shouldCopyBundledCore(installedVersion = updated, bundledVersion = bundled, installedSize = 0L, bundledSize = bundledSize))
        assertTrue(shouldCopyBundledCore(installedVersion = updated, bundledVersion = bundled, installedSize = -1L, bundledSize = bundledSize))
    }

    @Test
    fun `no version metadata falls back to legacy size comparison`() {
        // Legacy install (never updated through the app): sizes differ → copy,
        // so a bundled core update still propagates to root mode.
        assertTrue(shouldCopyBundledCore(null, bundled, installedSize = updatedSize, bundledSize = bundledSize))
        // Same size → nothing to do.
        assertFalse(shouldCopyBundledCore(null, bundled, installedSize = bundledSize, bundledSize = bundledSize))
    }

    @Test
    fun `same installed version keeps the size comparison`() {
        // Same core version but a different size: the installed copy is corrupted
        // or partial → re-copy the bundled binary.
        assertTrue(shouldCopyBundledCore(bundled, bundled, installedSize = updatedSize, bundledSize = bundledSize))
        // Same version and same size → up to date.
        assertFalse(shouldCopyBundledCore(bundled, bundled, installedSize = bundledSize, bundledSize = bundledSize))
    }

    @Test
    fun `updated core is never rolled back even when sizes differ`() {
        // THE fix: the user installed another core via downloadAndUpdateCore; the
        // bundled binary has a different size and must NOT overwrite it.
        assertFalse(shouldCopyBundledCore(updated, bundled, installedSize = updatedSize, bundledSize = bundledSize))
        assertFalse(shouldCopyBundledCore(updated, bundled, installedSize = bundledSize, bundledSize = bundledSize))
    }

    @Test
    fun `unknown bundled version falls back to legacy size comparison`() {
        // The bundled binary could not report its own version: version comparison
        // is impossible, so behave like a legacy install.
        assertTrue(shouldCopyBundledCore(updated, null, installedSize = updatedSize, bundledSize = bundledSize))
        assertFalse(shouldCopyBundledCore(updated, null, installedSize = bundledSize, bundledSize = bundledSize))
    }
}
