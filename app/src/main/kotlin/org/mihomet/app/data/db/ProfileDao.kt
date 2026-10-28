package org.mihomet.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY id DESC")
    fun getAllProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE isSelected = 1 LIMIT 1")
    fun getSelectedProfileFlow(): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE isSelected = 1 LIMIT 1")
    suspend fun getSelectedProfile(): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE id = :id LIMIT 1")
    suspend fun getProfileById(id: Long): ProfileEntity?

    /** One-shot list of all profiles (background worker / non-reactive callers). */
    @Query("SELECT * FROM profiles")
    suspend fun getAllProfilesOnce(): List<ProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: ProfileEntity): Long

    @Update
    suspend fun updateProfile(profile: ProfileEntity)

    @Delete
    suspend fun deleteProfile(profile: ProfileEntity)

    @Query("UPDATE profiles SET isSelected = 0")
    suspend fun clearSelection()

    @Transaction
    suspend fun selectProfile(id: Long) {
        clearSelection()
        setSelected(id)
    }

    @Query("UPDATE profiles SET isSelected = 1 WHERE id = :id")
    suspend fun setSelected(id: Long)
}
