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
        val title = sessionTitle.trim().ifBlank { "OpenCode 会话" }
        val sb = StringBuilder()
        sb.appendLine("# $title")
        sb.appendLine()
        if (workspacePath.isNotBlank()) {
            sb.appendLine("- **项目：** `$workspacePath`")
        }
        sb.appendLine("- **导出时间：** ${timeFormatter.format(Instant.now().atZone(ZoneId.systemDefault()))}")
        sb.appendLine("- **消息数：** ${messages.size}")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        messages.forEach { msg ->
            val roleHeader = if (msg.isUser) "## 用户" else "## 助手"
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
                            sb.appendLine("<summary>思考过程</summary>")
                            sb.appendLine()
                            sb.appendLine(part.text.trim())
                            sb.appendLine("</details>")
                            sb.appendLine()
                        }
                    }

                    is OpenCodePart.Tool -> {
                        val toolTitle = part.title?.takeIf { it.isNotBlank() } ?: part.toolName
                        sb.appendLine("> **工具：** `$toolTitle` (${part.status.name.lowercase()})")
                        part.inputSummary?.takeIf { it.isNotBlank() }?.let {
                            sb.appendLine("> 输入：`$it`")
                        }
                        part.error?.takeIf { it.isNotBlank() }?.let {
                            sb.appendLine("> 错误：$it")
                        }
                        sb.appendLine()
                    }

                    is OpenCodePart.FileAttachment -> {
                        sb.appendLine("- 附件：`${part.filename}`（`${part.mime}`）")
                    }

                    is OpenCodePart.Patch -> {
                        if (part.files.isNotEmpty()) {
                            sb.appendLine("- 已修改文件：${part.files.joinToString(", ") { "`$it`" }}")
                        }
                    }
                }
            }

            if (!msg.error.isNullOrBlank()) {
                sb.appendLine("> **错误：** ${msg.error}")
                sb.appendLine()
            }
            sb.appendLine("---")
            sb.appendLine()
        }
        return sb.toString().trimEnd() + "\n"
    }
}
