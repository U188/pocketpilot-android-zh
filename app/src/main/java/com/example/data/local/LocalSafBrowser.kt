package com.example.data.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.example.domain.model.LocalSafEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Zero-permission local folder browser backed by Android Storage Access Framework (SAF)
 * for dual-pane tablet transfers between phone and remote server.
 */
class LocalSafBrowser(context: Context) {

    private val appContext = context.applicationContext

    fun takePersistablePermission(treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(treeUri, flags)
        }
    }

    suspend fun listChildren(treeUri: Uri): List<LocalSafEntry> = withContext(Dispatchers.IO) {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val result = mutableListOf<LocalSafEntry>()
        appContext.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (cursor.moveToNext()) {
                val childDocId = if (idIdx >= 0) cursor.getString(idIdx) else continue
                val name = if (nameIdx >= 0) cursor.getString(nameIdx) ?: childDocId else childDocId
                val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) ?: "application/octet-stream" else "application/octet-stream"
                val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                val modified = if (modIdx >= 0 && !cursor.isNull(modIdx)) cursor.getLong(modIdx) else 0L
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                result.add(
                    LocalSafEntry(
                        name = name,
                        uri = childUri,
                        isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        sizeBytes = size,
                        modifiedAtMillis = modified,
                        mimeType = mime,
                    ),
                )
            }
        }
        result.sortedWith(compareBy<LocalSafEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }

    suspend fun createDocumentInTree(treeUri: Uri, fileName: String): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val parentDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
            DocumentsContract.createDocument(
                appContext.contentResolver,
                parentDocUri,
                "application/octet-stream",
                fileName,
            )
        }.getOrNull()
    }

    fun folderDisplayName(treeUri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        return docId?.substringAfter(':')?.ifBlank { docId } ?: treeUri.lastPathSegment ?: "手机文件夹"
    }
}
