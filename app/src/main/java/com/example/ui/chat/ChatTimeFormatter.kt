package com.example.ui.chat

import com.example.domain.model.OpenCodeMessage
import com.example.domain.model.OpenCodePart
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ChatTimeFormatter {

    private val TIME_ONLY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")
    private val FULL_DATE_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    /**
     * Formats message timestamp in the phone's local timezone:
     * - Today -> "HH:mm"
     * - Same year, different day -> "dd.MM HH:mm"
     * - Different year -> "dd.MM.yyyy HH:mm"
     */
    fun formatMessageTimestamp(
        epochMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (epochMillis <= 0L) return ""
        val msgZoned = Instant.ofEpochMilli(epochMillis).atZone(zoneId)
        val nowZoned = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        return when {
            msgZoned.toLocalDate() == nowZoned.toLocalDate() -> TIME_ONLY_FMT.format(msgZoned)
            msgZoned.year == nowZoned.year -> DAY_TIME_FMT.format(msgZoned)
            else -> FULL_DATE_TIME_FMT.format(msgZoned)
        }
    }

    /**
     * Formats assistant generation duration (e.g., "за 14 с", "за 1 мин 12 с", "за <1 с").
     * Returns null when duration cannot be determined or is non-positive.
     */
    fun formatGenerationDuration(
        startMillis: Long,
        endMillis: Long?,
    ): String? {
        if (startMillis <= 0L || endMillis == null || endMillis <= startMillis) return null
        val diffMs = endMillis - startMillis
        val totalSec = diffMs / 1000L
        return when {
            totalSec < 1L -> "за <1 с"
            totalSec < 60L -> "за $totalSec с"
            else -> {
                val minutes = totalSec / 60L
                val remSec = totalSec % 60L
                if (remSec == 0L) "за $minutes мин" else "за $minutes мин $remSec с"
            }
        }
    }

    /**
     * Computes displayable assistant duration using completedAtMillis - createdAtMillis,
     * falling back to previous user message timestamp if createdAtMillis == completedAtMillis.
     */
    fun resolveAssistantDuration(
        message: OpenCodeMessage,
        previousUserCreatedAtMillis: Long?,
    ): String? {
        if (message.isUser) return null
        val completed = message.completedAtMillis ?: return null
        val start = when {
            message.createdAtMillis in 1 until completed -> message.createdAtMillis
            previousUserCreatedAtMillis != null && previousUserCreatedAtMillis in 1 until completed ->
                previousUserCreatedAtMillis
            else -> return null
        }
        return formatGenerationDuration(start, completed)
    }

    /**
     * Builds copyable plain text representation of a chat message (markdown text, attachments,
     * tool summaries if no text, and error message).
     */
    fun buildCopyableMessageText(message: OpenCodeMessage): String {
        val sections = mutableListOf<String>()
        message.parts.forEach { part ->
            when (part) {
                is OpenCodePart.Text -> {
                    if (part.text.isNotBlank()) sections.add(part.text.trimEnd())
                }
                is OpenCodePart.FileAttachment -> {
                    sections.add("@${part.filename}")
                }
                else -> Unit
            }
        }
        if (sections.isEmpty()) {
            message.parts.forEach { part ->
                when (part) {
                    is OpenCodePart.Reasoning -> {
                        if (part.text.isNotBlank()) sections.add(part.text.trim())
                    }
                    is OpenCodePart.Tool -> {
                        val line = buildString {
                            append("[${part.toolName}]")
                            part.inputSummary?.let { append(" $it") }
                            part.output?.takeIf { it.isNotBlank() }?.let { append("\n$it") }
                            part.error?.takeIf { it.isNotBlank() }?.let { append("\nОшибка: $it") }
                        }
                        if (line.isNotBlank()) sections.add(line)
                    }
                    is OpenCodePart.Patch -> {
                        if (part.files.isNotEmpty()) {
                            sections.add("Изменены файлы: ${part.files.joinToString(", ")}")
                        }
                    }
                    else -> Unit
                }
            }
        }
        message.error?.takeIf { it.isNotBlank() }?.let { err ->
            sections.add("Ошибка: $err")
        }
        return sections.joinToString("\n\n").trim()
    }
}
