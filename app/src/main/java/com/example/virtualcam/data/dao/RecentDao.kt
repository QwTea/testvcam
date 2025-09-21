package com.example.virtualcam.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.virtualcam.data.entity.RecentItem
import kotlinx.coroutines.flow.Flow

// GREP: ROOM_DAO_RECENT
@Dao
interface RecentDao {
    @Query("SELECT * FROM recent_items ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<RecentItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: RecentItem)

    @Query("DELETE FROM recent_items")
    suspend fun clear()
}
