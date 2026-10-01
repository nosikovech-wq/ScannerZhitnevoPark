package com.example.russianplatescanner.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "plates")
data class PlateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val number: String,
    val photoPath: String,
    val timestamp: Long = System.currentTimeMillis(),
    val note: String? = null,
    val unauthorizedExit: Boolean = false,
    val uploaded: Boolean = false,
    val uid: String = UUID.randomUUID().toString()
) {
    fun recordKey(): String = uid.ifBlank { id.toString() }
}
