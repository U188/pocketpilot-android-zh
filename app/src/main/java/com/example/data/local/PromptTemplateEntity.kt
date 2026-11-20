package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "prompt_templates")
data class PromptTemplateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val title: String,
    val slashCommand: String,
    val body: String,
    val autoSend: Boolean = false,
    val sortOrder: Int = 0,
    val createdAtMillis: Long = System.currentTimeMillis(),
)
