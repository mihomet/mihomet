package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.core.OemHints
import org.mihomet.app.core.OemHints.OemBrand

/**
 * Strict tests for [OemHints] — OEM brand detection and vendor deep links.
 *
 * [OemHints.detect] is a pure (manufacturer, brand) -> [OemBrand] function, so
 * the whole matching table is verified on the JVM with mock device strings.
 * Intent resolvability needs a real PackageManager; per the null-safe contract
 * only the early-return branches (STOCK brand, missing context) are covered
 * here — the resolveActivity path is validated on-device.
 */
class OemHintsStrictTest {

    // ---- detect(): pure mapping of (manufacturer, brand) -> OemBrand ----

    @Test
    fun `test xiaomi redmi and poco devices map to XIAOMI`() {
        assertEquals(OemBrand.XIAOMI, OemHints.detect("Xiaomi", "MI 9"))
        assertEquals(OemBrand.XIAOMI, OemHints.detect("Xiaomi", "Redmi Note 12"))
        assertEquals(OemBrand.XIAOMI, OemHints.detect("Xiaomi", "POCO X3 Pro"))
        assertEquals(OemBrand.XIAOMI, OemHints.detect("redmi", "23021RAAEG"))
    }

    @Test
    fun `test realme oppo oneplus and oplus map to COLOROS`() {
        assertEquals(OemBrand.COLOROS, OemHints.detect("OPPO", "CPH2451"))
        assertEquals(OemBrand.COLOROS, OemHints.detect("realme", "RMX3771"))
        assertEquals(OemBrand.COLOROS, OemHints.detect("OnePlus", "CPH2551"))
        assertEquals(OemBrand.COLOROS, OemHints.detect("oplus", "CPH2451"))
    }

    @Test
    fun `test samsung maps to SAMSUNG`() {
        assertEquals(OemBrand.SAMSUNG, OemHints.detect("samsung", "SM-G991B"))
        assertEquals(OemBrand.SAMSUNG, OemHints.detect("SAMSUNG", "samsung"))
    }

    @Test
    fun `test huawei and honor map to HUAWEI`() {
        assertEquals(OemBrand.HUAWEI, OemHints.detect("HUAWEI", "ELS-NX9"))
        assertEquals(OemBrand.HUAWEI, OemHints.detect("HONOR", "ANE-LX1"))
    }

    @Test
    fun `test vivo and iqoo map to VIVO`() {
        assertEquals(OemBrand.VIVO, OemHints.detect("vivo", "V2312"))
        assertEquals(OemBrand.VIVO, OemHints.detect("vivo", "iQOO 9"))
    }

    @Test
    fun `test stock vendors map to STOCK`() {
        assertEquals(OemBrand.STOCK, OemHints.detect("google", "Pixel 8"))
        assertEquals(OemBrand.STOCK, OemHints.detect("Motorola", "moto g(60)"))
        assertEquals(OemBrand.STOCK, OemHints.detect("Nokia", "Nokia 8.3"))
        assertEquals(OemBrand.STOCK, OemHints.detect("Nothing", "A063"))
        assertEquals(OemBrand.STOCK, OemHints.detect("HMD", "TA-1600"))
    }

    @Test
    fun `test null and blank device strings fall back to STOCK`() {
        assertEquals(OemBrand.STOCK, OemHints.detect(null, null))
        assertEquals(OemBrand.STOCK, OemHints.detect("", ""))
        assertEquals(OemBrand.STOCK, OemHints.detect(null, "Pixel 8"))
    }

    @Test
    fun `test matching is case-insensitive and containment-based`() {
        assertEquals(OemBrand.XIAOMI, OemHints.detect("XIAOMI", "23021RAAEG"))
        assertEquals(OemBrand.VIVO, OemHints.detect("VIVO", "iqoo"))
    }

    // ---- brand table invariants ----

    @Test
    fun `test every non-stock brand declares prefixes and a localized note`() {
        for (brand in OemBrand.entries) {
            if (brand == OemBrand.STOCK) continue
            assertTrue(
                "$brand must declare manufacturer prefixes for detection",
                brand.manufacturerPrefixes.isNotEmpty()
            )
            assertNotNull("$brand must provide a per-brand note resource", brand.notesRes)
        }
    }

    @Test
    fun `test stock brand has no prefixes and no note`() {
        assertTrue(OemBrand.STOCK.manufacturerPrefixes.isEmpty())
        assertNull(OemBrand.STOCK.notesRes)
    }

    @Test
    fun `test all manufacturer prefixes are lowercase`() {
        for (brand in OemBrand.entries) {
            for (prefix in brand.manufacturerPrefixes) {
                assertEquals(
                    "$brand prefix '$prefix' must be lowercase (detect() lowercases input)",
                    prefix.lowercase(),
                    prefix
                )
            }
        }
    }

    // ---- resolveLaunchable(): only null-safe branches are JVM-testable ----

    @Test
    fun `test resolveLaunchable returns null for STOCK brand without touching context`() {
        assertNull(OemHints.resolveLaunchable(null, OemBrand.STOCK))
    }

    @Test
    fun `test resolveLaunchable returns null when context is unavailable`() {
        assertNull(OemHints.resolveLaunchable(null, OemBrand.XIAOMI))
        assertNull(OemHints.resolveLaunchable(null, OemBrand.COLOROS))
        assertNull(OemHints.resolveLaunchable(null, OemBrand.SAMSUNG))
        assertNull(OemHints.resolveLaunchable(null, OemBrand.HUAWEI))
        assertNull(OemHints.resolveLaunchable(null, OemBrand.VIVO))
    }

    @Test
    fun `test resolveLaunchableOrGeneric degrades safely without context`() {
        assertNull(OemHints.resolveLaunchableOrGeneric(null, OemBrand.XIAOMI))
        assertNull(OemHints.resolveLaunchableOrGeneric(null, OemBrand.STOCK))
    }
}
