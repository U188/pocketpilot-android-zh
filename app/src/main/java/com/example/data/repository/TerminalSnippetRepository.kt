package com.example.data.repository

import com.example.data.local.TerminalSnippetDao
import com.example.data.local.TerminalSnippetEntity
import com.example.domain.model.TerminalSnippet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TerminalSnippetRepository(
    private val dao: TerminalSnippetDao,
) {
    private val seedMutex = Mutex()
    private var seeded = false

    val snippets: Flow<List<TerminalSnippet>> = dao.observeAll().map { entities ->
        entities.map { entity ->
            TerminalSnippet(
                id = entity.id,
                title = entity.title,
                command = entity.command,
                autoExecute = entity.autoExecute,
            )
        }
    }

    suspend fun ensureDefaultSnippets() {
        if (seeded) return
        seedMutex.withLock {
            if (seeded) return
            if (dao.count() == 0) {
                dao.insertAll(defaultSnippets())
            }
            seeded = true
        }
    }

    suspend fun saveSnippet(snippet: TerminalSnippet) {
        val trimmedTitle = snippet.title.trim()
        val trimmedCmd = snippet.command.trim()
        if (trimmedTitle.isEmpty() || trimmedCmd.isEmpty()) return
        if (snippet.id == 0L) {
            dao.insert(
                TerminalSnippetEntity(
                    title = trimmedTitle,
                    command = trimmedCmd,
                    autoExecute = snippet.autoExecute,
                ),
            )
        } else {
            dao.update(
                TerminalSnippetEntity(
                    id = snippet.id,
                    title = trimmedTitle,
                    command = trimmedCmd,
                    autoExecute = snippet.autoExecute,
                ),
            )
        }
    }

    suspend fun deleteSnippet(id: Long) {
        dao.deleteById(id)
    }

    private fun defaultSnippets(): List<TerminalSnippetEntity> = listOf(
        TerminalSnippetEntity(
            title = "OpenCode serve",
            command = "opencode serve --port 4096",
            autoExecute = true,
        ),
        TerminalSnippetEntity(
            title = "Git status",
            command = "git status -sb",
            autoExecute = true,
        ),
        TerminalSnippetEntity(
            title = "Git diff",
            command = "git diff --stat",
            autoExecute = true,
        ),
        TerminalSnippetEntity(
            title = "Файлы (ls -la)",
            command = "ls -la",
            autoExecute = true,
        ),
        TerminalSnippetEntity(
            title = "Docker контейнеры",
            command = "docker ps",
            autoExecute = true,
        ),
        TerminalSnippetEntity(
            title = "Системные ресурсы",
            command = "df -h && free -h",
            autoExecute = true,
        ),
    )
}
