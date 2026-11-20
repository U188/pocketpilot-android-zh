package com.example.ui.files

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppPreferencesRepository
import com.example.data.local.LocalSafBrowser
import com.example.data.sftp.SftpRepository
import com.example.data.sftp.TransferQueueManager
import com.example.data.ssh.SshConnectionManager
import com.example.domain.model.ClipboardOperation
import com.example.domain.model.FileClipboard
import com.example.domain.model.FileSearchMatch
import com.example.domain.model.FileSearchMode
import com.example.domain.model.FileSortBy
import com.example.domain.model.LocalSafEntry
import com.example.domain.model.RemoteFileEntry
import com.example.domain.model.SortDirection
import com.example.domain.model.SshConnectionState
import com.example.domain.model.TransferTask
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

data class FileManagerUiState(
    val isConnected: Boolean = false,
    val isReconnecting: Boolean = false,
    val homePath: String = "/",
    val currentPath: String = "/",
    val entries: List<RemoteFileEntry> = emptyList(),
    val isLoading: Boolean = false,
    val isMutating: Boolean = false,
    val errorMessage: String? = null,
    val sortBy: FileSortBy = FileSortBy.NAME,
    val sortDirection: SortDirection = SortDirection.ASC,
    val showHidden: Boolean = false,
    val selectedPaths: Set<String> = emptySet(),
    val clipboard: FileClipboard? = null,
    val searchActive: Boolean = false,
    val searchQuery: String = "",
    val searchMode: FileSearchMode = FileSearchMode.NAME,
    val isSearching: Boolean = false,
    val searchResults: List<FileSearchMatch> = emptyList(),
    val searchPerformed: Boolean = false,
    val treeChildren: Map<String, List<RemoteFileEntry>> = emptyMap(),
    val expandedTreePaths: Set<String> = setOf("/"),
    val loadingTreePaths: Set<String> = emptySet(),
    val transfers: List<TransferTask> = emptyList(),
    val localFolderUri: Uri? = null,
    val localFolderName: String? = null,
    val localEntries: List<LocalSafEntry> = emptyList(),
    val isLocalLoading: Boolean = false,
) {
    val isSelectionMode: Boolean
        get() = selectedPaths.isNotEmpty()

    val selectedEntries: List<RemoteFileEntry>
        get() = entries.filter { it.path in selectedPaths }

    val activeTransferCount: Int
        get() = transfers.count {
            it.status == com.example.domain.model.TransferStatus.QUEUED ||
                it.status == com.example.domain.model.TransferStatus.IN_PROGRESS
        }
}

class FileManagerViewModel(
    private val connectionManager: SshConnectionManager,
    private val sftpRepository: SftpRepository,
    private val transferQueueManager: TransferQueueManager,
    private val preferencesRepository: AppPreferencesRepository,
    private val localSafBrowser: LocalSafBrowser,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileManagerUiState())
    val uiState: StateFlow<FileManagerUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var listJob: Job? = null
    private var searchJob: Job? = null
    private var lastConnectedProfileId: Long? = null

    init {
        viewModelScope.launch {
            preferencesRepository.settings.collect { settings ->
                val changed = settings.showHiddenFiles != _uiState.value.showHidden
                _uiState.update { it.copy(showHidden = settings.showHiddenFiles) }
                if (changed && _uiState.value.isConnected) {
                    refresh()
                }
            }
        }

        viewModelScope.launch {
            transferQueueManager.tasks.collect { tasks ->
                _uiState.update { it.copy(transfers = tasks) }
            }
        }

        viewModelScope.launch {
            transferQueueManager.completedRemoteDirs.collect { completedDir ->
                if (_uiState.value.isConnected &&
                    SftpRepository.normalizeRemotePath(_uiState.value.currentPath) == completedDir
                ) {
                    refresh()
                }
            }
        }

        viewModelScope.launch {
            connectionManager.connectionState.collect { state ->
                when (state) {
                    is SshConnectionState.Connected -> {
                        val profileChanged = lastConnectedProfileId != state.profile.id
                        lastConnectedProfileId = state.profile.id
                        _uiState.update { it.copy(isConnected = true, isReconnecting = false) }
                        if (profileChanged || _uiState.value.entries.isEmpty()) {
                            loadInitialDirectory()
                        } else {
                            // Reconnected to same server: preserve currentPath and silently refresh
                            refresh()
                        }
                    }

                    is SshConnectionState.Reconnecting -> {
                        sftpRepository.closeCached()
                        _uiState.update {
                            it.copy(
                                isReconnecting = true,
                                isLoading = false,
                            )
                        }
                    }

                    else -> {
                        sftpRepository.closeCached()
                        _uiState.update {
                            it.copy(
                                isConnected = false,
                                isReconnecting = false,
                                isLoading = false,
                                selectedPaths = emptySet(),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun loadInitialDirectory() {
        listJob?.cancel()
        listJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                val home = sftpRepository.resolveInitialDirectory()
                val raw = sftpRepository.listDirectory(home, _uiState.value.showHidden)
                home to sortEntries(raw, _uiState.value.sortBy, _uiState.value.sortDirection)
            }.onSuccess { (home, sorted) ->
                _uiState.update {
                    it.copy(
                        homePath = home,
                        currentPath = home,
                        entries = sorted,
                        isLoading = false,
                        errorMessage = null,
                        selectedPaths = emptySet(),
                        expandedTreePaths = setOf("/", home),
                    )
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = err.message ?: "Не удалось открыть каталог",
                    )
                }
            }
        }
    }

    fun openDirectory(path: String) {
        val normalized = SftpRepository.normalizeRemotePath(path)
        listJob?.cancel()
        listJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    currentPath = normalized,
                    isLoading = true,
                    errorMessage = null,
                    selectedPaths = emptySet(),
                )
            }
            runCatching {
                sftpRepository.listDirectory(normalized, _uiState.value.showHidden)
            }.onSuccess { raw ->
                _uiState.update { state ->
                    state.copy(
                        entries = sortEntries(raw, state.sortBy, state.sortDirection),
                        isLoading = false,
                        errorMessage = null,
                    )
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = err.message ?: "Не удалось прочитать папку",
                    )
                }
            }
        }
    }

    fun navigateUp(): Boolean {
        val current = _uiState.value.currentPath
        if (current == "/") return false
        openDirectory(SftpRepository.parentRemotePath(current))
        return true
    }

    fun refresh() {
        if (!_uiState.value.isConnected) return
        openDirectory(_uiState.value.currentPath)
    }

    fun setSort(sortBy: FileSortBy) {
        _uiState.update { state ->
            val direction = if (state.sortBy == sortBy) {
                if (state.sortDirection == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
            } else {
                SortDirection.ASC
            }
            state.copy(
                sortBy = sortBy,
                sortDirection = direction,
                entries = sortEntries(state.entries, sortBy, direction),
            )
        }
    }

    fun toggleShowHidden() {
        viewModelScope.launch {
            preferencesRepository.setShowHiddenFiles(!_uiState.value.showHidden)
        }
    }

    // Selection
    fun toggleSelection(path: String) {
        _uiState.update { state ->
            val next = state.selectedPaths.toMutableSet()
            if (!next.add(path)) next.remove(path)
            state.copy(selectedPaths = next)
        }
    }

    fun selectAll() {
        _uiState.update { state ->
            state.copy(selectedPaths = state.entries.map { it.path }.toSet())
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedPaths = emptySet()) }
    }

    // Clipboard
    fun copyToClipboard(entries: List<RemoteFileEntry>) {
        if (entries.isEmpty()) return
        _uiState.update {
            it.copy(
                clipboard = FileClipboard(ClipboardOperation.COPY, entries),
                selectedPaths = emptySet(),
            )
        }
    }

    fun cutToClipboard(entries: List<RemoteFileEntry>) {
        if (entries.isEmpty()) return
        _uiState.update {
            it.copy(
                clipboard = FileClipboard(ClipboardOperation.CUT, entries),
                selectedPaths = emptySet(),
            )
        }
    }

    fun clearClipboard() {
        _uiState.update { it.copy(clipboard = null) }
    }

    fun pasteClipboard() {
        val clip = _uiState.value.clipboard ?: return
        val targetDir = _uiState.value.currentPath
        runMutation("Выполнено") {
            when (clip.operation) {
                ClipboardOperation.COPY -> sftpRepository.copyEntries(clip.entries, targetDir)
                ClipboardOperation.CUT -> sftpRepository.moveEntries(clip.entries, targetDir)
            }
            if (clip.operation == ClipboardOperation.CUT) {
                _uiState.update { it.copy(clipboard = null) }
            }
        }
    }

    // File Operations
    fun createFile(name: String, onCreated: (String) -> Unit = {}) {
        val parent = _uiState.value.currentPath
        viewModelScope.launch {
            _uiState.update { it.copy(isMutating = true) }
            runCatching {
                sftpRepository.createEmptyFile(parent, name)
            }.onSuccess { fullPath ->
                _uiState.update { it.copy(isMutating = false) }
                refresh()
                onCreated(fullPath)
            }.onFailure { err ->
                _uiState.update { it.copy(isMutating = false) }
                _messages.tryEmit(err.message ?: "Не удалось создать файл")
            }
        }
    }

    fun createFolder(name: String) {
        val parent = _uiState.value.currentPath
        runMutation("Папка создана") {
            sftpRepository.createDirectory(parent, name)
        }
    }

    fun renameEntry(entry: RemoteFileEntry, newName: String) {
        runMutation("Переименовано") {
            sftpRepository.rename(entry.path, newName)
        }
    }

    fun moveEntries(entries: List<RemoteFileEntry>, targetDir: String) {
        runMutation("Перемещено") {
            sftpRepository.moveEntries(entries, targetDir)
            _uiState.update { it.copy(selectedPaths = emptySet()) }
        }
    }

    fun deleteEntries(entries: List<RemoteFileEntry>) {
        if (entries.isEmpty()) return
        runMutation("Удалено: ${entries.size}") {
            sftpRepository.deleteEntries(entries)
            _uiState.update { it.copy(selectedPaths = emptySet()) }
        }
    }

    fun chmodEntry(entry: RemoteFileEntry, permissionsMask: Int) {
        runMutation("Права обновлены (${"%04o".format(permissionsMask and 0xFFF)})") {
            sftpRepository.chmod(entry.path, permissionsMask)
        }
    }

    // Search
    fun toggleSearch() {
        val next = !_uiState.value.searchActive
        if (!next) {
            searchJob?.cancel()
        }
        _uiState.update {
            it.copy(
                searchActive = next,
                searchResults = if (next) it.searchResults else emptyList(),
                searchPerformed = if (next) it.searchPerformed else false,
                isSearching = false,
            )
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun onSearchModeChange(mode: FileSearchMode) {
        _uiState.update { it.copy(searchMode = mode, searchResults = emptyList(), searchPerformed = false) }
    }

    fun runSearch() {
        val state = _uiState.value
        val query = state.searchQuery.trim()
        if (query.isEmpty() || !state.isConnected) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, searchPerformed = false) }
            runCatching {
                when (state.searchMode) {
                    FileSearchMode.NAME ->
                        sftpRepository.searchByName(state.currentPath, query, state.showHidden)
                    FileSearchMode.CONTENT ->
                        sftpRepository.searchByContent(state.currentPath, query, state.showHidden)
                }
            }.onSuccess { results ->
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        searchResults = results,
                        searchPerformed = true,
                    )
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                _uiState.update { it.copy(isSearching = false, searchPerformed = true) }
                _messages.tryEmit(err.message ?: "Ошибка поиска")
            }
        }
    }

    // Folder Tree
    fun loadTreeNode(path: String) {
        val normalized = SftpRepository.normalizeRemotePath(path)
        viewModelScope.launch {
            _uiState.update { it.copy(loadingTreePaths = it.loadingTreePaths + normalized) }
            runCatching {
                sftpRepository.listDirectory(normalized, _uiState.value.showHidden)
                    .filter { it.isDirectory }
                    .sortedBy { it.name.lowercase() }
            }.onSuccess { dirs ->
                _uiState.update { state ->
                    state.copy(
                        treeChildren = state.treeChildren + (normalized to dirs),
                        loadingTreePaths = state.loadingTreePaths - normalized,
                    )
                }
            }.onFailure {
                _uiState.update { it.copy(loadingTreePaths = it.loadingTreePaths - normalized) }
            }
        }
    }

    fun toggleTreeExpand(path: String) {
        val normalized = SftpRepository.normalizeRemotePath(path)
        val currentlyExpanded = normalized in _uiState.value.expandedTreePaths
        if (currentlyExpanded) {
            _uiState.update { it.copy(expandedTreePaths = it.expandedTreePaths - normalized) }
        } else {
            _uiState.update { it.copy(expandedTreePaths = it.expandedTreePaths + normalized) }
            if (normalized !in _uiState.value.treeChildren) {
                loadTreeNode(normalized)
            }
        }
    }

    // Transfers (SAF)
    fun uploadFiles(uris: List<Uri>) {
        if (uris.isEmpty() || !_uiState.value.isConnected) return
        transferQueueManager.enqueueUploads(uris, _uiState.value.currentPath)
        _messages.tryEmit("Добавлено в очередь загрузки: ${uris.size}")
    }

    fun downloadFileToUri(entry: RemoteFileEntry, destinationUri: Uri) {
        if (entry.isDirectory || !_uiState.value.isConnected) return
        transferQueueManager.enqueueDownload(
            remotePath = entry.path,
            fileName = entry.name,
            sizeBytes = entry.sizeBytes,
            destinationUri = destinationUri,
        )
        _messages.tryEmit("Скачивание «${entry.name}» начато")
    }

    fun cancelTransfer(taskId: String) = transferQueueManager.cancelTask(taskId)
    fun clearFinishedTransfers() = transferQueueManager.clearFinished()

    // Dual-pane Local SAF Browser
    fun setLocalSafFolder(treeUri: Uri) {
        localSafBrowser.takePersistablePermission(treeUri)
        val name = localSafBrowser.folderDisplayName(treeUri)
        _uiState.update {
            it.copy(
                localFolderUri = treeUri,
                localFolderName = name,
            )
        }
        refreshLocalSafFolder()
    }

    fun refreshLocalSafFolder() {
        val treeUri = _uiState.value.localFolderUri ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLocalLoading = true) }
            runCatching {
                localSafBrowser.listChildren(treeUri)
            }.onSuccess { entries ->
                _uiState.update { it.copy(localEntries = entries, isLocalLoading = false) }
            }.onFailure {
                _uiState.update { it.copy(isLocalLoading = false) }
            }
        }
    }

    fun downloadEntryToSelectedLocalFolder(entry: RemoteFileEntry) {
        val treeUri = _uiState.value.localFolderUri ?: return
        if (entry.isDirectory) return
        viewModelScope.launch {
            val destUri = localSafBrowser.createDocumentInTree(treeUri, entry.name)
            if (destUri != null) {
                downloadFileToUri(entry, destUri)
                refreshLocalSafFolder()
            } else {
                _messages.tryEmit("Не удалось создать файл в выбранной папке телефона")
            }
        }
    }

    private fun runMutation(successMessage: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isMutating = true) }
            runCatching {
                block()
            }.onSuccess {
                _uiState.update { it.copy(isMutating = false) }
                _messages.tryEmit(successMessage)
                refresh()
            }.onFailure { err ->
                if (err is CancellationException) throw err
                _uiState.update { it.copy(isMutating = false) }
                _messages.tryEmit(err.message ?: "Ошибка операции")
            }
        }
    }

    private fun sortEntries(
        list: List<RemoteFileEntry>,
        sortBy: FileSortBy,
        direction: SortDirection,
    ): List<RemoteFileEntry> {
        val comparator: Comparator<RemoteFileEntry> = when (sortBy) {
            FileSortBy.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            FileSortBy.SIZE -> compareBy<RemoteFileEntry> { it.sizeBytes }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            FileSortBy.MODIFIED -> compareBy<RemoteFileEntry> { it.modifiedAtEpochSec }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            FileSortBy.TYPE -> compareBy<RemoteFileEntry> { it.extension }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        val ordered = if (direction == SortDirection.ASC) comparator else comparator.reversed()
        return list.sortedWith(compareBy<RemoteFileEntry> { !it.isDirectory }.then(ordered))
    }

    class Factory(
        private val connectionManager: SshConnectionManager,
        private val sftpRepository: SftpRepository,
        private val transferQueueManager: TransferQueueManager,
        private val preferencesRepository: AppPreferencesRepository,
        private val localSafBrowser: LocalSafBrowser,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FileManagerViewModel(
                connectionManager = connectionManager,
                sftpRepository = sftpRepository,
                transferQueueManager = transferQueueManager,
                preferencesRepository = preferencesRepository,
                localSafBrowser = localSafBrowser,
            ) as T
        }
    }
}
