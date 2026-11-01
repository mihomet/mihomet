package org.mihomet.app

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mihomet.app.data.crypto.SecretsCipher
import org.mihomet.app.data.db.AppDatabase

/**
 * On-device Room migration tests (audit P1.3). Runs the REAL exported schemas
 * (app/schemas exposed as androidTest assets) against the REAL migration code,
 * including the 4→5 at-rest encryption migration that touches the Android
 * Keystore — a JVM test cannot prove that path.
 */
@RunWith(AndroidJUnit4::class)
class RoomMigrationInstrumentedTest {

    private val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    private fun insertV4Row(db: androidx.sqlite.db.SupportSQLiteDatabase, id: Long, url: String, headers: String) {
        db.execSQL(
            """INSERT INTO profiles
               (id, name, type, url, filePath, customHeaders, lastUpdated, isSelected,
                autoUpdateMinutes, updateIntervalHours, uploadUsed, downloadUsed,
                trafficTotal, expireAt, overridePatch)
               VALUES (?, 'MigrationSmoke', 'REMOTE', ?, '', ?, 0, 1, 0, 0, 0, 0, 0, 0, '')""",
            arrayOf(id, url, headers)
        )
    }

    private fun queryStrings(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): List<String> =
        mutableListOf<String>().also { out ->
            db.query(sql).use { c ->
                while (c.moveToNext()) out.add(c.getString(0))
            }
        }

    @Test
    fun migration4to5EncryptsUrlAndHeaders() {
        val secretUrl = "https://panel.example/sub?token=TOP-SECRET-TOKEN"
        val secretHeader = "Authorization: Bearer panel-api-token"

        helper.createDatabase("enc-mig-4-5", 4).use { created ->
            insertV4Row(created, 1L, secretUrl, secretHeader)
            insertV4Row(created, 2L, "", "")
        }

        val migrated = helper.runMigrationsAndValidate(
            "enc-mig-4-5",
            5,
            true,
            AppDatabase.MIGRATION_4_5
        )
        migrated.use { db ->
            val urls = queryStrings(db, "SELECT url FROM profiles ORDER BY id")
            val headers = queryStrings(db, "SELECT customHeaders FROM profiles ORDER BY id")
            assertEquals(2, urls.size)

            // Non-blank secrets are now marker-prefixed and decrypt to the original…
            assertTrue(urls[0].startsWith(SecretsCipher.MARKER))
            assertEquals(secretUrl, SecretsCipher.decrypt(urls[0]))
            assertTrue(headers[0].startsWith(SecretsCipher.MARKER))
            assertEquals(secretHeader, SecretsCipher.decrypt(headers[0]))

            // …while blank values pass through unchanged (idempotent rule).
            assertEquals("", urls[1])
            assertEquals("", headers[1])
        }
    }

    @Test
    fun migration2toCurrentKeepsDataIntact() {
        helper.createDatabase("enc-mig-2-current", 2).use { created ->
            created.execSQL(
                """INSERT INTO profiles
                   (id, name, type, url, filePath, customHeaders, lastUpdated, isSelected,
                    autoUpdateMinutes, uploadUsed, downloadUsed, trafficTotal, expireAt)
                   VALUES (7, 'OldProfile', 'REMOTE', 'https://old.example/sub?key=k',
                           '', '', 0, 1, 0, 0, 0, 0, 0)"""
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            "enc-mig-2-current",
            5,
            true,
            *AppDatabase.MIGRATIONS
        )
        migrated.use { db ->
            val names = queryStrings(db, "SELECT name FROM profiles")
            assertEquals(listOf("OldProfile"), names)

            // v4→v5 ran on the way up: the pre-existing url is now encrypted.
            val urls = queryStrings(db, "SELECT url FROM profiles")
            assertTrue(urls[0].startsWith(SecretsCipher.MARKER))
            assertEquals("https://old.example/sub?key=k", SecretsCipher.decrypt(urls[0]))

            // Columns added by 1→2 / 2→3 / 3→4 all exist with their defaults.
            val patch = queryStrings(db, "SELECT overridePatch FROM profiles")
            assertEquals(listOf(""), patch)
            val interval = queryStrings(db, "SELECT updateIntervalHours FROM profiles")
            assertEquals(listOf("0"), interval)
        }
    }

    /** The current schema opens clean on-device (no migration needed). */
    @Test
    fun currentVersionOpensAndSupportsWrites() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.profileDao()
            kotlinx.coroutines.runBlocking {
                dao.insertProfile(
                    org.mihomet.app.data.db.ProfileEntity(
                        name = "Smoke",
                        type = org.mihomet.app.data.db.ProfileType.REMOTE,
                        url = "https://s.example/sub",
                        filePath = ""
                    )
                )
            }
            val all = kotlinx.coroutines.runBlocking { dao.getAllProfilesOnce() }
            assertEquals(1, all.size)
            assertEquals("Smoke", all[0].name)
        } finally {
            db.close()
        }
    }
}
