package org.mihomet.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ProfileEntity::class], version = 4, exportSchema = true)
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

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    }
}
