package com.example.data.git

import com.example.data.ssh.SshConnectionManager
import com.example.domain.model.GitBranchEntry
import com.example.domain.model.GitCommitEntry
import com.example.domain.model.GitFileChange
import com.example.domain.model.GitFileStatus
import com.example.domain.model.GitRepositoryStatus
import com.example.domain.model.GitRepositorySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class GitCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val combinedOutput: String
        get() = listOf(stdout.trim(), stderr.trim()).filter { it.isNotBlank() }.joinToString("\n")
}

class GitRepository(
    private val connectionManager: SshConnectionManager,
) {

    suspend fun loadStatus(directory: String): GitRepositoryStatus = withContext(Dispatchers.IO) {
        val dir = directory.trim().ifBlank { return@withContext GitRepositoryStatus() }
        val topRes = execGit(dir, "rev-parse --show-toplevel", timeoutSec = 10)
        if (topRes.exitCode != 0) {
            return@withContext GitRepositoryStatus(
                summary = GitRepositorySummary(isGitRepo = false),
            )
        }
        val repoRoot = topRes.stdout.trim().ifBlank { dir }
        val statusRes = execGit(repoRoot, "status --porcelain=v2 -b --untracked-files=all", timeoutSec = 15)
        if (statusRes.exitCode != 0) {
            throw IllegalStateException(
                statusRes.combinedOutput.ifBlank { "Не удалось получить git status" },
            )
        }
        GitParser.parsePorcelainV2(statusRes.stdout, repoRootPath = repoRoot)
    }

    suspend fun loadSummary(directory: String): GitRepositorySummary = withContext(Dispatchers.IO) {
        runCatching { loadStatus(directory).summary }
            .getOrElse { GitRepositorySummary(isGitRepo = false) }
    }

    suspend fun initRepository(directory: String): String = withContext(Dispatchers.IO) {
        val res = execGit(directory, "init", timeoutSec = 15)
        requireSuccess(res, "Не удалось инициализировать Git")
    }

    suspend fun stageFile(repoRoot: String, relativePath: String) = withContext(Dispatchers.IO) {
        val quoted = GitParser.shellQuote(relativePath)
        val res = execGit(repoRoot, "add -- $quoted", timeoutSec = 15)
        requireSuccess(res, "Не удалось добавить файл в индекс")
    }

    suspend fun unstageFile(repoRoot: String, relativePath: String) = withContext(Dispatchers.IO) {
        val quoted = GitParser.shellQuote(relativePath)
        // Works both when HEAD exists and on initial unborn branch
        val res = execGit(
            repoRoot,
            "restore --staged -- $quoted 2>/dev/null || git -C ${GitParser.shellQuote(repoRoot)} rm --cached -q -- $quoted",
            timeoutSec = 15,
            rawShellAfterC = true,
        )
        requireSuccess(res, "Не удалось убрать файл из индекса")
    }

    suspend fun stageAll(repoRoot: String) = withContext(Dispatchers.IO) {
        val res = execGit(repoRoot, "add -A", timeoutSec = 20)
        requireSuccess(res, "Не удалось добавить все файлы в индекс")
    }

    suspend fun unstageAll(repoRoot: String) = withContext(Dispatchers.IO) {
        val res = execGit(
            repoRoot,
            "reset -q HEAD -- . 2>/dev/null || git -C ${GitParser.shellQuote(repoRoot)} rm --cached -rq .",
            timeoutSec = 20,
            rawShellAfterC = true,
        )
        requireSuccess(res, "Не удалось очистить индекс")
    }

    suspend fun discardFile(repoRoot: String, change: GitFileChange) = withContext(Dispatchers.IO) {
        val quoted = GitParser.shellQuote(change.path)
        if (change.status == GitFileStatus.UNTRACKED) {
            val res = execRawShell(
                "cd ${GitParser.shellQuote(repoRoot)} && rm -rf -- $quoted",
                timeoutSec = 15,
            )
            requireSuccess(res, "Не удалось удалить неотслеживаемый файл")
        } else if (change.isStaged) {
            // Unstage first, then restore working tree if tracked in HEAD
            val res = execRawShell(
                "git -C ${GitParser.shellQuote(repoRoot)} reset -q HEAD -- $quoted 2>/dev/null; " +
                    "git -C ${GitParser.shellQuote(repoRoot)} checkout -- $quoted 2>/dev/null || true",
                timeoutSec = 15,
            )
            requireSuccess(res, "Не удалось откатить файл")
        } else {
            val res = execGit(repoRoot, "checkout -- $quoted", timeoutSec = 15)
            requireSuccess(res, "Не удалось откатить изменения")
        }
    }

    suspend fun loadFileDiff(repoRoot: String, change: GitFileChange): String = withContext(Dispatchers.IO) {
        val quoted = GitParser.shellQuote(change.path)
        if (change.status == GitFileStatus.UNTRACKED) {
            // git diff --no-index exits with code 1 when files differ, which is expected
            val res = execGit(repoRoot, "diff --no-index -- /dev/null $quoted", timeoutSec = 15)
            return@withContext res.stdout.ifBlank { res.stderr }
        }
        val cmd = if (change.isStaged) {
            "diff --cached -- $quoted"
        } else {
            "diff -- $quoted"
        }
        val res = execGit(repoRoot, cmd, timeoutSec = 15)
        res.stdout.ifBlank { res.stderr }
    }

    suspend fun commit(
        repoRoot: String,
        message: String,
        amend: Boolean = false,
        autoStageAll: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val trimmed = message.trim()
        require(trimmed.isNotBlank()) { "Введите сообщение коммита" }
        if (autoStageAll) {
            stageAll(repoRoot)
        }
        val amendFlag = if (amend) "--amend " else ""
        val res = execGit(
            directory = repoRoot,
            gitArgs = "commit ${amendFlag}-F -",
            stdinText = "$trimmed\n",
            timeoutSec = 25,
        )
        requireSuccess(res, "Не удалось выполнить коммит")
    }

    suspend fun fetch(repoRoot: String): String = withContext(Dispatchers.IO) {
        val res = execGit(repoRoot, "fetch --all --prune", timeoutSec = 45)
        requireSuccess(res, "Ошибка git fetch")
    }

    suspend fun pull(repoRoot: String): String = withContext(Dispatchers.IO) {
        val res = execGit(repoRoot, "pull --ff-only", timeoutSec = 60)
        if (res.exitCode != 0 && res.combinedOutput.contains("Not possible to fast-forward", ignoreCase = true)) {
            val mergeRes = execGit(repoRoot, "pull --no-rebase", timeoutSec = 60)
            return@withContext requireSuccess(mergeRes, "Ошибка git pull")
        }
        requireSuccess(res, "Ошибка git pull")
    }

    suspend fun push(repoRoot: String, currentBranch: String, hasUpstream: Boolean): String = withContext(Dispatchers.IO) {
        val args = if (!hasUpstream && currentBranch.isNotBlank() && GitParser.isValidBranchName(currentBranch)) {
            "push -u origin ${GitParser.shellQuote(currentBranch)}"
        } else {
            "push"
        }
        val res = execGit(repoRoot, args, timeoutSec = 60)
        requireSuccess(res, "Ошибка git push")
    }

    suspend fun loadBranches(repoRoot: String): List<GitBranchEntry> = withContext(Dispatchers.IO) {
        val format = GitParser.shellQuote("%(HEAD)\t%(refname:short)\t%(refname)\t%(upstream:short)")
        val res = execGit(repoRoot, "for-each-ref --format=$format refs/heads refs/remotes", timeoutSec = 15)
        if (res.exitCode != 0) return@withContext emptyList()
        GitParser.parseBranches(res.stdout)
    }

    suspend fun switchBranch(
        repoRoot: String,
        branchName: String,
        isRemote: Boolean,
    ): String = withContext(Dispatchers.IO) {
        val clean = branchName.trim()
        require(GitParser.isValidBranchName(clean)) { "Некорректное имя ветки: $clean" }
        val args = if (isRemote) {
            // Strip remote prefix (e.g. origin/feature -> feature) and track
            val localName = clean.substringAfter('/', clean)
            "checkout -B ${GitParser.shellQuote(localName)} --track ${GitParser.shellQuote(clean)}"
        } else {
            "checkout ${GitParser.shellQuote(clean)}"
        }
        val res = execGit(repoRoot, args, timeoutSec = 25)
        requireSuccess(res, "Не удалось переключить ветку")
    }

    suspend fun createAndSwitchBranch(repoRoot: String, newBranchName: String): String = withContext(Dispatchers.IO) {
        val clean = newBranchName.trim()
        require(GitParser.isValidBranchName(clean)) { "Некорректное имя ветки (без пробелов и спецсимволов)" }
        val res = execGit(repoRoot, "checkout -b ${GitParser.shellQuote(clean)}", timeoutSec = 20)
        requireSuccess(res, "Не удалось создать ветку")
    }

    suspend fun loadRecentCommits(repoRoot: String, limit: Int = 20): List<GitCommitEntry> = withContext(Dispatchers.IO) {
        val format = GitParser.shellQuote("%h%x1f%H%x1f%s%x1f%an%x1f%cr%x1e")
        val res = execGit(repoRoot, "log -n $limit --format=$format", timeoutSec = 15)
        if (res.exitCode != 0) return@withContext emptyList()
        GitParser.parseCommits(res.stdout)
    }

    suspend fun loadCommitDiff(repoRoot: String, commitHash: String): String = withContext(Dispatchers.IO) {
        val clean = commitHash.trim()
        require(clean.matches(Regex("[0-9a-fA-F]{4,40}"))) { "Некорректный хэш коммита" }
        val res = execGit(repoRoot, "show --stat --patch --format=medium $clean", timeoutSec = 20)
        requireSuccess(res, "Не удалось загрузить diff коммита")
    }

    private fun requireSuccess(res: GitCommandResult, fallbackMessage: String): String {
        if (res.exitCode != 0) {
            throw IllegalStateException(res.combinedOutput.ifBlank { fallbackMessage })
        }
        return res.combinedOutput
    }

    private suspend fun execGit(
        directory: String,
        gitArgs: String,
        stdinText: String? = null,
        timeoutSec: Long = 20,
        rawShellAfterC: Boolean = false,
    ): GitCommandResult {
        val quotedDir = GitParser.shellQuote(directory)
        val cmd = if (rawShellAfterC) {
            "GIT_TERMINAL_PROMPT=0 git -C $quotedDir $gitArgs"
        } else {
            "GIT_TERMINAL_PROMPT=0 git -C $quotedDir $gitArgs"
        }
        return execRawShell(cmd, stdinText = stdinText, timeoutSec = timeoutSec)
    }

    private suspend fun execRawShell(
        command: String,
        stdinText: String? = null,
        timeoutSec: Long = 20,
    ): GitCommandResult = withContext(Dispatchers.IO) {
        val client = connectionManager.getActiveSshClient()
            ?: throw IllegalStateException("SSH-соединение не активно")

        client.startSession().use { session ->
            val cmd = session.exec(command)
            if (stdinText != null) {
                cmd.outputStream.use { os ->
                    os.write(stdinText.toByteArray(StandardCharsets.UTF_8))
                    os.flush()
                }
            }
            val stdoutBytes = readBounded(cmd.inputStream, MAX_OUTPUT_BYTES)
            val stderrBytes = readBounded(cmd.errorStream, MAX_ERROR_BYTES)
            cmd.join(timeoutSec, TimeUnit.SECONDS)
            GitCommandResult(
                exitCode = cmd.exitStatus ?: -1,
                stdout = stdoutBytes.toString(StandardCharsets.UTF_8),
                stderr = stderrBytes.toString(StandardCharsets.UTF_8),
            )
        }
    }

    private fun readBounded(stream: java.io.InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val read = stream.read(buf)
            if (read <= 0) break
            val allowed = minOf(read, maxBytes - total)
            if (allowed > 0) {
                out.write(buf, 0, allowed)
                total += allowed
            }
            if (total >= maxBytes) break
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAX_OUTPUT_BYTES = 1_500_000 // 1.5 MB cap for large diffs
        private const val MAX_ERROR_BYTES = 128_000
    }
}
