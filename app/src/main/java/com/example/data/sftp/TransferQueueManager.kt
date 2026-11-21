package com.example.data.sftp

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.domain.model.TransferDirection
import com.example.domain.model.TransferStatus
import com.example.domain.model.TransferTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TransferQueueManager(
    context: Context,
    private val sftpRepository: SftpRepository,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tasks = MutableStateFlow<List<TransferTask>>(emptyList())
    val tasks: StateFlow<List<TransferTask>> = _tasks.asStateFlow()

    private val _completedRemoteDirs = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val completedRemoteDirs: SharedFlow<String> = _completedRemoteDirs.asSharedFlow()

    private val runningJobs = ConcurrentHashMap<String, Job>()

    fun enqueueUploads(uris: List<Uri>, targetRemoteDir: String) {
        uris.forEach { uri ->
            val (fileName, totalBytes) = resolveUriMeta(uri)
            val remotePath = SftpRepository.joinRemotePath(targetRemoteDir, fileName)
            val taskId = UUID.randomUUID().toString()
            val task = TransferTask(
                id = taskId,
                fileName = fileName,
                remotePath = remotePath,
                direction = TransferDirection.UPLOAD,
                status = TransferStatus.QUEUED,
                totalBytes = totalBytes,
            )
            addTask(task)
            val job = scope.launch {
                updateTask(taskId) { it.copy(status = TransferStatus.IN_PROGRESS) }
                try {
                    val stream = appContext.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("无法打开所选文件")
                    stream.use { input ->
                        sftpRepository.uploadStream(input, remotePath) { transferred ->
                            updateTask(taskId) {
                                it.copy(bytesTransferred = transferred)
                            }
                        }
                    }
                    updateTask(taskId) {
                        it.copy(
                            status = TransferStatus.COMPLETED,
                            bytesTransferred = if (it.totalBytes > 0L) it.totalBytes else it.bytesTransferred,
                        )
                    }
                    _completedRemoteDirs.tryEmit(SftpRepository.normalizeRemotePath(targetRemoteDir))
                } catch (e: CancellationException) {
                    updateTask(taskId) { it.copy(status = TransferStatus.CANCELLED) }
                    throw e
                } catch (t: Throwable) {
                    updateTask(taskId) {
                        it.copy(
                            status = TransferStatus.FAILED,
                            errorMessage = t.message ?: "上传失败",
                        )
                    }
                } finally {
                    runningJobs.remove(taskId)
                }
            }
            runningJobs[taskId] = job
        }
    }

    fun enqueueDownload(
        remotePath: String,
        fileName: String,
        sizeBytes: Long,
        destinationUri: Uri,
    ) {
        val taskId = UUID.randomUUID().toString()
        val task = TransferTask(
            id = taskId,
            fileName = fileName,
            remotePath = remotePath,
            direction = TransferDirection.DOWNLOAD,
            status = TransferStatus.QUEUED,
            totalBytes = sizeBytes,
        )
        addTask(task)
        val job = scope.launch {
            updateTask(taskId) { it.copy(status = TransferStatus.IN_PROGRESS) }
            try {
                val stream = appContext.contentResolver.openOutputStream(destinationUri)
                    ?: throw IllegalStateException("无法打开目标文件")
                stream.use { output ->
                    sftpRepository.downloadStream(remotePath, output) { transferred, total ->
                        updateTask(taskId) {
                            it.copy(
                                bytesTransferred = transferred,
                                totalBytes = if (total > 0L) total else it.totalBytes,
                            )
                        }
                    }
                }
                updateTask(taskId) {
                    it.copy(
                        status = TransferStatus.COMPLETED,
                        bytesTransferred = if (it.totalBytes > 0L) it.totalBytes else it.bytesTransferred,
                    )
                }
            } catch (e: CancellationException) {
                updateTask(taskId) { it.copy(status = TransferStatus.CANCELLED) }
                throw e
            } catch (t: Throwable) {
                updateTask(taskId) {
                    it.copy(
                        status = TransferStatus.FAILED,
                        errorMessage = t.message ?: "下载失败",
                    )
                }
            } finally {
                runningJobs.remove(taskId)
            }
        }
        runningJobs[taskId] = job
    }

    fun cancelTask(taskId: String) {
        runningJobs[taskId]?.cancel()
        runningJobs.remove(taskId)
        updateTask(taskId) {
            if (it.status == TransferStatus.QUEUED || it.status == TransferStatus.IN_PROGRESS) {
                it.copy(status = TransferStatus.CANCELLED)
            } else {
                it
            }
        }
    }

    fun clearFinished() {
        _tasks.update { list ->
            list.filter {
                it.status == TransferStatus.QUEUED || it.status == TransferStatus.IN_PROGRESS
            }
        }
    }

    private fun addTask(task: TransferTask) {
        _tasks.update { listOf(task) + it.take(40) }
    }

    private inline fun updateTask(taskId: String, transform: (TransferTask) -> TransferTask) {
        _tasks.update { list ->
            list.map { if (it.id == taskId) transform(it) else it }
        }
    }

    private fun resolveUriMeta(uri: Uri): Pair<String, Long> {
        var name = "upload_${System.currentTimeMillis()}"
        var size = -1L
        runCatching {
            appContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) {
                        cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { name = it }
                    }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        size = cursor.getLong(sizeIdx)
                    }
                }
            }
        }
        return name to size
    }
}
