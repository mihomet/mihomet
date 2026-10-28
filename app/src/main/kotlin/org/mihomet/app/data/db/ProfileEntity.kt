package org.mihomet.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ProfileType {
    REMOTE,
    LOCAL
}

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val type: ProfileType,
    val url: String = "",
    val filePath: String,
    val customHeaders: String = "{}",
    val lastUpdated: Long = System.currentTimeMillis(),
    val isSelected: Boolean = false,
    val autoUpdateMinutes: Int = 0,
    // Panel-recommended auto-update interval in hours (`profile-update-interval` header);
    // 0 = panel did not provide one (background auto-refresh disabled).
    val updateIntervalHours: Int = 0,
    // Subscription-Userinfo quota (bytes); 0 = unknown/unlimited
    val uploadUsed: Long = 0,
    val downloadUsed: Long = 0,
    val trafficTotal: Long = 0,
    // Expiration as epoch millis; 0 = unknown/no expiration
    val expireAt: Long = 0,
    // Per-profile YAML override patch: a small user-authored YAML fragment deep-merged
    // into the runtime config BEFORE the mandatory app-managed keys (ports, allow-lan,
    // DNS, external-controller...) are written, so security/port settings always win.
    // Empty string = no patch.
    val overridePatch: String = ""
)
