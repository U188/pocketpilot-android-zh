package com.example.data.sftp

import com.example.data.ssh.SshConnectionManager
import com.example.domain.model.FileSearchMatch
import com.example.domain.model.RemoteFileContent
import com.example.domain.model.RemoteFileEntry
import com.example.domain.model.RemoteFileStat
import com.example.domain.model.SaveFileResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.EnumSet
import java.util.concurrent.TimeUnit

class SftpRepository(
    private val connectionManager: SshConnectionManager,
) {
    private val sftpLock = Mutex()
    private var cachedClientRef: SSHClient? = null
    private var cachedSftp: SFTPClient? = null

    suspend fun resolveInitialDirectory(): String = withSftp { sftp ->
        runCatching { sftp.canonicalize(".") }.getOrDefault("/")
    }

    suspend fun listDirectory(
        path: String,
        includeHidden: Boolean,
    ): List<RemoteFileEntry> = withSftp { sftp ->
        val normalized = normalizeRemotePath(path)
        val resources: List<RemoteResourceInfo> = sftp.ls(normalized)
        resources
            .filter { it.name != "." && it.name != ".." }
            .map { info ->
                val attrs = info.attributes
                val isSymlink = attrs.type == FileMode.Type.SYMLINK
                val isDir = if (isSymlink) {
                    runCatching { sftp.stat(info.path).type == FileMode.Type.DIRECTORY }
                        .getOrDefault(false)
                } else {
                    info.isDirectory
                }
                RemoteFileEntry(
                    name = info.name,
                    path = normalizeRemotePath(info.path),
                    isDirectory = isDir,
                    isSymlink = isSymlink,
                    sizeBytes = attrs.size,
                    modifiedAtEpochSec = attrs.mtime,
                    permissionsMask = attrs.mode.mask,
                )
            }
            .filter { includeHidden || !it.isHidden }
    }

    suspend fun stat(path: String): RemoteFileStat = withSftp { sftp ->
        val normalized = normalizeRemotePath(path)
        val attrs: FileAttributes = sftp.stat(normalized)
        RemoteFileStat(
            path = normalized,
            sizeBytes = attrs.size,
            modifiedAtEpochSec = attrs.mtime,
            permissionsMask = attrs.mode.mask,
            isDirectory = attrs.type == FileMode.Type.DIRECTORY,
        )
    }

    suspend fun createDirectory(parentPath: String, name: String): String = withSftp { sftp ->
        val cleanName = name.trim().trim('/')
        require(cleanName.isNotEmpty() && !cleanName.contains('/')) {
            "Некорректное имя папки"
        }
        val fullPath = joinRemotePath(parentPath, cleanName)
        sftp.mkdir(fullPath)
        fullPath
    }

    suspend fun createEmptyFile(parentPath: String, name: String): String = withSftp { sftp ->
        val cleanName = name.trim().trim('/')
        require(cleanName.isNotEmpty() && !cleanName.contains('/')) {
            "Некорректное имя файла"
        }
        val fullPath = joinRemotePath(parentPath, cleanName)
        val remoteFile = sftp.open(
            fullPath,
            EnumSet.of(OpenMode.CREAT, OpenMode.EXCL, OpenMode.WRITE),
        )
        remoteFile.close()
        fullPath
    }

    suspend fun rename(oldPath: String, newName: String): String = withSftp { sftp ->
        val cleanName = newName.trim().trim('/')
        require(cleanName.isNotEmpty() && !cleanName.contains('/')) {
            "Некорректное имя"
        }
        val parent = parentRemotePath(oldPath)
        val targetPath = joinRemotePath(parent, cleanName)
        sftp.rename(normalizeRemotePath(oldPath), targetPath)
        targetPath
    }

    suspend fun moveEntries(
        entries: List<RemoteFileEntry>,
        targetDir: String,
    ) = withSftp { sftp ->
        val normalizedTarget = normalizeRemotePath(targetDir)
        for (entry in entries) {
            currentCoroutineContext().ensureActive()
            val destination = joinRemotePath(normalizedTarget, entry.name)
            if (destination == entry.path) continue
            sftp.rename(entry.path, destination)
        }
    }

    suspend fun copyEntries(
        entries: List<RemoteFileEntry>,
        targetDir: String,
    ) = withContext(Dispatchers.IO) {
        val normalizedTarget = normalizeRemotePath(targetDir)
        for (entry in entries) {
            currentCoroutineContext().ensureActive()
            val dest = resolveCopyDestination(entry.name, normalizedTarget)
            val cmd = "cp -a -- ${shellQuote(entry.path)} ${shellQuote(dest)}"
            val (exitCode, stderr) = execCommand(cmd)
            if (exitCode != 0) {
                throw IllegalStateException(
                    stderr.ifBlank { "Не удалось скопировать ${entry.name}" },
                )
            }
        }
    }

    suspend fun deleteEntries(entries: List<RemoteFileEntry>) = withSftp { sftp ->
        for (entry in entries) {
            currentCoroutineContext().ensureActive()
            deleteRecursive(sftp, entry.path, entry.isDirectory && !entry.isSymlink)
        }
    }

    suspend fun chmod(path: String, permissionsMask: Int) = withSftp { sftp ->
        sftp.chmod(normalizeRemotePath(path), permissionsMask and 0xFFF)
    }

    suspend fun readTextFile(path: String): RemoteFileContent = withSftp { sftp ->
        val normalized = normalizeRemotePath(path)
        val attrs = sftp.stat(normalized)
        if (attrs.type == FileMode.Type.DIRECTORY) {
            throw IllegalArgumentException("Нельзя открыть папку в редакторе")
        }
        if (attrs.size > MAX_EDITOR_FILE_BYTES) {
            throw IllegalArgumentException(
                "Файл слишком большой для редактора (> ${MAX_EDITOR_FILE_BYTES / (1024 * 1024)} МБ)",
            )
        }

        val buffer = ByteArray(attrs.size.toInt().coerceAtLeast(0))
        sftp.open(normalized, EnumSet.of(OpenMode.READ)).use { remoteFile ->
            var offset = 0L
            while (offset < buffer.size) {
                currentCoroutineContext().ensureActive()
                val read = remoteFile.read(offset, buffer, offset.toInt(), buffer.size - offset.toInt())
                if (read <= 0) break
                offset += read
            }
        }
        val text = String(buffer, StandardCharsets.UTF_8)
        val fileName = normalized.substringAfterLast('/', normalized)
        RemoteFileContent(
            path = normalized,
            fileName = fileName,
            text = text,
            modifiedAtEpochSec = attrs.mtime,
            sizeBytes = attrs.size,
        )
    }

    suspend fun writeTextFile(
        path: String,
        text: String,
        expectedMtimeEpochSec: Long?,
        forceOverwrite: Boolean,
    ): SaveFileResult = withSftp { sftp ->
        val normalized = normalizeRemotePath(path)
        if (!forceOverwrite && expectedMtimeEpochSec != null) {
            val currentAttrs = runCatching { sftp.stat(normalized) }.getOrNull()
            if (currentAttrs != null && currentAttrs.mtime != expectedMtimeEpochSec) {
                return@withSftp SaveFileResult.Conflict(
                    serverModifiedAtEpochSec = currentAttrs.mtime,
                    serverSizeBytes = currentAttrs.size,
                )
            }
        }

        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        sftp.open(
            normalized,
            EnumSet.of(OpenMode.CREAT, OpenMode.TRUNC, OpenMode.WRITE),
        ).use { remoteFile ->
            var offset = 0
            while (offset < bytes.size) {
                currentCoroutineContext().ensureActive()
                val chunk = minOf(IO_CHUNK_SIZE, bytes.size - offset)
                remoteFile.write(offset.toLong(), bytes, offset, chunk)
                offset += chunk
            }
        }
        val updatedAttrs = sftp.stat(normalized)
        SaveFileResult.Saved(
            modifiedAtEpochSec = updatedAttrs.mtime,
            sizeBytes = updatedAttrs.size,
        )
    }

    suspend fun uploadStream(
        inputStream: InputStream,
        remotePath: String,
        onProgress: (Long) -> Unit,
    ) = withSftp { sftp ->
        val normalized = normalizeRemotePath(remotePath)
        val buffer = ByteArray(IO_CHUNK_SIZE)
        var totalWritten = 0L
        sftp.open(
            normalized,
            EnumSet.of(OpenMode.CREAT, OpenMode.TRUNC, OpenMode.WRITE),
        ).use { remoteFile ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = inputStream.read(buffer)
                if (read <= 0) break
                remoteFile.write(totalWritten, buffer, 0, read)
                totalWritten += read
                onProgress(totalWritten)
            }
        }
    }

    suspend fun downloadStream(
        remotePath: String,
        outputStream: OutputStream,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
    ) = withSftp { sftp ->
        val normalized = normalizeRemotePath(remotePath)
        val totalBytes = runCatching { sftp.stat(normalized).size }.getOrDefault(-1L)
        val buffer = ByteArray(IO_CHUNK_SIZE)
        var offset = 0L
        sftp.open(normalized, EnumSet.of(OpenMode.READ)).use { remoteFile ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = remoteFile.read(offset, buffer, 0, buffer.size)
                if (read <= 0) break
                outputStream.write(buffer, 0, read)
                offset += read
                onProgress(offset, totalBytes)
            }
            outputStream.flush()
        }
    }

    suspend fun searchByName(
        basePath: String,
        query: String,
        includeHidden: Boolean,
    ): List<FileSearchMatch> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@withContext emptyList()
        val normalized = normalizeRemotePath(basePath)
        val hiddenClause = if (includeHidden) "" else """-not -path '*/.*'"""
        val pattern = "*${cleanQuery.replace("'", "")}*"
        val cmd =
            "find ${shellQuote(normalized)} -maxdepth 5 $hiddenClause -iname ${shellQuote(pattern)} 2>/dev/null | head -n 100"
        val (_, stdout) = execCommandWithStdout(cmd)
        stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != normalized }
            .toList()
            .map { matchedPath ->
                val clean = normalizeRemotePath(matchedPath)
                val isDir = runCatching { stat(clean).isDirectory }.getOrDefault(false)
                FileSearchMatch(
                    path = clean,
                    fileName = clean.substringAfterLast('/', clean),
                    isDirectory = isDir,
                )
            }
    }

    suspend fun searchByContent(
        basePath: String,
        query: String,
        includeHidden: Boolean,
    ): List<FileSearchMatch> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@withContext emptyList()
        val normalized = normalizeRemotePath(basePath)
        val excludeHidden = if (includeHidden) {
            "--exclude-dir=.git --exclude-dir=node_modules"
        } else {
            "--exclude-dir='.*' --exclude='.*' --exclude-dir=node_modules"
        }
        val cmd =
            "grep -rnIF $excludeHidden -m 3 -- ${shellQuote(cleanQuery)} ${shellQuote(normalized)} 2>/dev/null | head -n 80"
        val (_, stdout) = execCommandWithStdout(cmd)
        stdout.lineSequence()
            .mapNotNull { line ->
                val firstColon = line.indexOf(':')
                if (firstColon <= 0) return@mapNotNull null
                val secondColon = line.indexOf(':', firstColon + 1)
                if (secondColon <= firstColon + 1) return@mapNotNull null
                val path = normalizeRemotePath(line.substring(0, firstColon))
                val lineNumber = line.substring(firstColon + 1, secondColon).toIntOrNull()
                    ?: return@mapNotNull null
                val snippet = line.substring(secondColon + 1).trim().take(140)
                FileSearchMatch(
                    path = path,
                    fileName = path.substringAfterLast('/', path),
                    isDirectory = false,
                    lineNumber = lineNumber,
                    previewLine = snippet,
                )
            }
            .toList()
    }

    fun closeCached() {
        runCatching { cachedSftp?.close() }
        cachedSftp = null
        cachedClientRef = null
    }

    private suspend fun resolveCopyDestination(name: String, targetDir: String): String {
        val candidate = joinRemotePath(targetDir, name)
        val exists = runCatching { stat(candidate) }.isSuccess
        if (!exists) return candidate
        val dotIdx = name.lastIndexOf('.')
        val (stem, ext) = if (dotIdx > 0) {
            name.substring(0, dotIdx) to name.substring(dotIdx)
        } else {
            name to ""
        }
        for (i in 1..99) {
            val suffix = if (i == 1) "_copy" else "_copy_$i"
            val next = joinRemotePath(targetDir, "$stem$suffix$ext")
            if (runCatching { stat(next) }.isFailure) return next
        }
        return joinRemotePath(targetDir, "${stem}_${System.currentTimeMillis()}$ext")
    }

    private suspend fun deleteRecursive(
        sftp: SFTPClient,
        path: String,
        isDirectory: Boolean,
    ) {
        currentCoroutineContext().ensureActive()
        if (!isDirectory) {
            sftp.rm(path)
            return
        }
        val children = sftp.ls(path).filter { it.name != "." && it.name != ".." }
        for (child in children) {
            val childIsDir = child.isDirectory && child.attributes.type != FileMode.Type.SYMLINK
            deleteRecursive(sftp, child.path, childIsDir)
        }
        sftp.rmdir(path)
    }

    private suspend fun execCommand(command: String): Pair<Int, String> {
        val client = requireActiveClient()
        return client.startSession().use { session ->
            val cmd = session.exec(command)
            val stderr = cmd.errorStream.bufferedReader(StandardCharsets.UTF_8).readText()
            cmd.join(30, TimeUnit.SECONDS)
            (cmd.exitStatus ?: -1) to stderr.trim()
        }
    }

    private suspend fun execCommandWithStdout(command: String): Pair<Int, String> {
        val client = requireActiveClient()
        return client.startSession().use { session ->
            val cmd = session.exec(command)
            val stdout = cmd.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            cmd.join(20, TimeUnit.SECONDS)
            (cmd.exitStatus ?: -1) to stdout
        }
    }

    private suspend fun <T> withSftp(block: suspend (SFTPClient) -> T): T =
        withContext(Dispatchers.IO) {
            sftpLock.withLock {
                val client = requireActiveClient()
                val sftp = getOrOpenSftp(client)
                try {
                    block(sftp)
                } catch (e: SFTPException) {
                    throw IllegalStateException(
                        e.message?.takeIf { it.isNotBlank() } ?: "Ошибка SFTP (${e.statusCode})",
                        e,
                    )
                }
            }
        }

    private fun requireActiveClient(): SSHClient {
        return connectionManager.getActiveSshClient()
            ?: throw IllegalStateException("Нет активного SSH-подключения")
    }

    private fun getOrOpenSftp(client: SSHClient): SFTPClient {
        if (cachedClientRef === client && cachedSftp != null) {
            return cachedSftp!!
        }
        runCatching { cachedSftp?.close() }
        val fresh = client.newSFTPClient()
        cachedClientRef = client
        cachedSftp = fresh
        return fresh
    }

    companion object {
        private const val IO_CHUNK_SIZE = 32 * 1024
        private const val MAX_EDITOR_FILE_BYTES = 2 * 1024 * 1024L

        fun normalizeRemotePath(path: String): String {
            val cleaned = path.replace(Regex("/+"), "/").trim()
            if (cleaned.isEmpty() || cleaned == "/") return "/"
            return cleaned.trimEnd('/')
        }

        fun joinRemotePath(parent: String, child: String): String {
            val normalizedParent = normalizeRemotePath(parent)
            val cleanChild = child.trim('/')
            return if (normalizedParent == "/") "/$cleanChild" else "$normalizedParent/$cleanChild"
        }

        fun parentRemotePath(path: String): String {
            val normalized = normalizeRemotePath(path)
            if (normalized == "/") return "/"
            val idx = normalized.lastIndexOf('/')
            return if (idx <= 0) "/" else normalized.substring(0, idx)
        }

        fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
    }
}
