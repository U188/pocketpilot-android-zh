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
        require(cleanBody.isNotBlank()) { "模板内容不能为空" }

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
                title = "代码审查",
                slashCommand = "review",
                body = "检查当前更改（git diff），找出潜在错误、资源泄漏和安全问题，并提出针对性的改进建议。",
                autoSend = false,
                sortOrder = 0,
                createdAtMillis = nowMillis,
            ),
            PromptTemplate(
                title = "查找并修复问题",
                slashCommand = "fix",
                body = "找出问题根因，以最小且安全的改动修复，并运行构建和测试进行验证。",
                autoSend = false,
                sortOrder = 1,
                createdAtMillis = nowMillis,
            ),
            PromptTemplate(
                title = "编写测试",
                slashCommand = "tests",
                body = "为最近的更改编写单元测试，覆盖边界情况，并运行测试进行验证。",
                autoSend = false,
                sortOrder = 2,
                createdAtMillis = nowMillis,
            ),
            PromptTemplate(
                title = "解释模块",
                slashCommand = "explain",
                body = "简明、结构化地说明此模块的架构、数据流和关键入口。",
                autoSend = false,
                sortOrder = 3,
                createdAtMillis = nowMillis,
            ),
            PromptTemplate(
                title = "生成提交说明",
                slashCommand = "commit",
                body = "查看 git diff，并按照 Conventional Commits 格式拟定简短清晰的提交说明（中文和英文）。",
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
