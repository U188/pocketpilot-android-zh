package com.example.data.repository

import com.example.data.local.PromptTemplateDao
import com.example.data.local.PromptTemplateEntity
import com.example.domain.model.PromptTemplate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PromptTemplateRepository(
    private val dao: PromptTemplateDao,
) {
    fun observeAll(): Flow<List<PromptTemplate>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun ensureDefaultsSeeded() {
        if (dao.count() > 0) return
        val now = System.currentTimeMillis()
        dao.insertAll(
            defaultTemplates(now).map { it.toEntity() },
        )
    }

    suspend fun save(template: PromptTemplate): Long {
        val cleanSlug = sanitizeSlashCommand(template.slashCommand, fallbackTitle = template.title)
        val cleanTitle = template.title.trim().ifBlank { "/$cleanSlug" }
        val cleanBody = template.body.trim()
        require(cleanBody.isNotBlank()) { "Текст шаблона не может быть пустым" }

        val entity = PromptTemplateEntity(
            id = template.id,
            title = cleanTitle,
            slashCommand = cleanSlug,
            body = cleanBody,
            autoSend = template.autoSend,
            sortOrder = template.sortOrder,
            createdAtMillis = if (template.id == 0L) System.currentTimeMillis() else template.createdAtMillis,
        )
        return if (template.id == 0L) {
            dao.insert(entity)
        } else {
            dao.update(entity)
            template.id
        }
    }

    suspend fun delete(id: Long) {
        dao.deleteById(id)
    }

    companion object {
        fun sanitizeSlashCommand(raw: String, fallbackTitle: String = ""): String {
            val cleaned = raw.trim()
                .removePrefix("/")
                .lowercase()
                .replace(Regex("[^a-zа-яё0-9_-]"), "")
            if (cleaned.isNotBlank()) return cleaned.take(24)

            val fromTitle = fallbackTitle.trim()
                .lowercase()
                .replace(Regex("[^a-zа-яё0-9_-]+"), "_")
                .trim('_')
            return fromTitle.take(20).ifBlank { "prompt" }
        }

        /**
         * Extracts active slash query if composer text starts with `/` (single token)
         * or ends with a standalone `/token` after whitespace.
         * Returns the query string without leading `/`, or null if slash menu shouldn't be shown.
         */
        fun extractActiveSlashQuery(composerText: String): String? {
            if (composerText.isEmpty()) return null
            val lastSlash = composerText.lastIndexOf('/')
            if (lastSlash < 0) return null
            if (lastSlash > 0 && !composerText[lastSlash - 1].isWhitespace()) return null
            val tail = composerText.substring(lastSlash + 1)
            if (tail.any { it.isWhitespace() }) return null
            if (tail.length > 24) return null
            return tail.lowercase()
        }

        fun filterBySlashQuery(
            templates: List<PromptTemplate>,
            query: String,
        ): List<PromptTemplate> {
            val q = query.trim().removePrefix("/").lowercase()
            if (q.isEmpty()) return templates
            return templates.filter { item ->
                item.slashCommand.lowercase().contains(q) ||
                    item.title.lowercase().contains(q)
            }
        }

        fun applyTemplateToComposer(
            currentText: String,
            template: PromptTemplate,
        ): String {
            val body = template.body.trim()
            if (currentText.isBlank()) return body
            val activeSlash = extractActiveSlashQuery(currentText)
            if (activeSlash != null) {
                val lastSlash = currentText.lastIndexOf('/')
                val prefix = currentText.substring(0, lastSlash).trimEnd()
                return if (prefix.isEmpty()) body else "$prefix\n$body"
            }
            return "${currentText.trimEnd()}\n$body"
        }

        fun defaultTemplates(nowMillis: Long = System.currentTimeMillis()): List<PromptTemplate> = listOf(
            PromptTemplate(
                title = "Код-ревью изменений",
                slashCommand = "review",
                body = "Проверь текущие изменения (git diff): найди потенциальные баги, утечки ресурсов, проблемы безопасности и предложи точечные улучшения.",
                autoSend = false,
                sortOrder = 0,
                createdAtMillis = nowMillis,
            ),
            PromptTemplate(
                title = "Найти и исправить ошибку",
                slashCommand = "fix",
                body = "Найди первопричину ошибки, исправь её минимальными безопасными правками и проверь сборку и тесты.",
                autoSend = false,
                sortOrder = 1,
                createdAtMillis = nowMillis,
            ),
            PromptTemplate(
                title = "Написать тесты",
                slashCommand = "tests",
                body = "Напиши модульные тесты для последних изменений с покрытием пограничных случаев и запусти их для проверки.",
                autoSend = false,
                sortOrder = 2,
                createdAtMillis = nowMillis,
            ),
            PromptTemplate(
                title = "Объяснить модуль",
                slashCommand = "explain",
                body = "Кратко и структурированно объясни архитектуру этого модуля, поток данных и ключевые точки входа.",
                autoSend = false,
                sortOrder = 3,
                createdAtMillis = nowMillis,
            ),
            PromptTemplate(
                title = "Описание коммита",
                slashCommand = "commit",
                body = "Посмотри git diff и предложи короткое понятное commit-сообщение в формате Conventional Commits (на русском и английском).",
                autoSend = false,
                sortOrder = 4,
                createdAtMillis = nowMillis,
            ),
        )
    }
}

private fun PromptTemplateEntity.toDomain(): PromptTemplate = PromptTemplate(
    id = id,
    title = title,
    slashCommand = slashCommand,
    body = body,
    autoSend = autoSend,
    sortOrder = sortOrder,
    createdAtMillis = createdAtMillis,
)

private fun PromptTemplate.toEntity(): PromptTemplateEntity = PromptTemplateEntity(
    id = id,
    title = title,
    slashCommand = slashCommand,
    body = body,
    autoSend = autoSend,
    sortOrder = sortOrder,
    createdAtMillis = createdAtMillis,
)
