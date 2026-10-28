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
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val encrypted = mutableListOf<Triple<Long, String, String>>()
                db.query("SELECT id, url, customHeaders FROM profiles").use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow("id")
                    val urlIdx = cursor.getColumnIndexOrThrow("url")
                    val headersIdx = cursor.getColumnIndexOrThrow("customHeaders")
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idIdx)
                        val url = SecretsCipher.encryptIfSecret(cursor.getString(urlIdx) ?: "")
                        val headers = SecretsCipher.encryptIfSecret(cursor.getString(headersIdx) ?: "")
                        encrypted.add(Triple(id, url, headers))
                    }
                }
                for ((id, url, headers) in encrypted) {
                    db.execSQL(
                        "UPDATE profiles SET url = ?, customHeaders = ? WHERE id = ?",
                        arrayOf(url, headers, id)
                    )
                }
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
    }
}
