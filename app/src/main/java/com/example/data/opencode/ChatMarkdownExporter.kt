package com.example.data.opencode

import com.example.domain.model.OpenCodeMessage
import com.example.domain.model.OpenCodePart
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ChatMarkdownExporter {

    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun exportSession(
        sessionTitle: String,
        workspacePath: String,
        messages: List<OpenCodeMessage>,
    ): String {
        val title = sessionTitle.trim().ifBlank { "Сессия OpenCode" }
        val sb = StringBuilder()
        sb.appendLine("# $title")
        sb.appendLine()
        if (workspacePath.isNotBlank()) {
            sb.appendLine("- **Проект:** `$workspacePath`")
        }
        sb.appendLine("- **Экспортировано:** ${timeFormatter.format(Instant.now().atZone(ZoneId.systemDefault()))}")
        sb.appendLine("- **Сообщений:** ${messages.size}")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        messages.forEach { msg ->
            val roleHeader = if (msg.isUser) "## Пользователь" else "## Ассистент"
            val metaParts = buildList {
                if (msg.createdAtMillis > 0L) {
                    add(timeFormatter.format(Instant.ofEpochMilli(msg.createdAtMillis).atZone(ZoneId.systemDefault())))
                }
                msg.modelId?.takeIf { it.isNotBlank() }?.let { add(it) }
                msg.agent?.takeIf { it.isNotBlank() }?.let { add("agent: $it") }
            }
            sb.appendLine(if (metaParts.isNotEmpty()) "$roleHeader *(${metaParts.joinToString(" • ")})*" else roleHeader)
            sb.appendLine()

            msg.parts.forEach { part ->
                when (part) {
                    is OpenCodePart.Text -> {
                        if (part.text.isNotBlank()) {
                            sb.appendLine(part.text.trim())
                            sb.appendLine()
                        }
                    }

                    is OpenCodePart.Reasoning -> {
                        if (part.text.isNotBlank()) {
                            sb.appendLine("<details>")
                            sb.appendLine("<summary>Размышления</summary>")
                            sb.appendLine()
                            sb.appendLine(part.text.trim())
                            sb.appendLine("</details>")
                            sb.appendLine()
                        }
                    }

                    is OpenCodePart.Tool -> {
                        val toolTitle = part.title?.takeIf { it.isNotBlank() } ?: part.toolName
                        sb.appendLine("> **Инструмент:** `$toolTitle` (${part.status.name.lowercase()})")
                        part.inputSummary?.takeIf { it.isNotBlank() }?.let {
                            sb.appendLine("> Ввод: `$it`")
                        }
                        part.error?.takeIf { it.isNotBlank() }?.let {
                            sb.appendLine("> Ошибка: $it")
                        }
                        sb.appendLine()
                    }

                    is OpenCodePart.FileAttachment -> {
                        sb.appendLine("- Вложение: `${part.filename}` (`${part.mime}`)")
                    }

                    is OpenCodePart.Patch -> {
                        if (part.files.isNotEmpty()) {
                            sb.appendLine("- Изменённые файлы: ${part.files.joinToString(", ") { "`$it`" }}")
                        }
                    }
                }
            }

            if (!msg.error.isNullOrBlank()) {
                sb.appendLine("> **Ошибка:** ${msg.error}")
                sb.appendLine()
            }
            sb.appendLine("---")
            sb.appendLine()
        }
        return sb.toString().trimEnd() + "\n"
    }
}
