package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PromptTemplateDao {

    @Query("SELECT * FROM prompt_templates ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<PromptTemplateEntity>>

    @Query("SELECT COUNT(*) FROM prompt_templates")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PromptTemplateEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<PromptTemplateEntity>)

    @Update
    suspend fun update(entity: PromptTemplateEntity)

    @Query("DELETE FROM prompt_templates WHERE id = :id")
    suspend fun deleteById(id: Long)
}
