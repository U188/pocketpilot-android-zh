package com.example.domain.model

data class OpenCodeSession(
    val id: String,
    val title: String,
    val directory: String = "",
    val parentId: String? = null,
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
    val isBusy: Boolean = false,
)

enum class ToolCallStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    ERROR,
}

sealed interface OpenCodePart {
    val id: String
    val messageId: String
    val sessionId: String

    data class Text(
        override val id: String,
        override val messageId: String,
        override val sessionId: String,
        val text: String,
    ) : OpenCodePart

    data class Reasoning(
        override val id: String,
        override val messageId: String,
        override val sessionId: String,
        val text: String,
    ) : OpenCodePart

    data class Tool(
        override val id: String,
        override val messageId: String,
        override val sessionId: String,
        val callId: String,
        val toolName: String,
        val status: ToolCallStatus,
        val title: String? = null,
        val inputSummary: String? = null,
        val rawInput: String? = null,
        val output: String? = null,
        val error: String? = null,
        val filePath: String? = null,
    ) : OpenCodePart

    data class FileAttachment(
        override val id: String,
        override val messageId: String,
        override val sessionId: String,
        val filename: String,
        val url: String,
        val mime: String,
    ) : OpenCodePart

    data class Patch(
        override val id: String,
        override val messageId: String,
        override val sessionId: String,
        val files: List<String>,
    ) : OpenCodePart
}

data class OpenCodeMessage(
    val id: String,
    val sessionId: String,
    val role: String, // "user" or "assistant"
    val createdAtMillis: Long = 0L,
    val completedAtMillis: Long? = null,
    val providerId: String? = null,
    val modelId: String? = null,
    val agent: String? = null,
    val error: String? = null,
    val tokens: TokenUsage? = null,
    val costUsd: Double? = null,
    val parts: List<OpenCodePart> = emptyList(),
) {
    val isUser: Boolean
        get() = role.equals("user", ignoreCase = true)
}

data class OpenCodeModel(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val displayName: String,
    val contextLimit: Long? = null,
    val outputLimit: Long? = null,
    val inputCostPer1M: Double? = null,
    val outputCostPer1M: Double? = null,
)

data class OpenCodeProvider(
    val id: String,
    val name: String,
    val models: List<OpenCodeModel>,
)

data class OpenCodeAgent(
    val name: String,
    val description: String = "",
    val mode: String = "primary",
)

data class AttachedFileRef(
    val path: String,
    val name: String,
)

enum class SseConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
}

enum class PermissionReplyOption(val apiValue: String) {
    ONCE("once"),
    ALWAYS("always"),
    REJECT("reject"),
}

data class OpenCodePermissionRequest(
    val id: String,
    val sessionId: String,
    val permission: String,
    val title: String,
    val patterns: List<String> = emptyList(),
    val command: String? = null,
    val filePath: String? = null,
    val details: String? = null,
    val alwaysPatterns: List<String> = emptyList(),
    val createdAtMillis: Long = System.currentTimeMillis(),
) {
    val isHighRisk: Boolean
        get() {
            val lowerPerm = permission.lowercase()
            if (lowerPerm == "bash" || lowerPerm == "shell" || lowerPerm == "external_directory") {
                return true
            }
            if (command != null && DangerousCommandDetector.isDangerous(command)) {
                return true
            }
            return false
        }
}

enum class DiffLineType {
    CONTEXT,
    ADDED,
    REMOVED,
    HUNK_HEADER,
}

data class DiffLine(
    val type: DiffLineType,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null,
    val content: String,
)

data class OpenCodeFileDiff(
    val file: String,
    val before: String = "",
    val after: String = "",
    val additions: Int = 0,
    val deletions: Int = 0,
    val rawPatch: String? = null,
    val lines: List<DiffLine> = emptyList(),
)

sealed interface OpenCodeEvent {
    data class SessionUpdated(val session: OpenCodeSession) : OpenCodeEvent
    data class SessionDeleted(val sessionId: String) : OpenCodeEvent
    data class SessionStatusChanged(val sessionId: String, val isBusy: Boolean) : OpenCodeEvent
    data class MessageUpdated(val message: OpenCodeMessage) : OpenCodeEvent
    data class PartUpdated(val part: OpenCodePart, val delta: String?) : OpenCodeEvent
    data class PartDelta(
        val sessionId: String,
        val messageId: String,
        val partId: String,
        val field: String,
        val delta: String,
    ) : OpenCodeEvent

    data class PermissionAsked(val request: OpenCodePermissionRequest) : OpenCodeEvent
    data class PermissionReplied(val requestId: String, val sessionId: String?) : OpenCodeEvent
    data class SessionDiffUpdated(val sessionId: String, val diffs: List<OpenCodeFileDiff>) : OpenCodeEvent
    data class SessionError(val sessionId: String?, val message: String) : OpenCodeEvent
}
