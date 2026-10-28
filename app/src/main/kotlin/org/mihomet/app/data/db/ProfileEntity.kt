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
    // Subscription-Userinfo quota (bytes); 0 = unknown/unlimited
    val uploadUsed: Long = 0,
    val downloadUsed: Long = 0,
    val trafficTotal: Long = 0,
    // Expiration as epoch millis; 0 = unknown/no expiration
    val expireAt: Long = 0
)
