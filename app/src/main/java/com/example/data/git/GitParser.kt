package com.example.data.git

import com.example.domain.model.DiffLine
import com.example.domain.model.DiffLineType
import com.example.domain.model.GitBranchEntry
import com.example.domain.model.GitCommitEntry
import com.example.domain.model.GitFileChange
import com.example.domain.model.GitFileStatus
import com.example.domain.model.GitRepositoryStatus
import com.example.domain.model.GitRepositorySummary

object GitParser {

    const val LOG_FIELD_SEP = "\u001F"
    const val LOG_RECORD_SEP = "\u001E"

    fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    fun isValidBranchName(rawName: String): Boolean {
        val name = rawName.trim()
        if (name.isEmpty() || name.length > 120) return false
        if (name.startsWith("-") || name.startsWith("/") || name.endsWith("/") || name.endsWith(".")) return false
        if (name.endsWith(".lock") || name == "@") return false
        if (name.contains("..") || name.contains("//") || name.contains("@{")) return false
        if (name.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }) return false
        val forbidden = setOf('~', '^', ':', '?', '*', '[', '\\')
        if (name.any { it in forbidden }) return false
        return true
    }

    /**
     * Parses `git status --porcelain=v2 -b` output into structured staged, unstaged, and untracked lists.
     */
    fun parsePorcelainV2(raw: String, repoRootPath: String): GitRepositoryStatus {
        var branchName = ""
        var upstreamName: String? = null
        var aheadCount = 0
        var behindCount = 0
        var isDetachedHead = false
        var oidShort = ""

        val staged = mutableListOf<GitFileChange>()
        val unstaged = mutableListOf<GitFileChange>()
        val untracked = mutableListOf<GitFileChange>()

        raw.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd('\r')
            if (line.isEmpty()) return@forEach

            when {
                line.startsWith("# branch.oid ") -> {
                    val oid = line.removePrefix("# branch.oid ").trim()
                    if (oid != "(initial)" && oid.length >= 7) {
                        oidShort = oid.take(7)
                    }
                }

                line.startsWith("# branch.head ") -> {
                    val head = line.removePrefix("# branch.head ").trim()
                    if (head == "(detached)") {
                        isDetachedHead = true
                        branchName = if (oidShort.isNotBlank()) "detached@$oidShort" else "HEAD (detached)"
                    } else {
                        isDetachedHead = false
                        branchName = head
                    }
                }

                line.startsWith("# branch.upstream ") -> {
                    upstreamName = line.removePrefix("# branch.upstream ").trim().ifBlank { null }
                }

                line.startsWith("# branch.ab ") -> {
                    val parts = line.removePrefix("# branch.ab ").trim().split(Regex("\\s+"))
                    parts.forEach { token ->
                        when {
                            token.startsWith("+") -> aheadCount = token.drop(1).toIntOrNull() ?: 0
                            token.startsWith("-") -> behindCount = token.drop(1).toIntOrNull() ?: 0
                        }
                    }
                }

                // Ordinary changed entry: 1 <XY> <sub> <mH> <mI> <mW> <hH> <hI> <path>
                line.startsWith("1 ") -> {
                    val fields = line.split(' ', limit = 9)
                    if (fields.size >= 9) {
                        val xy = fields[1]
                        val path = unquoteGitPath(fields[8])
                        addEntriesFromXY(xy = xy, path = path, oldPath = null, staged = staged, unstaged = unstaged)
                    }
                }

                // Renamed/copied entry: 2 <XY> <sub> <mH> <mI> <mW> <hH> <hI> <X><score> <path>\t<origPath>
                line.startsWith("2 ") -> {
                    val fields = line.split(' ', limit = 10)
                    if (fields.size >= 10) {
                        val xy = fields[1]
                        val pathParts = fields[9].split('\t', limit = 2)
                        val newPath = unquoteGitPath(pathParts.firstOrNull().orEmpty())
                        val origPath = pathParts.getOrNull(1)?.let { unquoteGitPath(it) }
                        addEntriesFromXY(xy = xy, path = newPath, oldPath = origPath, staged = staged, unstaged = unstaged)
                    }
                }

                // Unmerged entry: u <XY> <sub> <m1> <m2> <m3> <mW> <h1> <h2> <h3> <path>
                line.startsWith("u ") -> {
                    val fields = line.split(' ', limit = 11)
                    if (fields.size >= 11) {
                        val path = unquoteGitPath(fields[10])
                        unstaged.add(
                            GitFileChange(
                                path = path,
                                status = GitFileStatus.UNMERGED,
                                isStaged = false,
                            ),
                        )
                    }
                }

                // Untracked entry: ? <path>
                line.startsWith("? ") -> {
                    val path = unquoteGitPath(line.removePrefix("? "))
                    if (path.isNotBlank()) {
                        untracked.add(
                            GitFileChange(
                                path = path,
                                status = GitFileStatus.UNTRACKED,
                                isStaged = false,
                            ),
                        )
                    }
                }
            }
        }

        val resolvedBranch = when {
            branchName.isNotBlank() -> branchName
            isDetachedHead && oidShort.isNotBlank() -> "detached@$oidShort"
            else -> "HEAD"
        }

        val totalDirty = staged.size + unstaged.size + untracked.size
        return GitRepositoryStatus(
            summary = GitRepositorySummary(
                isGitRepo = true,
                repoRootPath = repoRootPath,
                branchName = resolvedBranch,
                upstreamName = upstreamName,
                aheadCount = aheadCount,
                behindCount = behindCount,
                isDetachedHead = isDetachedHead,
                totalDirtyCount = totalDirty,
            ),
            stagedFiles = staged.sortedBy { it.path.lowercase() },
            unstagedFiles = unstaged.sortedBy { it.path.lowercase() },
            untrackedFiles = untracked.sortedBy { it.path.lowercase() },
        )
    }

    private fun addEntriesFromXY(
        xy: String,
        path: String,
        oldPath: String?,
        staged: MutableList<GitFileChange>,
        unstaged: MutableList<GitFileChange>,
    ) {
        if (xy.length < 2 || path.isBlank()) return
        val indexCode = xy[0]
        val worktreeCode = xy[1]

        mapStatusChar(indexCode)?.let { status ->
            staged.add(
                GitFileChange(
                    path = path,
                    oldPath = oldPath,
                    status = status,
                    isStaged = true,
                ),
            )
        }
        mapStatusChar(worktreeCode)?.let { status ->
            unstaged.add(
                GitFileChange(
                    path = path,
                    oldPath = oldPath,
                    status = status,
                    isStaged = false,
                ),
            )
        }
    }

    private fun mapStatusChar(ch: Char): GitFileStatus? = when (ch) {
        'M' -> GitFileStatus.MODIFIED
        'A' -> GitFileStatus.ADDED
        'D' -> GitFileStatus.DELETED
        'R' -> GitFileStatus.RENAMED
        'C' -> GitFileStatus.COPIED
        'U' -> GitFileStatus.UNMERGED
        else -> null
    }

    private fun unquoteGitPath(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
        return trimmed
    }

    /**
     * Parses `git for-each-ref --format=%(HEAD)%09%(refname:short)%09%(refname)%09%(upstream:short) refs/heads refs/remotes`.
     */
    fun parseBranches(raw: String): List<GitBranchEntry> {
        val result = mutableListOf<GitBranchEntry>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trimEnd('\r')
            if (trimmed.isBlank()) return@forEach
            val parts = trimmed.split('\t')
            if (parts.size < 3) return@forEach
            val headMark = parts[0].trim()
            val shortName = parts[1].trim()
            val fullRef = parts[2].trim()
            val upstream = parts.getOrNull(3)?.trim()?.ifBlank { null }
            if (shortName.isBlank() || shortName.endsWith("/HEAD")) return@forEach
            val isRemote = fullRef.startsWith("refs/remotes/")
            result.add(
                GitBranchEntry(
                    name = shortName,
                    isCurrent = headMark == "*",
                    isRemote = isRemote,
                    upstream = upstream,
                ),
            )
        }
        return result.sortedWith(
            compareByDescending<GitBranchEntry> { it.isCurrent }
                .thenBy { it.isRemote }
                .thenBy { it.name.lowercase() },
        )
    }

    /**
     * Parses `git log --format=%h%x1f%H%x1f%s%x1f%an%x1f%cr%x1e`.
     */
    fun parseCommits(raw: String): List<GitCommitEntry> {
        if (raw.isBlank()) return emptyList()
        return raw.split(LOG_RECORD_SEP).mapNotNull { record ->
            val cleaned = record.trim()
            if (cleaned.isEmpty()) return@mapNotNull null
            val fields = cleaned.split(LOG_FIELD_SEP)
            if (fields.size < 5) return@mapNotNull null
            GitCommitEntry(
                shortHash = fields[0].trim(),
                fullHash = fields[1].trim(),
                subject = fields[2].trim(),
                author = fields[3].trim(),
                relativeTime = fields[4].trim(),
            )
        }
    }

    /**
     * Parses unified diff output into colored [DiffLine] entries with line numbers.
     */
    fun parseUnifiedDiff(rawDiff: String): List<DiffLine> {
        if (rawDiff.isBlank()) return emptyList()
        val lines = mutableListOf<DiffLine>()
        var oldLine = 0
        var newLine = 0
        val hunkRegex = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@.*""")

        rawDiff.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd('\r')
            when {
                line.startsWith("diff --git ") ||
                    line.startsWith("index ") ||
                    line.startsWith("--- ") ||
                    line.startsWith("+++ ") ||
                    line.startsWith("new file mode") ||
                    line.startsWith("deleted file mode") ||
                    line.startsWith("Binary files ") -> {
                    lines.add(DiffLine(type = DiffLineType.HUNK_HEADER, content = line))
                }

                line.startsWith("@@") -> {
                    val match = hunkRegex.find(line)
                    if (match != null) {
                        oldLine = match.groupValues[1].toIntOrNull() ?: 0
                        newLine = match.groupValues[2].toIntOrNull() ?: 0
                    }
                    lines.add(DiffLine(type = DiffLineType.HUNK_HEADER, content = line))
                }

                line.startsWith("+") -> {
                    lines.add(
                        DiffLine(
                            type = DiffLineType.ADDED,
                            oldLineNumber = null,
                            newLineNumber = if (newLine > 0) newLine++ else null,
                            content = line.drop(1),
                        ),
                    )
                }

                line.startsWith("-") -> {
                    lines.add(
                        DiffLine(
                            type = DiffLineType.REMOVED,
                            oldLineNumber = if (oldLine > 0) oldLine++ else null,
                            newLineNumber = null,
                            content = line.drop(1),
                        ),
                    )
                }

                line.startsWith(" ") -> {
                    lines.add(
                        DiffLine(
                            type = DiffLineType.CONTEXT,
                            oldLineNumber = if (oldLine > 0) oldLine++ else null,
                            newLineNumber = if (newLine > 0) newLine++ else null,
                            content = line.drop(1),
                        ),
                    )
                }

                else -> {
                    lines.add(DiffLine(type = DiffLineType.CONTEXT, content = line))
                }
            }
        }
        return lines
    }
}
