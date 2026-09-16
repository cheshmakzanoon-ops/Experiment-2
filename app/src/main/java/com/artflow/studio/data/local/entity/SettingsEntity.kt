package com.artflow.studio.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing user settings and preferences
 */
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey
    val key: String,
    val value: String,
    val category: String = "general",
    val lastModified: Long = System.currentTimeMillis(),
)
