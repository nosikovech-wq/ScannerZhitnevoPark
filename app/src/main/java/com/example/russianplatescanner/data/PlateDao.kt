package com.example.russianplatescanner.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(plate: PlateEntity): Long

    @Delete
    suspend fun delete(plate: PlateEntity)

    @Update
    suspend fun update(plate: PlateEntity)

    @Query("SELECT * FROM plates ORDER BY timestamp DESC")
    fun getAll(): Flow<List<PlateEntity>>

    @Query("SELECT * FROM plates WHERE number LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun search(query: String): Flow<List<PlateEntity>>

    @Query("SELECT * FROM plates WHERE id = :id")
    suspend fun getById(id: Long): PlateEntity?

    @Query("SELECT * FROM plates WHERE timestamp >= :since")
    suspend fun recordedSince(since: Long): List<PlateEntity>

    @Query("SELECT * FROM plates WHERE uid = :uid LIMIT 1")
    suspend fun findByUid(uid: String): PlateEntity?

    @Query("SELECT * FROM plates WHERE pendingSync = 1")
    suspend fun pending(): List<PlateEntity>

    @Query("UPDATE plates SET pendingSync = 0 WHERE uid = :uid")
    suspend fun markSynced(uid: String)

    @Query("SELECT uid FROM plates")
    suspend fun allUids(): List<String>

    @Query("SELECT photoPath FROM plates")
    suspend fun allPhotoPaths(): List<String>

    @Query("DELETE FROM plates")
    suspend fun deleteAll()

    @Query("UPDATE plates SET uploaded = 1 WHERE uid IN (:uids)")
    suspend fun markUploaded(uids: List<String>)

    @Query("UPDATE plates SET uploaded = 0 WHERE uid IN (:uids)")
    suspend fun markNotUploaded(uids: List<String>)
}
