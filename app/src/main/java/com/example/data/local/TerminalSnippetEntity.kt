package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "terminal_snippets")
data class TerminalSnippetEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val title: String,
    val command: String,
    val autoExecute: Boolean = true,
    val createdAtMillis: Long = System.currentTimeMillis(),
)
