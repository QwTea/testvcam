package com.example.virtualcam.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

// GREP: ROOM_ENTITY_RECENT
@Entity(tableName = "recent_items")
data class RecentItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val type: String,
    val addedAt: Long
)
