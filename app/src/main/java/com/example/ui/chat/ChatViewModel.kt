package com.example.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppPreferencesRepository
import com.example.data.opencode.OpenCodeApiClient
import com.example.data.sftp.SftpRepository
import com.example.data.ssh.SshConnectionManager
import com.example.domain.model.AttachedFileRef
import com.example.domain.model.OpenCodeAgent
import com.example.domain.model.OpenCodeEvent
import com.example.domain.model.OpenCodeFileDiff
import com.example.domain.model.OpenCodeMessage
import com.example.domain.model.OpenCodeModel
import com.example.domain.model.OpenCodePart
import com.example.domain.model.OpenCodePermissionRequest
import com.example.domain.model.OpenCodeProvider
import com.example.domain.model.OpenCodeSession
import com.example.domain.model.PermissionReplyOption
import com.example.domain.model.RemoteFileEntry
import com.example.domain.model.SseConnectionStatus
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

data class ChatUiState(
    val isConnected: Boolean = false,
    val openCodePortReachable: Boolean = false,
    val sseStatus: SseConnectionStatus = SseConnectionStatus.DISCONNECTED,
    val sessions: List<OpenCodeSession> = emptyList(),
    val isLoadingSessions: Boolean = false,
    val activeSessionId: String? = null,
    val messages: List<OpenCodeMessage> = emptyList(),
    val isLoadingMessages: Boolean = false,
    val isSending: Boolean = false,
    val errorMessage: String? = null,
    val providers: List<OpenCodeProvider> = emptyList(),
    val agents: List<OpenCodeAgent> = emptyList(),
    val selectedProviderId: String = "",
    val selectedModelId: String = "",
    val selectedAgentName: String = "build",
    val composerText: String = "",
    val attachedFiles: List<AttachedFileRef> = emptyList(),
    val pickerPath: String = "/",
    val pickerEntries: List<RemoteFileEntry> = emptyList(),
    val isPickerLoading: Boolean = false,
    val pendingPermissions: List<OpenCodePermissionRequest> = emptyList(),
    val isSubmittingPermission: Boolean = false,
    val sessionDiffs: List<OpenCodeFileDiff> = emptyList(),
    val isLoadingDiffs: Boolean = false,
) {
    val activeSession: OpenCodeSession?
        get() = sessions.firstOrNull { it.id == activeSessionId }

    val isActiveSessionBusy: Boolean
        get() = activeSession?.isBusy == true || isSending

    val currentPermissionPrompt: OpenCodePermissionRequest?
        get() = pendingPermissions.firstOrNull { it.sessionId == activeSessionId }
            ?: pendingPermissions.firstOrNull()

    val selectedModelDisplay: String?
        get() = providers
            .flatMap { it.models }
            .firstOrNull { it.providerId == selectedProviderId && it.modelId == selectedModelId }
            ?.displayName
            ?: selectedModelId.takeIf { it.isNotBlank() }
}

class ChatViewModel(
    private val connectionManager: SshConnectionManager,
    private val apiClient: OpenCodeApiClient,
    private val sftpRepository: SftpRepository,
    private val preferencesRepository: AppPreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _toastMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastMessages: SharedFlow<String> = _toastMessages.asSharedFlow()

    private var sseJob: Job? = null
    private var messagesJob: Job? = null
    private var lastTunnelPort: Int? = null

    init {
        viewModelScope.launch {
            preferencesRepository.settings.collect { settings ->
                _uiState.update { state ->
                    state.copy(
                        selectedProviderId = state.selectedProviderId.ifBlank { settings.defaultProviderId },
                        selectedModelId = state.selectedModelId.ifBlank { settings.defaultModelId },
                        selectedAgentName = state.selectedAgentName.ifBlank { settings.defaultAgentName },
                    )
                }
            }
        }

        viewModelScope.launch {
            connectionManager.connectionState.collect { conn ->
                when (conn) {
                    is SshConnectionState.Connected -> {
                        val tunnelChanged = lastTunnelPort != conn.localTunnelPort
                        lastTunnelPort = conn.localTunnelPort
                        _uiState.update {
                            it.copy(
                                isConnected = true,
                                openCodePortReachable = conn.openCodePortReachable,
                            )
                        }
                        if (tunnelChanged) {
                            refreshAll()
                            startSseStream(conn.localTunnelPort)
                        }
                    }

                    else -> {
                        stopSseStream()
                        lastTunnelPort = null
                        _uiState.update {
                            it.copy(
                                isConnected = false,
                                openCodePortReachable = false,
                                sseStatus = SseConnectionStatus.DISCONNECTED,
                                isSending = false,
                                pendingPermissions = emptyList(),
                                sessionDiffs = emptyList(),
                            )
                        }
                    }
                }
            }
        }
    }

    fun refreshAll() {
        if (!_uiState.value.isConnected) return
        loadSessions()
        loadProvidersAndAgents()
        loadPendingPermissions()
        _uiState.value.activeSessionId?.let { sessionId ->
            loadMessages(sessionId)
            loadSessionDiffs(sessionId)
        }
    }

    fun loadSessions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingSessions = true, errorMessage = null) }
            runCatching {
                apiClient.listSessions()
            }.onSuccess { sessions ->
                _uiState.update { state ->
                    val busyIds = state.sessions.filter { it.isBusy }.map { it.id }.toSet()
                    val merged = sessions.map { s ->
                        if (s.id in busyIds) s.copy(isBusy = true) else s
                    }
                    state.copy(
                        sessions = merged,
                        isLoadingSessions = false,
                        errorMessage = null,
                    )
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                _uiState.update {
                    it.copy(
                        isLoadingSessions = false,
                        errorMessage = err.message ?: "Не удалось подключиться к OpenCode API",
                    )
                }
            }
        }
    }

    private fun loadProvidersAndAgents() {
        viewModelScope.launch {
            runCatching { apiClient.getProviders() }
                .onSuccess { providers ->
                    _uiState.update { state ->
                        val firstModel = providers.firstOrNull()?.models?.firstOrNull()
                        val resolvedProvider = state.selectedProviderId.ifBlank { firstModel?.providerId.orEmpty() }
                        val resolvedModel = state.selectedModelId.ifBlank { firstModel?.modelId.orEmpty() }
                        state.copy(
                            providers = providers,
                            selectedProviderId = resolvedProvider,
                            selectedModelId = resolvedModel,
                        )
                    }
                }

            runCatching { apiClient.getAgents() }
                .onSuccess { agents ->
                    _uiState.update { state ->
                        val resolvedAgent = if (agents.any { it.name == state.selectedAgentName }) {
                            state.selectedAgentName
                        } else {
                            agents.firstOrNull()?.name ?: "build"
                        }
                        state.copy(
                            agents = agents,
                            selectedAgentName = resolvedAgent,
                        )
                    }
                }
        }
    }

    fun createSession(title: String? = null) {
        viewModelScope.launch {
            runCatching {
                apiClient.createSession(title)
            }.onSuccess { created ->
                _uiState.update { state ->
                    val updated = listOf(created) + state.sessions.filterNot { it.id == created.id }
                    state.copy(
                        sessions = updated,
                        activeSessionId = created.id,
                        messages = emptyList(),
                        errorMessage = null,
                    )
                }
            }.onFailure { err ->
                _toastMessages.tryEmit(err.message ?: "Не удалось создать сессию")
            }
        }
    }

    fun openSession(sessionId: String) {
        _uiState.update { it.copy(activeSessionId = sessionId, sessionDiffs = emptyList(), errorMessage = null) }
        loadMessages(sessionId)
        loadSessionDiffs(sessionId)
    }

    fun closeActiveSession() {
        _uiState.update { it.copy(activeSessionId = null, sessionDiffs = emptyList()) }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch {
            runCatching {
                apiClient.renameSession(sessionId, newTitle)
            }.onSuccess { renamed ->
                _uiState.update { state ->
                    state.copy(
                        sessions = state.sessions.map {
                            if (it.id == sessionId) it.copy(title = renamed.title) else it
                        },
                    )
                }
            }.onFailure { err ->
                _toastMessages.tryEmit(err.message ?: "Не удалось переименовать сессию")
            }
        }
    }

    fun forkSession(sessionId: String, messageId: String? = null) {
        viewModelScope.launch {
            runCatching {
                apiClient.forkSession(sessionId, messageId)
            }.onSuccess { forked ->
                _uiState.update { state ->
                    val updated = listOf(forked) + state.sessions.filterNot { it.id == forked.id }
                    state.copy(sessions = updated, activeSessionId = forked.id)
                }
                loadMessages(forked.id)
                _toastMessages.tryEmit("Создан форк сессии")
            }.onFailure { err ->
                _toastMessages.tryEmit(err.message ?: "Не удалось создать форк сессии")
            }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            runCatching {
                apiClient.deleteSession(sessionId)
            }.onSuccess {
                _uiState.update { state ->
                    val remaining = state.sessions.filterNot { it.id == sessionId }
                    state.copy(
                        sessions = remaining,
                        activeSessionId = if (state.activeSessionId == sessionId) null else state.activeSessionId,
                        messages = if (state.activeSessionId == sessionId) emptyList() else state.messages,
                    )
                }
            }.onFailure { err ->
                _toastMessages.tryEmit(err.message ?: "Не удалось удалить сессию")
            }
        }
    }

    fun loadMessages(sessionId: String) {
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMessages = true) }
            runCatching {
                apiClient.getMessages(sessionId)
            }.onSuccess { msgs ->
                if (_uiState.value.activeSessionId == sessionId) {
                    _uiState.update {
                        it.copy(
                            messages = msgs,
                            isLoadingMessages = false,
                        )
                    }
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                _uiState.update { it.copy(isLoadingMessages = false) }
                _toastMessages.tryEmit(err.message ?: "Не удалось загрузить сообщения")
            }
        }
    }

    fun onComposerTextChange(value: String) {
        _uiState.update { it.copy(composerText = value) }
    }

    fun attachFile(path: String, name: String) {
        val ref = AttachedFileRef(path = path, name = name)
        _uiState.update { state ->
            if (state.attachedFiles.any { it.path == path }) state
            else state.copy(attachedFiles = state.attachedFiles + ref)
        }
    }

    fun removeAttachedFile(path: String) {
        _uiState.update { state ->
            state.copy(attachedFiles = state.attachedFiles.filterNot { it.path == path })
        }
    }

    fun selectModel(model: OpenCodeModel) {
        _uiState.update {
            it.copy(
                selectedProviderId = model.providerId,
                selectedModelId = model.modelId,
            )
        }
        viewModelScope.launch {
            preferencesRepository.setDefaultModel(model.providerId, model.modelId)
        }
    }

    fun selectAgent(agentName: String) {
        _uiState.update { it.copy(selectedAgentName = agentName) }
        viewModelScope.launch {
            preferencesRepository.setDefaultAgent(agentName)
        }
    }

    fun sendMessage() {
        val state = _uiState.value
        val text = state.composerText.trim()
        val attachments = state.attachedFiles
        if (text.isEmpty() && attachments.isEmpty()) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSending = true,
                    composerText = "",
                    attachedFiles = emptyList(),
                )
            }
            try {
                val targetSessionId = state.activeSessionId ?: run {
                    val created = apiClient.createSession()
                    _uiState.update { s ->
                        s.copy(
                            sessions = listOf(created) + s.sessions.filterNot { it.id == created.id },
                            activeSessionId = created.id,
                            messages = emptyList(),
                        )
                    }
                    created.id
                }

                markSessionBusy(targetSessionId, busy = true)
                apiClient.sendMessage(
                    sessionId = targetSessionId,
                    text = text,
                    attachments = attachments,
                    providerId = state.selectedProviderId.takeIf { it.isNotBlank() },
                    modelId = state.selectedModelId.takeIf { it.isNotBlank() },
                    agent = state.selectedAgentName.takeIf { it.isNotBlank() },
                )
                _uiState.update { it.copy(isSending = false) }
                // Sync snapshot if SSE hasn't delivered the new user message yet
                if (_uiState.value.sseStatus != SseConnectionStatus.CONNECTED) {
                    loadMessages(targetSessionId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        isSending = false,
                        composerText = if (it.composerText.isEmpty()) text else it.composerText,
                        attachedFiles = if (it.attachedFiles.isEmpty()) attachments else it.attachedFiles,
                    )
                }
                _uiState.value.activeSessionId?.let { markSessionBusy(it, busy = false) }
                _toastMessages.tryEmit(t.message ?: "Не удалось отправить сообщение")
            }
        }
    }

    fun abortActiveSession() {
        val sessionId = _uiState.value.activeSessionId ?: return
        viewModelScope.launch {
            runCatching {
                apiClient.abortSession(sessionId)
            }.onSuccess {
                markSessionBusy(sessionId, busy = false)
                _uiState.update { it.copy(isSending = false) }
            }.onFailure { err ->
                _toastMessages.tryEmit(err.message ?: "Не удалось остановить генерацию")
            }
        }
    }

    fun revertToMessage(messageId: String) {
        val sessionId = _uiState.value.activeSessionId ?: return
        viewModelScope.launch {
            runCatching {
                apiClient.revertSession(sessionId, messageId)
            }.onSuccess {
                _toastMessages.tryEmit("Изменения откатаны")
                loadMessages(sessionId)
                loadSessionDiffs(sessionId)
            }.onFailure { err ->
                _toastMessages.tryEmit(err.message ?: "Не удалось откатить изменения")
            }
        }
    }

    fun loadSessionDiffs(sessionId: String? = _uiState.value.activeSessionId) {
        if (sessionId.isNullOrBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingDiffs = true) }
            runCatching {
                apiClient.getSessionDiff(sessionId)
            }.onSuccess { diffs ->
                if (_uiState.value.activeSessionId == sessionId) {
                    _uiState.update { it.copy(sessionDiffs = diffs, isLoadingDiffs = false) }
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                _uiState.update { it.copy(isLoadingDiffs = false) }
            }
        }
    }

    fun loadPendingPermissions() {
        viewModelScope.launch {
            runCatching {
                apiClient.listPendingPermissions()
            }.onSuccess { list ->
                if (list.isNotEmpty()) {
                    _uiState.update { state ->
                        val merged = (state.pendingPermissions + list).distinctBy { it.id }
                        state.copy(pendingPermissions = merged)
                    }
                }
            }
        }
    }

    fun replyPermission(request: OpenCodePermissionRequest, option: PermissionReplyOption) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmittingPermission = true) }
            runCatching {
                apiClient.replyPermission(request, option)
            }.onSuccess {
                _uiState.update { state ->
                    state.copy(
                        isSubmittingPermission = false,
                        pendingPermissions = state.pendingPermissions.filterNot { it.id == request.id },
                    )
                }
                val toast = when (option) {
                    PermissionReplyOption.ONCE -> "Разрешено один раз"
                    PermissionReplyOption.ALWAYS -> "Разрешено для сессии"
                    PermissionReplyOption.REJECT -> "Действие отклонено"
                }
                _toastMessages.tryEmit(toast)
            }.onFailure { err ->
                _uiState.update { it.copy(isSubmittingPermission = false) }
                _toastMessages.tryEmit(err.message ?: "Не удалось отправить ответ на разрешение")
            }
        }
    }

    // Inline @ File Picker via SFTP
    fun openFilePicker(initialPath: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isPickerLoading = true) }
            val resolvedPath = initialPath ?: runCatching {
                sftpRepository.resolveInitialDirectory()
            }.getOrDefault("/")
            loadPickerDirectory(resolvedPath)
        }
    }

    fun loadPickerDirectory(path: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(pickerPath = path, isPickerLoading = true) }
            runCatching {
                sftpRepository.listDirectory(path, includeHidden = false)
                    .sortedWith(compareBy<RemoteFileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
            }.onSuccess { entries ->
                _uiState.update {
                    it.copy(
                        pickerPath = path,
                        pickerEntries = entries,
                        isPickerLoading = false,
                    )
                }
            }.onFailure {
                _uiState.update { it.copy(isPickerLoading = false) }
            }
        }
    }

    private fun startSseStream(tunnelPort: Int) {
        stopSseStream()
        sseJob = viewModelScope.launch {
            apiClient.observeEvents(tunnelPort).collect { (status, event) ->
                _uiState.update { it.copy(sseStatus = status) }
                if (event != null) {
                    applyEvent(event)
                }
            }
        }
    }

    private fun stopSseStream() {
        sseJob?.cancel()
        sseJob = null
    }

    private fun applyEvent(event: OpenCodeEvent) {
        when (event) {
            is OpenCodeEvent.SessionUpdated -> {
                _uiState.update { state ->
                    val existing = state.sessions.firstOrNull { it.id == event.session.id }
                    val updatedSession = event.session.copy(isBusy = existing?.isBusy ?: false)
                    val list = (listOf(updatedSession) + state.sessions.filterNot { it.id == updatedSession.id })
                        .sortedByDescending { it.updatedAtMillis }
                    state.copy(sessions = list)
                }
            }

            is OpenCodeEvent.SessionDeleted -> {
                _uiState.update { state ->
                    state.copy(
                        sessions = state.sessions.filterNot { it.id == event.sessionId },
                        activeSessionId = if (state.activeSessionId == event.sessionId) null else state.activeSessionId,
                    )
                }
            }

            is OpenCodeEvent.SessionStatusChanged -> {
                markSessionBusy(event.sessionId, event.isBusy)
            }

            is OpenCodeEvent.MessageUpdated -> {
                if (event.message.sessionId != _uiState.value.activeSessionId) return
                _uiState.update { state ->
                    val existing = state.messages.firstOrNull { it.id == event.message.id }
                    val merged = if (existing != null) {
                        event.message.copy(parts = existing.parts)
                    } else {
                        event.message
                    }
                    val updatedList = (state.messages.filterNot { it.id == merged.id } + merged)
                        .sortedBy { it.createdAtMillis }
                    state.copy(messages = updatedList)
                }
            }

            is OpenCodeEvent.PartUpdated -> {
                val part = event.part
                if (part.sessionId != _uiState.value.activeSessionId) return
                _uiState.update { state ->
                    val targetMsg = state.messages.firstOrNull { it.id == part.messageId }
                        ?: OpenCodeMessage(
                            id = part.messageId,
                            sessionId = part.sessionId,
                            role = "assistant",
                            createdAtMillis = System.currentTimeMillis(),
                        )
                    val updatedParts = upsertPart(targetMsg.parts, part, event.delta)
                    val updatedMsg = targetMsg.copy(parts = updatedParts)
                    val updatedMsgs = (state.messages.filterNot { it.id == updatedMsg.id } + updatedMsg)
                        .sortedBy { it.createdAtMillis }
                    state.copy(messages = updatedMsgs)
                }
            }

            is OpenCodeEvent.PartDelta -> {
                if (event.sessionId != _uiState.value.activeSessionId) return
                _uiState.update { state ->
                    val targetMsg = state.messages.firstOrNull { it.id == event.messageId }
                        ?: return@update state
                    val updatedParts = targetMsg.parts.map { existing ->
                        if (existing.id != event.partId) {
                            existing
                        } else {
                            when (existing) {
                                is OpenCodePart.Text -> existing.copy(text = existing.text + event.delta)
                                is OpenCodePart.Reasoning -> existing.copy(text = existing.text + event.delta)
                                else -> existing
                            }
                        }
                    }
                    val updatedMsgs = state.messages.map {
                        if (it.id == targetMsg.id) it.copy(parts = updatedParts) else it
                    }
                    state.copy(messages = updatedMsgs)
                }
            }

            is OpenCodeEvent.PermissionAsked -> {
                _uiState.update { state ->
                    val withoutDup = state.pendingPermissions.filterNot { it.id == event.request.id }
                    state.copy(pendingPermissions = withoutDup + event.request)
                }
            }

            is OpenCodeEvent.PermissionReplied -> {
                _uiState.update { state ->
                    state.copy(
                        pendingPermissions = state.pendingPermissions.filterNot { it.id == event.requestId },
                    )
                }
            }

            is OpenCodeEvent.SessionDiffUpdated -> {
                if (event.sessionId == _uiState.value.activeSessionId) {
                    _uiState.update { it.copy(sessionDiffs = event.diffs) }
                }
            }
        }
    }

    private fun upsertPart(
        existingParts: List<OpenCodePart>,
        incoming: OpenCodePart,
        delta: String?,
    ): List<OpenCodePart> {
        val index = existingParts.indexOfFirst { it.id == incoming.id }
        if (index < 0) return existingParts + incoming
        val current = existingParts[index]
        val resolved = when {
            incoming is OpenCodePart.Text && current is OpenCodePart.Text &&
                incoming.text.isEmpty() && !delta.isNullOrEmpty() ->
                current.copy(text = current.text + delta)

            incoming is OpenCodePart.Reasoning && current is OpenCodePart.Reasoning &&
                incoming.text.isEmpty() && !delta.isNullOrEmpty() ->
                current.copy(text = current.text + delta)

            else -> incoming
        }
        return existingParts.toMutableList().apply { set(index, resolved) }
    }

    private fun markSessionBusy(sessionId: String, busy: Boolean) {
        _uiState.update { state ->
            state.copy(
                sessions = state.sessions.map {
                    if (it.id == sessionId) it.copy(isBusy = busy) else it
                },
            )
        }
    }

    override fun onCleared() {
        stopSseStream()
        super.onCleared()
    }

    class Factory(
        private val connectionManager: SshConnectionManager,
        private val apiClient: OpenCodeApiClient,
        private val sftpRepository: SftpRepository,
        private val preferencesRepository: AppPreferencesRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(
                connectionManager = connectionManager,
                apiClient = apiClient,
                sftpRepository = sftpRepository,
                preferencesRepository = preferencesRepository,
            ) as T
        }
    }
}
