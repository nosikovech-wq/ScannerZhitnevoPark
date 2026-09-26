package com.example.russianplatescanner.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(plate: PlateEntity): Long

    @Delete
    suspend fun delete(plate: PlateEntity)

    @Query("SELECT * FROM plates ORDER BY timestamp DESC")
    fun getAll(): Flow<List<PlateEntity>>

    @Query("SELECT * FROM plates WHERE number LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun search(query: String): Flow<List<PlateEntity>>

    @Query("SELECT * FROM plates WHERE id = :id")
    suspend fun getById(id: Long): PlateEntity?

    @Query("SELECT * FROM plates WHERE timestamp >= :since")
    suspend fun recordedSince(since: Long): List<PlateEntity>

    @Query("SELECT photoPath FROM plates")
    suspend fun allPhotoPaths(): List<String>

    @Query("DELETE FROM plates")
    suspend fun deleteAll()
}
