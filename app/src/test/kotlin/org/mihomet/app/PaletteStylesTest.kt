package org.mihomet.app

import org.junit.Assert.*
import org.junit.Test
import org.mihomet.app.data.preferences.PaletteStyles

class PaletteStylesTest {

    @Test
    fun `known styles pass through`() {
        PaletteStyles.ALL.forEach { style ->
            assertEquals(style, PaletteStyles.sanitize(style))
        }
    }

    @Test
    fun `unknown garbage falls back to default`() {
        assertEquals(PaletteStyles.DEFAULT, PaletteStyles.sanitize(null))
        assertEquals(PaletteStyles.DEFAULT, PaletteStyles.sanitize(""))
        assertEquals(PaletteStyles.DEFAULT, PaletteStyles.sanitize("vibrant")) // case-sensitive enum names
        assertEquals(PaletteStyles.DEFAULT, PaletteStyles.sanitize("DROP TABLE; --"))
    }

    @Test
    fun `default is a valid member of ALL`() {
        assertTrue(PaletteStyles.DEFAULT in PaletteStyles.ALL)
    }
}
