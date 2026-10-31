package org.mihomet.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.mihomet.app.data.crypto.SecretsCipher

@Database(entities = [ProfileEntity::class], version = 5, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao

    companion object {
        /**
         * True while a Room migration is executing in this process. The
         * SecretsCipher data-loss gate
         * ([org.mihomet.app.data.preferences.SettingsRepository.hasEncryptedSecrets])
         * skips its Room probe while this flag is set: the probe would run from
         * inside the migration thread and contend on the write connection the
         * migration itself holds, and the encrypting migration (4→5) operates
         * on `url`/`customHeaders` columns that are plaintext by schema — an
         * `enc1:` probe can never contribute there. Set/cleared by
         * [MIGRATION_4_5] (try/finally); not for other callers.
         */
        @Volatile
        var roomMigrationInFlight: Boolean = false

        // v1 -> v2: subscription quota (Subscription-Userinfo header support).
        // Non-destructive: existing profiles keep their data.
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN uploadUsed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiles ADD COLUMN downloadUsed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiles ADD COLUMN trafficTotal INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiles ADD COLUMN expireAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v2 -> v3: panel-recommended auto-update interval (profile-update-interval
        // header, in hours). Non-destructive: existing profiles default to 0, which
        // keeps them out of the background auto-refresh worker.
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN updateIntervalHours INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v3 -> v4: per-profile YAML override patch (deep-merged into the runtime
        // config before the mandatory app-managed keys). Non-destructive: existing
        // profiles default to '' (no patch).
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN overridePatch TEXT NOT NULL DEFAULT ''")
            }
        }

        // v4 -> v5: encrypt secrets at rest. Subscription URLs can embed auth
        // tokens and customHeaders carry panel API tokens, so both columns are
        // re-stored encrypted with the AndroidKeyStore key (SecretsCipher). Blank
        // values and values already carrying the `enc1:` marker are left as-is
        // (idempotent). The Keystore key is generated on first use and is always
        // available when the database is opened on-device.
        //
        // External audit N07-2: [SecretsCipher.encryptIfSecret] now fails closed
        // (SecretEncryptException) when the Keystore key is unavailable while
        // encrypted data exists. A migration must never abort the database open
        // — and these columns were stored PLAINTEXT in v4 already — so on a
        // refusal the value passes through unchanged: the exact status quo, no
        // new exposure, no data loss; the next successful profile refresh
        // re-encrypts it.
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            private fun encryptForMigration(value: String): String =
                try {
                    SecretsCipher.encryptIfSecret(value)
                } catch (e: SecretsCipher.SecretEncryptException) {
                    android.util.Log.e(
                        "AppDatabase",
                        "Migration 4→5: at-rest encryption unavailable; keeping the value as stored in v4 (plaintext)",
                        e
                    )
                    value
                }

            override fun migrate(db: SupportSQLiteDatabase) {
                // The gate's Room probe would contend on the write connection
                // held by this very migration (deadlock until the probe's
                // timeout); see [Companion.roomMigrationInFlight].
                AppDatabase.roomMigrationInFlight = true
                try {
                    val encrypted = mutableListOf<Triple<Long, String, String>>()
                    db.query("SELECT id, url, customHeaders FROM profiles").use { cursor ->
                        val idIdx = cursor.getColumnIndexOrThrow("id")
                        val urlIdx = cursor.getColumnIndexOrThrow("url")
                        val headersIdx = cursor.getColumnIndexOrThrow("customHeaders")
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idIdx)
                            val url = encryptForMigration(cursor.getString(urlIdx) ?: "")
                            val headers = encryptForMigration(cursor.getString(headersIdx) ?: "")
                            encrypted.add(Triple(id, url, headers))
                        }
                    }
                    for ((id, url, headers) in encrypted) {
                        db.execSQL(
                            "UPDATE profiles SET url = ?, customHeaders = ? WHERE id = ?",
                            arrayOf(url, headers, id)
                        )
                    }
                } finally {
                    AppDatabase.roomMigrationInFlight = false
                }
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
    }
}
