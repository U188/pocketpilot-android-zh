package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TerminalSnippetDao {

    @Query("SELECT * FROM terminal_snippets ORDER BY id ASC")
    fun observeAll(): Flow<List<TerminalSnippetEntity>>

    @Query("SELECT COUNT(*) FROM terminal_snippets")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TerminalSnippetEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<TerminalSnippetEntity>)

    @Update
    suspend fun update(entity: TerminalSnippetEntity)

    @Query("DELETE FROM terminal_snippets WHERE id = :id")
    suspend fun deleteById(id: Long)
}
