package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards EN/RU locale parity: every key present in the default (English) strings.xml must exist
 * in values-ru and vice versa. Catches the classic failure where a string is added to one locale
 * only and Russian text leaks into the English UI (or vice versa) at runtime.
 */
class LocaleParityTest {

    private val regex = Regex("""<string name="([^"]+)""")

    private fun resDir(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(5) {
            val candidate = File(dir, "app/src/main/res")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("app/src/main/res not found from working dir")
    }

    private fun keys(localeDir: String): Set<String> =
        regex.findAll(File(resDir(), "$localeDir/strings.xml").readText())
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun `every string key exists in both locales`() {
        val en = keys("values")
        val ru = keys("values-ru")
        assertTrue("default strings.xml parsed to nothing — regex or path broken", en.isNotEmpty())
        assertEquals("locale key drift — add missing keys to BOTH locales", en, ru)
    }

    @Test
    fun `no untranslated TODO placeholders`() {
        val ru = File(resDir(), "values-ru/strings.xml").readText()
        assertTrue(
            "values-ru/strings.xml contains untranslated placeholder markers",
            !ru.contains("TRANSLATE") && !ru.contains("TODO")
        )
    }
}
