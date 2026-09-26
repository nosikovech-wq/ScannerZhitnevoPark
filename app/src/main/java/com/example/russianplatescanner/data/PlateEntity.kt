package com.example.russianplatescanner.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "plates")
data class PlateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val number: String,
    val photoPath: String,
    val timestamp: Long = System.currentTimeMillis(),
    val note: String? = null,
    val unauthorizedExit: Boolean = false
)
