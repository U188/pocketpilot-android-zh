package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface KnownHostDao {
    @Query("SELECT * FROM known_hosts ORDER BY lastVerifiedAt DESC")
    fun observeAll(): Flow<List<KnownHostEntity>>

    @Query("SELECT * FROM known_hosts WHERE hostPortKey = :hostPortKey LIMIT 1")
    suspend fun getByHostPort(hostPortKey: String): KnownHostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: KnownHostEntity)

    @Query("UPDATE known_hosts SET lastVerifiedAt = :timestamp WHERE hostPortKey = :hostPortKey")
    suspend fun updateLastVerified(hostPortKey: String, timestamp: Long)

    @Query("DELETE FROM known_hosts WHERE hostPortKey = :hostPortKey")
    suspend fun delete(hostPortKey: String)
}
