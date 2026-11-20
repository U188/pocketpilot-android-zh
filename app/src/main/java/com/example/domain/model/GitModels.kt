package com.example.domain.model

enum class GitFileStatus(val shortCode: String) {
    MODIFIED("M"),
    ADDED("A"),
    DELETED("D"),
    RENAMED("R"),
    COPIED("C"),
    UNMERGED("U"),
    UNTRACKED("?"),
}

data class GitFileChange(
    val path: String,
    val oldPath: String? = null,
    val status: GitFileStatus,
    val isStaged: Boolean,
) {
    val stableKey: String
        get() = "${if (isStaged) "S" else "U"}:${status.shortCode}:$path"
}

data class GitCommitEntry(
    val shortHash: String,
    val fullHash: String,
    val subject: String,
    val author: String,
    val relativeTime: String,
)

data class GitBranchEntry(
    val name: String,
    val isCurrent: Boolean,
    val isRemote: Boolean = false,
    val upstream: String? = null,
)

data class GitRepositorySummary(
    val isGitRepo: Boolean = false,
    val repoRootPath: String = "",
    val branchName: String = "",
    val upstreamName: String? = null,
    val aheadCount: Int = 0,
    val behindCount: Int = 0,
    val isDetachedHead: Boolean = false,
    val totalDirtyCount: Int = 0,
)

data class GitRepositoryStatus(
    val summary: GitRepositorySummary = GitRepositorySummary(),
    val stagedFiles: List<GitFileChange> = emptyList(),
    val unstagedFiles: List<GitFileChange> = emptyList(),
    val untrackedFiles: List<GitFileChange> = emptyList(),
) {
    val totalChangesCount: Int
        get() = stagedFiles.size + unstagedFiles.size + untrackedFiles.size
}
