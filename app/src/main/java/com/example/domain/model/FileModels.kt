package com.example.domain.model

import android.net.Uri

data class RemoteFileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val sizeBytes: Long,
    val modifiedAtEpochSec: Long,
    val permissionsMask: Int,
) {
    val isHidden: Boolean
        get() = name.startsWith(".")

    val extension: String
        get() = name.substringAfterLast('.', "").lowercase()

    val octalPermissions: String
        get() = "%04o".format(permissionsMask and 0xFFF)

    val rwxPermissions: String
        get() {
            val perm = permissionsMask and 0x1FF
            val chars = CharArray(9)
            val bits = intArrayOf(0x100, 0x080, 0x040, 0x020, 0x010, 0x008, 0x004, 0x002, 0x001)
            val letters = charArrayOf('r', 'w', 'x', 'r', 'w', 'x', 'r', 'w', 'x')
            for (i in 0 until 9) {
                chars[i] = if ((perm and bits[i]) != 0) letters[i] else '-'
            }
            val prefix = when {
                isSymlink -> 'l'
                isDirectory -> 'd'
                else -> '-'
            }
            return "$prefix${String(chars)}"
        }
}

enum class FileSortBy {
    NAME,
    SIZE,
    MODIFIED,
    TYPE,
}

enum class SortDirection {
    ASC,
    DESC,
}

enum class ClipboardOperation {
    COPY,
    CUT,
}

data class FileClipboard(
    val operation: ClipboardOperation,
    val entries: List<RemoteFileEntry>,
)

enum class FileSearchMode {
    NAME,
    CONTENT,
}

data class FileSearchMatch(
    val path: String,
    val fileName: String,
    val isDirectory: Boolean = false,
    val lineNumber: Int? = null,
    val previewLine: String? = null,
)

enum class TransferDirection {
    UPLOAD,
    DOWNLOAD,
}

enum class TransferStatus {
    QUEUED,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class TransferTask(
    val id: String,
    val fileName: String,
    val remotePath: String,
    val direction: TransferDirection,
    val status: TransferStatus = TransferStatus.QUEUED,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = -1L,
    val errorMessage: String? = null,
) {
    val progressFraction: Float?
        get() = if (totalBytes > 0L) {
            (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }
}

data class LocalSafEntry(
    val name: String,
    val uri: Uri,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val mimeType: String,
)

data class RemoteFileStat(
    val path: String,
    val sizeBytes: Long,
    val modifiedAtEpochSec: Long,
    val permissionsMask: Int,
    val isDirectory: Boolean,
)

data class RemoteFileContent(
    val path: String,
    val fileName: String,
    val text: String,
    val modifiedAtEpochSec: Long,
    val sizeBytes: Long,
)

sealed interface SaveFileResult {
    data class Saved(
        val modifiedAtEpochSec: Long,
        val sizeBytes: Long,
    ) : SaveFileResult

    data class Conflict(
        val serverModifiedAtEpochSec: Long,
        val serverSizeBytes: Long,
    ) : SaveFileResult

    data class Error(
        val message: String,
    ) : SaveFileResult
}
