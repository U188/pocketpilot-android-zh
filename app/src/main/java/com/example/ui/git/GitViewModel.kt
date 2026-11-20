package com.example.ui.git

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.git.GitParser
import com.example.data.git.GitRepository
import com.example.data.ssh.SshConnectionManager
import com.example.domain.model.DiffLine
import com.example.domain.model.GitBranchEntry
import com.example.domain.model.GitCommitEntry
import com.example.domain.model.GitFileChange
import com.example.domain.model.GitRepositoryStatus
import com.example.domain.model.SshConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GitDiffPreviewState(
    val title: String,
    val subtitle: String,
    val absoluteRemotePath: String? = null,
    val rawDiff: String = "",
    val lines: List<DiffLine> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

data class GitUiState(
    val isConnected: Boolean = false,
    val targetDirectory: String = "",
    val status: GitRepositoryStatus = GitRepositoryStatus(),
    val isLoading: Boolean = false,
    val isBusy: Boolean = false,
    val activeActionLabel: String? = null,
    val errorMessage: String? = null,
    val commitMessage: String = "",
    val amendLastCommit: Boolean = false,
    val branches: List<GitBranchEntry> = emptyList(),
    val recentCommits: List<GitCommitEntry> = emptyList(),
    val isLoadingMeta: Boolean = false,
    val diffPreview: GitDiffPreviewState? = null,
)

class GitViewModel(
    private val connectionManager: SshConnectionManager,
    private val gitRepository: GitRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GitUiState())
    val uiState: StateFlow<GitUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var refreshJob: Job? = null
    private var metaJob: Job? = null
    private var diffJob: Job? = null

    init {
        viewModelScope.launch {
            connectionManager.connectionState.collect { conn ->
                when (conn) {
                    is SshConnectionState.Connected -> {
                        val wasDisconnected = !_uiState.value.isConnected
                        _uiState.update { it.copy(isConnected = true) }
                        if (wasDisconnected && _uiState.value.targetDirectory.isNotBlank()) {
                            refresh()
                        }
                    }

                    is SshConnectionState.Disconnected,
                    is SshConnectionState.Error -> {
                        _uiState.update {
                            it.copy(
                                isConnected = false,
                                isLoading = false,
                                isBusy = false,
                                activeActionLabel = null,
                            )
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    fun setTargetDirectory(path: String, autoRefresh: Boolean = true) {
        val clean = path.trim()
        if (clean.isEmpty()) return
        val changed = clean != _uiState.value.targetDirectory
        if (changed) {
            _uiState.update {
                it.copy(
                    targetDirectory = clean,
                    errorMessage = null,
                    diffPreview = null,
                )
            }
        }
        if (autoRefresh && _uiState.value.isConnected && (changed || !_uiState.value.status.summary.isGitRepo)) {
            refresh()
        }
    }

    fun refresh() {
        val dir = _uiState.value.targetDirectory.ifBlank { return }
        if (!_uiState.value.isConnected) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                gitRepository.loadStatus(dir)
            }.onSuccess { repoStatus ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        status = repoStatus,
                        errorMessage = null,
                    )
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = err.message ?: "Не удалось загрузить статус Git",
                    )
                }
            }
        }
    }

    fun initRepository() {
        val dir = _uiState.value.targetDirectory.ifBlank { return }
        runGitAction("Инициализация Git…") {
            gitRepository.initRepository(dir)
            _messages.tryEmit("Git-репозиторий инициализирован")
            refreshInternal(dir)
        }
    }

    fun stageFile(change: GitFileChange) {
        val root = activeRepoRoot() ?: return
        runGitAction("Индексация…") {
            gitRepository.stageFile(root, change.path)
            refreshInternal(root)
        }
    }

    fun unstageFile(change: GitFileChange) {
        val root = activeRepoRoot() ?: return
        runGitAction("Снятие из индекса…") {
            gitRepository.unstageFile(root, change.path)
            refreshInternal(root)
        }
    }

    fun stageAll() {
        val root = activeRepoRoot() ?: return
        runGitAction("Добавление всех файлов…") {
            gitRepository.stageAll(root)
            refreshInternal(root)
        }
    }

    fun unstageAll() {
        val root = activeRepoRoot() ?: return
        runGitAction("Очистка индекса…") {
            gitRepository.unstageAll(root)
            refreshInternal(root)
        }
    }

    fun discardFile(change: GitFileChange) {
        val root = activeRepoRoot() ?: return
        runGitAction("Откат изменений…") {
            gitRepository.discardFile(root, change)
            _messages.tryEmit("Откат выполнен: ${change.path}")
            refreshInternal(root)
        }
    }

    fun updateCommitMessage(text: String) {
        _uiState.update { it.copy(commitMessage = text) }
    }

    fun prependCommitPrefix(prefix: String) {
        _uiState.update { state ->
            val current = state.commitMessage.trimStart()
            val cleaned = current.replace(Regex("^(feat|fix|refactor|chore|docs|test|style|perf)(\\([^)]+\\))?:\\s*"), "")
            state.copy(commitMessage = "$prefix $cleaned")
        }
    }

    fun toggleAmend() {
        _uiState.update { it.copy(amendLastCommit = !it.amendLastCommit) }
    }

    fun commit(andPush: Boolean = false) {
        val root = activeRepoRoot() ?: return
        val state = _uiState.value
        val msg = state.commitMessage.trim()
        if (msg.isBlank()) {
            _messages.tryEmit("Введите сообщение коммита")
            return
        }
        val autoStageAll = state.status.stagedFiles.isEmpty() &&
            (state.status.unstagedFiles.isNotEmpty() || state.status.untrackedFiles.isNotEmpty())
        if (state.status.stagedFiles.isEmpty() && !autoStageAll && !state.amendLastCommit) {
            _messages.tryEmit("Нет изменений для коммита")
            return
        }

        val label = if (andPush) "Коммит и Push…" else "Создание коммита…"
        runGitAction(label) {
            gitRepository.commit(
                repoRoot = root,
                message = msg,
                amend = state.amendLastCommit,
                autoStageAll = autoStageAll,
            )
            _uiState.update { it.copy(commitMessage = "", amendLastCommit = false) }
            if (andPush) {
                val summary = _uiState.value.status.summary
                gitRepository.push(
                    repoRoot = root,
                    currentBranch = summary.branchName,
                    hasUpstream = !summary.upstreamName.isNullOrBlank(),
                )
                _messages.tryEmit("Коммит создан и отправлен (push)")
            } else {
                _messages.tryEmit("Коммит создан")
            }
            refreshInternal(root)
        }
    }

    fun fetch() {
        val root = activeRepoRoot() ?: return
        runGitAction("Git fetch…") {
            gitRepository.fetch(root)
            _messages.tryEmit("Состояние remote обновлено")
            refreshInternal(root)
        }
    }

    fun pull() {
        val root = activeRepoRoot() ?: return
        runGitAction("Git pull…") {
            val out = gitRepository.pull(root)
            val firstLine = out.lineSequence().firstOrNull { it.isNotBlank() } ?: "Pull выполнен"
            _messages.tryEmit(firstLine.take(120))
            refreshInternal(root)
        }
    }

    fun push() {
        val root = activeRepoRoot() ?: return
        val summary = _uiState.value.status.summary
        runGitAction("Git push…") {
            gitRepository.push(
                repoRoot = root,
                currentBranch = summary.branchName,
                hasUpstream = !summary.upstreamName.isNullOrBlank(),
            )
            _messages.tryEmit("Изменения отправлены на сервер (push)")
            refreshInternal(root)
        }
    }

    fun loadBranchesAndLog() {
        val root = activeRepoRoot() ?: return
        metaJob?.cancel()
        metaJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMeta = true) }
            runCatching {
                val branches = gitRepository.loadBranches(root)
                val commits = gitRepository.loadRecentCommits(root, limit = 25)
                branches to commits
            }.onSuccess { (branches, commits) ->
                _uiState.update {
                    it.copy(
                        isLoadingMeta = false,
                        branches = branches,
                        recentCommits = commits,
                    )
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                _uiState.update { it.copy(isLoadingMeta = false) }
                _messages.tryEmit(err.message ?: "Не удалось загрузить ветки и историю")
            }
        }
    }

    fun switchBranch(branch: GitBranchEntry) {
        val root = activeRepoRoot() ?: return
        if (branch.isCurrent) return
        runGitAction("Переключение на ${branch.name}…") {
            gitRepository.switchBranch(root, branch.name, branch.isRemote)
            _messages.tryEmit("Активная ветка: ${branch.name}")
            refreshInternal(root)
            loadBranchesAndLog()
        }
    }

    fun createBranch(branchName: String) {
        val root = activeRepoRoot() ?: return
        val clean = branchName.trim()
        if (!GitParser.isValidBranchName(clean)) {
            _messages.tryEmit("Некорректное имя ветки (без пробелов и спецсимволов)")
            return
        }
        runGitAction("Создание ветки $clean…") {
            gitRepository.createAndSwitchBranch(root, clean)
            _messages.tryEmit("Создана и выбрана ветка $clean")
            refreshInternal(root)
            loadBranchesAndLog()
        }
    }

    fun openFileDiff(change: GitFileChange) {
        val root = activeRepoRoot() ?: return
        val absPath = if (root.endsWith("/")) "$root${change.path}" else "$root/${change.path}"
        val subtitle = buildString {
            append(if (change.isStaged) "Staged" else "Unstaged")
            append(" • ")
            append(change.status.shortCode)
        }
        diffJob?.cancel()
        diffJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    diffPreview = GitDiffPreviewState(
                        title = change.path,
                        subtitle = subtitle,
                        absoluteRemotePath = absPath,
                        isLoading = true,
                    ),
                )
            }
            runCatching {
                gitRepository.loadFileDiff(root, change)
            }.onSuccess { raw ->
                _uiState.update { state ->
                    state.copy(
                        diffPreview = state.diffPreview?.copy(
                            rawDiff = raw,
                            lines = GitParser.parseUnifiedDiff(raw),
                            isLoading = false,
                            error = null,
                        ),
                    )
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                _uiState.update { state ->
                    state.copy(
                        diffPreview = state.diffPreview?.copy(
                            isLoading = false,
                            error = err.message ?: "Не удалось загрузить diff",
                        ),
                    )
                }
            }
        }
    }

    fun openCommitDiff(commit: GitCommitEntry) {
        val root = activeRepoRoot() ?: return
        diffJob?.cancel()
        diffJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    diffPreview = GitDiffPreviewState(
                        title = "${commit.shortHash} — ${commit.subject}",
                        subtitle = "${commit.author} • ${commit.relativeTime}",
                        absoluteRemotePath = null,
                        isLoading = true,
                    ),
                )
            }
            runCatching {
                gitRepository.loadCommitDiff(root, commit.fullHash)
            }.onSuccess { raw ->
                _uiState.update { state ->
                    state.copy(
                        diffPreview = state.diffPreview?.copy(
                            rawDiff = raw,
                            lines = GitParser.parseUnifiedDiff(raw),
                            isLoading = false,
                            error = null,
                        ),
                    )
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                _uiState.update { state ->
                    state.copy(
                        diffPreview = state.diffPreview?.copy(
                            isLoading = false,
                            error = err.message ?: "Не удалось загрузить diff коммита",
                        ),
                    )
                }
            }
        }
    }

    fun closeDiff() {
        diffJob?.cancel()
        _uiState.update { it.copy(diffPreview = null) }
    }

    private fun activeRepoRoot(): String? =
        _uiState.value.status.summary.repoRootPath.ifBlank { _uiState.value.targetDirectory }.takeIf { it.isNotBlank() }

    private suspend fun refreshInternal(dir: String) {
        val updated = gitRepository.loadStatus(dir)
        _uiState.update { it.copy(status = updated, errorMessage = null) }
    }

    private fun runGitAction(label: String, block: suspend () -> Unit) {
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, activeActionLabel = label, errorMessage = null) }
            runCatching {
                block()
            }.onFailure { err ->
                if (err is CancellationException) throw err
                val msg = err.message ?: "Ошибка Git-операции"
                _uiState.update { it.copy(errorMessage = msg) }
                _messages.tryEmit(msg.take(160))
            }
            _uiState.update { it.copy(isBusy = false, activeActionLabel = null) }
        }
    }

    class Factory(
        private val connectionManager: SshConnectionManager,
        private val gitRepository: GitRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return GitViewModel(
                connectionManager = connectionManager,
                gitRepository = gitRepository,
            ) as T
        }
    }
}
