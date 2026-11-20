package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UsageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(items: List<UsageMessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(item: UsageMessageEntity)

    @Query(
        """
        SELECT * FROM usage_messages
        WHERE (:serverProfileId <= 0 OR serverProfileId = :serverProfileId)
          AND (:workspacePath = '' OR workspacePath = :workspacePath)
          AND timestampMillis >= :sinceMillis
        ORDER BY timestampMillis DESC
        """,
    )
    fun observeRecords(
        serverProfileId: Long,
        workspacePath: String,
        sinceMillis: Long,
    ): Flow<List<UsageMessageEntity>>

    @Query(
        """
        SELECT * FROM usage_messages
        WHERE (:serverProfileId <= 0 OR serverProfileId = :serverProfileId)
          AND (:workspacePath = '' OR workspacePath = :workspacePath)
        ORDER BY timestampMillis DESC
        """,
    )
    suspend fun getRecordsOnce(
        serverProfileId: Long,
        workspacePath: String,
    ): List<UsageMessageEntity>

    @Query("DELETE FROM usage_messages WHERE serverProfileId = :serverProfileId")
    suspend fun clearForServer(serverProfileId: Long)
}

@Dao
interface ModelPricingDao {
    @Query("SELECT * FROM model_pricing ORDER BY providerId ASC, modelId ASC")
    fun observeAll(): Flow<List<ModelPricingEntity>>

    @Query("SELECT * FROM model_pricing WHERE providerId = :providerId")
    suspend fun getForProvider(providerId: String): List<ModelPricingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ModelPricingEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ModelPricingEntity)

    @Query("DELETE FROM model_pricing WHERE providerId = :providerId")
    suspend fun deleteForProvider(providerId: String)
}

@Dao
interface ManualBalanceDao {
    @Query("SELECT * FROM manual_balance WHERE scopeKey = :scopeKey LIMIT 1")
    fun observeByScope(scopeKey: String): Flow<ManualBalanceEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ManualBalanceEntity)

    @Query("DELETE FROM manual_balance WHERE scopeKey = :scopeKey")
    suspend fun deleteByScope(scopeKey: String)
}
