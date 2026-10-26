package org.mihomet.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test
import org.mihomet.app.ui.theme.*

class ThemeStrictTest {

    @Test
    fun `test amoled scheme background is pure pitch black for OLED`() {
        assertEquals("AMOLED background must be pure black #000000", Color(0xFF000000), AmoledBackground)
        assertEquals("AMOLED surfaceContainerLowest must be pure black #000000", Color(0xFF000000), AmoledSurfaceContainerLowest)
        assertEquals(0f, AmoledBackground.luminance(), 0.0001f)
        assertEquals(0f, AmoledSurfaceContainerLowest.luminance(), 0.0001f)
    }

    @Test
    fun `test amoled scheme has zero purple contamination`() {
        // Assert that the old default purple (0xFFC4B5FD) is strictly eliminated from AMOLED
        assertNotEquals("AMOLED primary must not be default purple", PrimaryDark, ExpressiveAmoledColorScheme.primary)
        assertNotEquals("AMOLED primaryContainer must not be purple", PrimaryContainerDark, ExpressiveAmoledColorScheme.primaryContainer)
        assertNotEquals("AMOLED onPrimaryContainer must not be purple", OnPrimaryContainerDark, ExpressiveAmoledColorScheme.onPrimaryContainer)

        // Verify that AMOLED primary is crisp high-contrast white
        assertEquals(AmoledPrimary, ExpressiveAmoledColorScheme.primary)
        assertEquals(Color(0xFFFFFFFF), ExpressiveAmoledColorScheme.primary)
        assertEquals(AmoledOnPrimary, ExpressiveAmoledColorScheme.onPrimary)
        assertEquals(Color(0xFF000000), ExpressiveAmoledColorScheme.onPrimary)

        // Verify container is neutral dark carbon
        assertEquals(AmoledPrimaryContainer, ExpressiveAmoledColorScheme.primaryContainer)
        assertEquals(Color(0xFF27272A), ExpressiveAmoledColorScheme.primaryContainer)
    }

    @Test
    fun `test amoled surface containers are strictly neutral carbon with zero hue skew`() {
        val containers = listOf(
            "AmoledSurface" to AmoledSurface,
            "AmoledSurfaceContainerLow" to AmoledSurfaceContainerLow,
            "AmoledSurfaceContainer" to AmoledSurfaceContainer,
            "AmoledSurfaceContainerHigh" to AmoledSurfaceContainerHigh,
            "AmoledSurfaceContainerHighest" to AmoledSurfaceContainerHighest,
            "AmoledOutline" to AmoledOutline,
            "AmoledOutlineVariant" to AmoledOutlineVariant
        )

        for ((name, color) in containers) {
            val r = (color.red * 255).toInt()
            val g = (color.green * 255).toInt()
            val b = (color.blue * 255).toInt()
            assertEquals("$name has red != green (hue skew)", r, g)
            assertEquals("$name has green != blue (purple/blue skew)", g, b)
        }
    }

    @Test
    fun `test amoled containers strict luminance ordering`() {
        val lowest = ExpressiveAmoledColorScheme.surfaceContainerLowest.luminance()
        val low = ExpressiveAmoledColorScheme.surfaceContainerLow.luminance()
        val base = ExpressiveAmoledColorScheme.surfaceContainer.luminance()
        val high = ExpressiveAmoledColorScheme.surfaceContainerHigh.luminance()
        val highest = ExpressiveAmoledColorScheme.surfaceContainerHighest.luminance()

        assertTrue("Lowest <= Low", lowest <= low)
        assertTrue("Low < Base", low < base)
        assertTrue("Base < High", base < high)
        assertTrue("High < Highest", high < highest)
    }
}
