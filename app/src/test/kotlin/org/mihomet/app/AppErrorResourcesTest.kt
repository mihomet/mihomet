package org.mihomet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mihomet.app.core.AppErrorCode
import org.mihomet.app.core.stringRes
import java.io.File

/**
 * Guards the typed error-code → resource mapping (audit P2.9 tail): every
 * [AppErrorCode] must (a) carry a `resKeyName` that exists in BOTH locales'
 * strings.xml and (b) resolve through [stringRes] to the same-named resource.
 * Catches a code added without its strings, or a mapping drifting from its key.
 */
class AppErrorResourcesTest {

    private fun resDir(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(5) {
            val candidate = File(dir, "app/src/main/res")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("app/src/main/res not found from working dir")
    }

    private fun keySet(localeDir: String): Set<String> =
        Regex("""<string name="([^"]+)"""").findAll(File(resDir(), "$localeDir/strings.xml").readText())
            .map { it.groupValues[1] }
            .toSet()

    private fun stringResIdsByName(): Map<String, Int> =
        org.mihomet.app.R.string::class.java.fields
            .associate { it.name to (it.get(null) as Int) }

    @Test
    fun `every error code has a key in both locales`() {
        val en = keySet("values")
        val ru = keySet("values-ru")
        for (code in AppErrorCode.entries) {
            assertTrue(
                "${code.name} resKeyName '${code.resKeyName}' missing in values/strings.xml",
                code.resKeyName in en
            )
            assertTrue(
                "${code.name} resKeyName '${code.resKeyName}' missing in values-ru/strings.xml",
                code.resKeyName in ru
            )
        }
    }

    @Test
    fun `stringRes mapping matches the declared key names`() {
        val idsByName = stringResIdsByName()
        for (code in AppErrorCode.entries) {
            val expected = idsByName[code.resKeyName]
            assertTrue(
                "R.string has no field named '${code.resKeyName}' for ${code.name}",
                expected != null
            )
            assertEquals(
                "${code.name} stringRes() does not point at '${code.resKeyName}'",
                expected,
                code.stringRes()
            )
        }
    }
}
