package com.example.aquiethours.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
data class Profile(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String,
    val ringerMode: Int, // e.g. AudioManager.RINGER_MODE_SILENT, NORMAL, VIBRATE
    val ringVolume: Int,
    val mediaVolume: Int,
    val silentVolume: Int
)
