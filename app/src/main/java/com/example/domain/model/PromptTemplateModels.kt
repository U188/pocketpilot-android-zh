package com.example.domain.model

data class PromptTemplate(
    val id: Long = 0L,
    val title: String,
    val slashCommand: String,
    val body: String,
    val autoSend: Boolean = false,
    val sortOrder: Int = 0,
    val createdAtMillis: Long = System.currentTimeMillis(),
) {
    val normalizedCommand: String
        get() {
            val clean = slashCommand.trim().removePrefix("/").lowercase()
            return if (clean.isEmpty()) "" else "/$clean"
        }
}

data class AttachedImageRef(
    val id: String,
    val filename: String,
    val mimeType: String,
    val base64DataUrl: String,
    val byteSize: Int,
    val previewBytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AttachedImageRef) return false
        return id == other.id &&
            filename == other.filename &&
            mimeType == other.mimeType &&
            byteSize == other.byteSize
    }

    override fun hashCode(): Int = id.hashCode()
}
