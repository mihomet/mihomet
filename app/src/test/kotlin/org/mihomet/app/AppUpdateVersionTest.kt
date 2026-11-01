package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.data.update.AppVersion

/**
 * Pure-logic tests for the in-app update semver comparison (audit P1.4).
 * Contract: strictly-greater tags only, `v` prefix tolerated, pre-release
 * suffixes ordered per semver 2.0.0, build metadata ignored, unparseable
 * input never triggers an update (fail-closed).
 */
class AppUpdateVersionTest {

    // ------------------------------------------------------------------
    // Task-mandated cases
    // ------------------------------------------------------------------

    @Test
    fun `1_2_10 is greater than 1_2_9`() {
        assertTrue(AppVersion.isUpdateAvailable("1.2.9", "v1.2.10"))
        assertFalse(AppVersion.isUpdateAvailable("1.2.10", "v1.2.9"))
    }

    @Test
    fun `1_3_0 is greater than 1_2_9`() {
        assertTrue(AppVersion.isUpdateAvailable("1.2.9", "v1.3.0"))
        assertFalse(AppVersion.isUpdateAvailable("1.3.0", "v1.2.9"))
    }

    @Test
    fun `equal versions mean no update regardless of v prefix`() {
        assertFalse(AppVersion.isUpdateAvailable("1.2.1", "v1.2.1"))
        assertFalse(AppVersion.isUpdateAvailable("1.2.1", "1.2.1"))
    }

    @Test
    fun `older tag means no update`() {
        assertFalse(AppVersion.isUpdateAvailable("1.2.1", "v1.2.0"))
        assertFalse(AppVersion.isUpdateAvailable("1.2.1", "v1.1.99"))
        assertFalse(AppVersion.isUpdateAvailable("2.0.0", "v1.99.99"))
    }

    // ------------------------------------------------------------------
    // Pre-release and build metadata (tolerated, semver-ordered)
    // ------------------------------------------------------------------

    @Test
    fun `pre-release of the same triple is OLDER than the release`() {
        assertFalse(AppVersion.isUpdateAvailable("1.3.0", "v1.3.0-rc1"))
        // ...but a pre-release tag IS an update over the previous release
        assertTrue(AppVersion.isUpdateAvailable("1.2.9", "v1.3.0-rc1"))
    }

    @Test
    fun `pre-release suffixes compare per semver identifier rules`() {
        assertTrue(AppVersion.isUpdateAvailable("1.2.9-rc1", "1.2.9-rc2"))
        assertFalse(AppVersion.isUpdateAvailable("1.2.9-rc2", "1.2.9-rc1"))
        assertTrue(AppVersion.isUpdateAvailable("1.2.9-alpha", "1.2.9-beta"))
        // numeric identifiers are lower-precedence than alphanumeric
        assertTrue(AppVersion.isUpdateAvailable("1.2.9-1", "1.2.9-alpha"))
        // more fields win when the common prefix is equal
        assertTrue(AppVersion.isUpdateAvailable("1.2.9-rc", "1.2.9-rc.1"))
    }

    @Test
    fun `build metadata is ignored`() {
        assertFalse(AppVersion.isUpdateAvailable("1.2.9", "1.2.9+build.5"))
        assertFalse(AppVersion.isUpdateAvailable("1.2.9+build.4", "1.2.9+build.5"))
        assertTrue(AppVersion.isUpdateAvailable("1.2.9", "1.3.0+build.5"))
    }

    // ------------------------------------------------------------------
    // Parsing tolerance and fail-closed behavior
    // ------------------------------------------------------------------

    @Test
    fun `v and V prefixes are tolerated on both sides`() {
        assertTrue(AppVersion.isUpdateAvailable("v1.2.1", "V1.2.2"))
        assertEquals(AppVersion.SemVer(1, 2, 3), AppVersion.parse("v1.2.3"))
        assertEquals(AppVersion.SemVer(1, 2, 3, "rc.1"), AppVersion.parse("v1.2.3-rc.1"))
    }

    @Test
    fun `unparseable input never triggers an update`() {
        assertNull(AppVersion.parse(null))
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("   "))
        assertNull(AppVersion.parse("1.19.33 (Bundled)"))   // decorated core-style version
        assertNull(AppVersion.parse("1.2"))                 // missing patch
        assertNull(AppVersion.parse("1.2.3.4"))             // four parts
        assertNull(AppVersion.parse("abc"))                 // garbage
        assertNull(AppVersion.parse("v"))                   // prefix only

        assertFalse(AppVersion.isUpdateAvailable("1.2.1", null))
        assertFalse(AppVersion.isUpdateAvailable(null, "v1.2.2"))
        assertFalse(AppVersion.isUpdateAvailable("garbage", "v1.2.2"))
        assertFalse(AppVersion.isUpdateAvailable("1.2.1", "garbage"))
    }

    @Test
    fun `parse is anchored and case-consistent`() {
        // a decorated string with a valid triple in the middle must NOT parse
        assertNull(AppVersion.parse("core-v1.19.33-mihomet.1"))
        // three-digit components parse as numbers, not lexicographically
        assertTrue(AppVersion.isUpdateAvailable("1.2.9", "1.10.0"))
        assertTrue(AppVersion.isUpdateAvailable("1.9.9", "1.10.0"))
        assertFalse(AppVersion.isUpdateAvailable("1.10.0", "1.9.9"))
    }
}
