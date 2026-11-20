package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerProfileDao {
    @Query("SELECT * FROM server_profiles ORDER BY COALESCE(lastConnectedAt, 0) DESC, createdAt DESC")
    fun observeAll(): Flow<List<ServerProfileEntity>>

    @Query("SELECT * FROM server_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ServerProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ServerProfileEntity): Long

    @Update
    suspend fun update(entity: ServerProfileEntity)

    @Query("UPDATE server_profiles SET lastConnectedAt = :timestamp WHERE id = :id")
    suspend fun updateLastConnectedAt(id: Long, timestamp: Long)

    @Query("DELETE FROM server_profiles WHERE id = :id")
    suspend fun deleteById(id: Long)
}
