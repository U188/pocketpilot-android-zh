package com.example.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.git.GitRepository
import com.example.data.local.AppPreferencesRepository
import com.example.data.opencode.ChatMarkdownExporter
import com.example.data.opencode.ImageAttachmentProcessor
import com.example.data.opencode.OpenCodeApiClient
import com.example.data.opencode.OpenCodeConfigHelper
import com.example.data.opencode.OpenCodeHttpException
import com.example.data.opencode.OpenCodeServerInstaller
import com.example.data.opencode.ProviderApplyOutcome
import com.example.data.repository.PromptTemplateRepository
import com.example.data.repository.UsageRepository
import com.example.data.sftp.SftpRepository
import com.example.data.ssh.SshConnectionManager
import com.example.domain.model.AttachedFileRef
import com.example.domain.model.AttachedImageRef
import com.example.domain.model.ConfiguredProviderInfo
import com.example.domain.model.CustomModelEntry
import com.example.domain.model.CustomProviderSaveRequest
import com.example.domain.model.InstallStepStatus
import com.example.domain.model.ModelPricing
import com.example.domain.model.ModelTestResult
import com.example.domain.model.OpenCodeAgent
import com.example.domain.model.OpenCodeEvent
import com.example.domain.model.OpenCodeFileDiff
import com.example.domain.model.OpenCodeMessage
import com.example.domain.model.OpenCodeModel
import com.example.domain.model.OpenCodePart
import com.example.domain.model.OpenCodePermissionRequest
import com.example.domain.model.OpenCodeProvider
import com.example.domain.model.OpenCodeServerStatus
import com.example.domain.model.OpenCodeSession
import com.example.domain.model.OpenCodeSetupUiState
import com.example.domain.model.PermissionReplyOption
import com.example.domain.model.PromptTemplate
import com.example.domain.model.ProviderSaveStepItem
import com.example.domain.model.ProviderSaveWarning
import com.example.domain.model.RemoteFileEntry
import com.example.domain.model.SessionContextUsage
import com.example.domain.model.SessionUsageTotals
import com.example.domain.model.SetupWizardStage
import com.example.domain.model.SseConnectionStatus
import com.example.domain.model.SshConnectionState
import com.example.domain.model.UsageCalculator
import com.example.service.AgentNotificationHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ChatUiState(
    val isConnected: Boolean = false,
    val isReconnecting: Boolean = false,
    val openCodePort: Int = 4096,
    val openCodePortReachable: Boolean = false,
    val sseStatus: SseConnectionStatus = SseConnectionStatus.DISCONNECTED,
    val setup: OpenCodeSetupUiState = OpenCodeSetupUiState(),
    val sessions: List<OpenCodeSession> = emptyList(),
    val isLoadingSessions: Boolean = false,
    val activeSessionId: String? = null,
    val messages: List<OpenCodeMessage> = emptyList(),
    val isLoadingMessages: Boolean = false,
    val isSending: Boolean = false,
    val errorMessage: String? = null,
    val isAuthRequired: Boolean = false,
    val isSyncingAuth: Boolean = false,
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
    // Block B: 60s response inactivity watchdog
    val isResponseStalled: Boolean = false,
    val pendingSwitchModelAndRetry: Boolean = false,
    // Block C: Project workspace folder selection
    val activeWorkspacePath: String = "",
    val homeDirectoryPath: String = "",
    val recentWorkspaces: List<String> = emptyList(),
    val workspacePickerPath: String = "/",
    val workspacePickerFolders: List<RemoteFileEntry> = emptyList(),
    val isWorkspacePickerLoading: Boolean = false,
    val workspacePickerError: String? = null,
    val isCreatingWorkspaceFolder: Boolean = false,
    // Token & cost pricing rules from Room
    val modelPricingByKey: Map<String, ModelPricing> = emptyMap(),
    // Mobile Chat v2: Session search, photo attachments, prompt templates, live Git badge
    val sessionSearchQuery: String = "",
    val attachedImages: List<AttachedImageRef> = emptyList(),
    val isProcessingImages: Boolean = false,
    val promptTemplates: List<PromptTemplate> = emptyList(),
    val activeGitBranch: String? = null,
    val activeGitDirtyCount: Int = 0,
) {
    val activeSession: OpenCodeSession?
        get() = sessions.firstOrNull { it.id == activeSessionId }

    val filteredSessions: List<OpenCodeSession>
        get() {
            val q = sessionSearchQuery.trim().lowercase()
            if (q.isEmpty()) return sessions
            return sessions.filter { s ->
                s.title.lowercase().contains(q) || s.id.lowercase().contains(q)
            }
        }

    val activeSlashQuery: String?
        get() = PromptTemplateRepository.extractActiveSlashQuery(composerText)

    val matchingSlashTemplates: List<PromptTemplate>
        get() {
            val q = activeSlashQuery ?: return emptyList()
            return PromptTemplateRepository.filterBySlashQuery(promptTemplates, q)
        }

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

    val sessionUsageTotals: SessionUsageTotals
        get() = UsageCalculator.computeSessionTotals(messages, modelPricingByKey)

    val sessionContextUsage: SessionContextUsage?
        get() = UsageCalculator.computeContextUsage(
            messages = messages,
            providers = providers,
            selectedProviderId = selectedProviderId,
            selectedModelId = selectedModelId,
        )
}

class ChatViewModel(
    private val connectionManager: SshConnectionManager,
    private val apiClient: OpenCodeApiClient,
    private val sftpRepository: SftpRepository,
    private val preferencesRepository: AppPreferencesRepository,
    private val serverInstaller: OpenCodeServerInstaller,
    private val usageRepository: UsageRepository,
    private val agentNotificationHelper: AgentNotificationHelper,
    private val promptTemplateRepository: PromptTemplateRepository,
    private val gitRepository: GitRepository,
    private val imageAttachmentProcessor: ImageAttachmentProcessor,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _toastMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastMessages: SharedFlow<String> = _toastMessages.asSharedFlow()

    private var sseJob: Job? = null
    private var messagesJob: Job? = null
    private var setupJob: Job? = null
    private var draftSaveJob: Job? = null
    private var inactivityWatchdogJob: Job? = null
    private var gitBadgeJob: Job? = null
    private var lastTunnelPort: Int? = null
    private var lastProfileId: Long? = null
    private var restoredDraftOnce = false

    private var activeWorkspaceByProfile: Map<Long, String> = emptyMap()
    private var recentWorkspacesByProfile: Map<Long, List<String>> = emptyMap()
    private var lastSessionEventAtMs: Long = 0L
    private var lastSessionError: String? = null
    private var lastSentPromptText: String = ""
    private var lastSentAttachments: List<AttachedFileRef> = emptyList()

    init {
        viewModelScope.launch {
            runCatching { promptTemplateRepository.ensureDefaultsSeeded() }
            promptTemplateRepository.observeAll().collect { templates ->
                _uiState.update { it.copy(promptTemplates = templates) }
            }
        }

        viewModelScope.launch {
            usageRepository.allPricingMap.collect { pricingMap ->
                _uiState.update { it.copy(modelPricingByKey = pricingMap) }
            }
        }

        viewModelScope.launch {
            preferencesRepository.settings.collect { settings ->
                activeWorkspaceByProfile = settings.activeWorkspaceByProfile
                recentWorkspacesByProfile = settings.recentWorkspacesByProfile
                val currentProfileId = lastProfileId
                val savedActiveWs = currentProfileId?.let { activeWorkspaceByProfile[it] }
                val savedRecentWs = currentProfileId?.let { recentWorkspacesByProfile[it] }.orEmpty()

                _uiState.update { state ->
                    val shouldRestore = !restoredDraftOnce
                    if (shouldRestore) restoredDraftOnce = true
                    state.copy(
                        selectedProviderId = state.selectedProviderId.ifBlank { settings.defaultProviderId },
                        selectedModelId = state.selectedModelId.ifBlank { settings.defaultModelId },
                        selectedAgentName = state.selectedAgentName.ifBlank { settings.defaultAgentName },
                        activeSessionId = if (shouldRestore && state.activeSessionId == null) {
                            settings.lastChatSessionId
                        } else {
                            state.activeSessionId
                        },
                        composerText = if (shouldRestore && state.composerText.isEmpty()) {
                            settings.lastChatDraft
                        } else {
                            state.composerText
                        },
                        activeWorkspacePath = state.activeWorkspacePath.ifBlank { savedActiveWs.orEmpty() },
                        recentWorkspaces = savedRecentWs.ifEmpty { state.recentWorkspaces },
                    )
                }
            }
        }

        viewModelScope.launch {
            connectionManager.connectionState.collect { conn ->
                when (conn) {
                    is SshConnectionState.Connected -> {
                        val profileChanged = lastProfileId != null && lastProfileId != conn.profile.id
                        val wasReconnecting = _uiState.value.isReconnecting
                        val tunnelChanged = lastTunnelPort != conn.localTunnelPort
                        lastProfileId = conn.profile.id
                        lastTunnelPort = conn.localTunnelPort

                        val savedWorkspace = activeWorkspaceByProfile[conn.profile.id].orEmpty()
                        val savedRecent = recentWorkspacesByProfile[conn.profile.id].orEmpty()
                        val effectiveWorkspace = if (profileChanged) {
                            savedWorkspace
                        } else {
                            _uiState.value.activeWorkspacePath.ifBlank { savedWorkspace }
                        }
                        if (effectiveWorkspace.isNotBlank()) {
                            apiClient.setActiveDirectory(effectiveWorkspace)
                        } else if (profileChanged) {
                            apiClient.setActiveDirectory(null)
                        }

                        _uiState.update { state ->
                            state.copy(
                                isConnected = true,
                                isReconnecting = false,
                                openCodePort = conn.profile.openCodePort,
                                openCodePortReachable = conn.openCodePortReachable,
                                activeSessionId = if (profileChanged) null else state.activeSessionId,
                                messages = if (profileChanged) emptyList() else state.messages,
                                sessions = if (profileChanged) emptyList() else state.sessions,
                                sessionDiffs = if (profileChanged) emptyList() else state.sessionDiffs,
                                isResponseStalled = if (profileChanged) false else state.isResponseStalled,
                                activeWorkspacePath = effectiveWorkspace,
                                recentWorkspaces = savedRecent,
                                setup = if (profileChanged) {
                                    OpenCodeSetupUiState(
                                        serverStatus = OpenCodeServerStatus.CHECKING,
                                        steps = OpenCodeConfigHelper.buildDefaultSteps(conn.profile.openCodePort),
                                    )
                                } else {
                                    state.setup
                                },
                            )
                        }

                        if (!profileChanged && wasReconnecting && _uiState.value.setup.serverStatus == OpenCodeServerStatus.RUNNING) {
                            // Restore tunnel & SSE seamlessly without resetting chat session or draft
                            startSseStream(conn.localTunnelPort)
                            refreshAll()
                        } else if (tunnelChanged || profileChanged || _uiState.value.setup.serverStatus == OpenCodeServerStatus.CHECKING) {
                            checkOpenCodeServer()
                        }
                    }

                    is SshConnectionState.Reconnecting -> {
                        stopSseStream()
                        cancelInactivityWatchdog()
                        lastTunnelPort = null
                        _uiState.update {
                            it.copy(
                                isConnected = false,
                                isReconnecting = true,
                                sseStatus = SseConnectionStatus.RECONNECTING,
                            )
                        }
                    }

                    else -> {
                        stopSseStream()
                        cancelInactivityWatchdog()
                        setupJob?.cancel()
                        lastTunnelPort = null
                        apiClient.setActiveDirectory(null)
                        _uiState.update {
                            it.copy(
                                isConnected = false,
                                isReconnecting = false,
                                openCodePortReachable = false,
                                sseStatus = SseConnectionStatus.DISCONNECTED,
                                isSending = false,
                                isResponseStalled = false,
                                pendingSwitchModelAndRetry = false,
                                isAuthRequired = false,
                                isSyncingAuth = false,
                                pendingPermissions = emptyList(),
                                sessionDiffs = emptyList(),
                                setup = OpenCodeSetupUiState(),
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
            val initialAttempt = runCatching { apiClient.listSessions() }
            if (initialAttempt.isSuccess) {
                applyLoadedSessions(initialAttempt.getOrThrow())
                return@launch
            }

            val err = initialAttempt.exceptionOrNull()!!
            if (err is CancellationException) throw err

            val isUnauthorized = isHttp401Error(err)
            if (isUnauthorized) {
                val connected = connectionManager.connectionState.value as? SshConnectionState.Connected
                if (connected != null) {
                    val env = _uiState.value.setup.envInfo
                    val synced = runCatching {
                        serverInstaller.syncOpenCodeServerAuthFromServer(
                            profileId = connected.profile.id,
                            homeDir = env.homeDir,
                            isRoot = env.isRoot,
                        )
                    }.getOrNull()

                    if (synced != null) {
                        apiClient.invalidateDiscoveryCache()
                        val retryAttempt = runCatching { apiClient.listSessions() }
                        if (retryAttempt.isSuccess) {
                            applyLoadedSessions(retryAttempt.getOrThrow())
                            startSseStream(connected.localTunnelPort)
                            loadProvidersAndAgents()
                            loadPendingPermissions()
                            _toastMessages.tryEmit("已同步 OpenCode 服务器密码")
                            return@launch
                        }
                    }
                }

                _uiState.update {
                    it.copy(
                        isLoadingSessions = false,
                        isAuthRequired = true,
                        errorMessage = "OpenCode 服务器需要密码（HTTP 401）。请从服务器同步密码或手动输入。",
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    isLoadingSessions = false,
                    isAuthRequired = false,
                    errorMessage = err.message ?: "无法连接到 OpenCode API",
                )
            }
        }
    }

    private fun applyLoadedSessions(sessions: List<OpenCodeSession>) {
        _uiState.update { state ->
            val busyIds = state.sessions.filter { it.isBusy }.map { it.id }.toSet()
            val merged = sessions.map { s ->
                if (s.id in busyIds) s.copy(isBusy = true) else s
            }
            state.copy(
                sessions = merged,
                isLoadingSessions = false,
                isAuthRequired = false,
                isSyncingAuth = false,
                errorMessage = null,
            )
        }
    }

    private fun isHttp401Error(err: Throwable): Boolean {
        if (err is OpenCodeHttpException && err.statusCode == 401) return true
        val msg = err.message.orEmpty()
        return msg.contains("HTTP 401", ignoreCase = true) ||
            msg.contains("401 Unauthorized", ignoreCase = true)
    }

    fun syncOpenCodeAuthAndRetry() {
        val connected = connectionManager.connectionState.value as? SshConnectionState.Connected ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncingAuth = true) }
            val env = _uiState.value.setup.envInfo
            val synced = runCatching {
                serverInstaller.syncOpenCodeServerAuthFromServer(
                    profileId = connected.profile.id,
                    homeDir = env.homeDir,
                    isRoot = env.isRoot,
                )
            }.getOrNull()
            _uiState.update { it.copy(isSyncingAuth = false) }
            if (synced != null) {
                apiClient.invalidateDiscoveryCache()
                startSseStream(connected.localTunnelPort)
                refreshAll()
                _toastMessages.tryEmit("已同步 OpenCode 服务器密码")
            } else {
                _toastMessages.tryEmit("无法在服务器上自动找到密码，请手动输入")
            }
        }
    }

    fun saveManualOpenCodeAuth(username: String, password: String) {
        val connected = connectionManager.connectionState.value as? SshConnectionState.Connected ?: return
        if (password.isEmpty()) return
        serverInstaller.saveManualOpenCodeServerAuth(
            profileId = connected.profile.id,
            username = username,
            password = password,
        )
        apiClient.invalidateDiscoveryCache()
        _uiState.update { it.copy(isAuthRequired = false, errorMessage = null) }
        startSseStream(connected.localTunnelPort)
        refreshAll()
        _toastMessages.tryEmit("OpenCode 服务器密码已保存到 Keystore")
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
                viewModelScope.launch { preferencesRepository.setLastChatSessionId(created.id) }
            }.onFailure { err ->
                _toastMessages.tryEmit(err.message ?: "无法创建会话")
            }
        }
    }

    fun openSession(sessionId: String) {
        cancelInactivityWatchdog()
        _uiState.update {
            it.copy(
                activeSessionId = sessionId,
                sessionDiffs = emptyList(),
                errorMessage = null,
                isResponseStalled = false,
            )
        }
        viewModelScope.launch { preferencesRepository.setLastChatSessionId(sessionId) }
        loadMessages(sessionId)
        loadSessionDiffs(sessionId)
        if (_uiState.value.sessions.firstOrNull { it.id == sessionId }?.isBusy == true) {
            resetInactivityWatchdog()
        }
    }

    fun closeActiveSession() {
        cancelInactivityWatchdog()
        _uiState.update {
            it.copy(
                activeSessionId = null,
                sessionDiffs = emptyList(),
                isResponseStalled = false,
            )
        }
        viewModelScope.launch { preferencesRepository.setLastChatSessionId(null) }
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
                _toastMessages.tryEmit(err.message ?: "无法重命名会话")
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
                _toastMessages.tryEmit("会话分支已创建")
            }.onFailure { err ->
                _toastMessages.tryEmit(err.message ?: "无法创建会话分支")
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
                _toastMessages.tryEmit(err.message ?: "无法删除会话")
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
                val profileId = currentActiveProfileId()
                val workspace = _uiState.value.activeWorkspacePath
                if (profileId != null && msgs.isNotEmpty()) {
                    viewModelScope.launch {
                        runCatching { usageRepository.recordMessages(profileId, workspace, msgs) }
                    }
                }
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
                _toastMessages.tryEmit(err.message ?: "无法加载消息")
            }
        }
    }

    fun onComposerTextChange(value: String) {
        _uiState.update { it.copy(composerText = value) }
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            delay(250L)
            preferencesRepository.setLastChatDraft(value)
        }
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

    fun onSessionSearchChange(query: String) {
        _uiState.update { it.copy(sessionSearchQuery = query) }
    }

    fun appendVoiceText(spoken: String) {
        val clean = spoken.trim()
        if (clean.isEmpty()) return
        val current = _uiState.value.composerText
        val merged = when {
            current.isBlank() -> clean
            current.endsWith(" ") || current.endsWith("\n") -> "$current$clean"
            else -> "$current $clean"
        }
        onComposerTextChange(merged)
    }

    fun applyPromptTemplate(template: PromptTemplate) {
        val nextText = PromptTemplateRepository.applyTemplateToComposer(
            currentText = _uiState.value.composerText,
            template = template,
        )
        onComposerTextChange(nextText)
        if (template.autoSend && !_uiState.value.isActiveSessionBusy) {
            sendMessage()
        }
    }

    fun savePromptTemplate(template: PromptTemplate) {
        viewModelScope.launch {
            runCatching {
                promptTemplateRepository.save(template)
            }.onSuccess {
                _toastMessages.tryEmit("模板已保存")
            }.onFailure { err ->
                _toastMessages.tryEmit(err.message ?: "无法保存模板")
            }
        }
    }

    fun deletePromptTemplate(id: Long) {
        viewModelScope.launch {
            runCatching {
                promptTemplateRepository.delete(id)
            }.onSuccess {
                _toastMessages.tryEmit("模板已删除")
            }.onFailure { err ->
                _toastMessages.tryEmit(err.message ?: "无法删除模板")
            }
        }
    }

    fun attachImagesFromUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val currentCount = _uiState.value.attachedImages.size
        val remainingSlots = (MAX_ATTACHED_IMAGES - currentCount).coerceAtLeast(0)
        if (remainingSlots <= 0) {
            _toastMessages.tryEmit("一次最多可附加 $MAX_ATTACHED_IMAGES 张图片")
            return
        }
        val toProcess = uris.take(remainingSlots)
        if (uris.size > remainingSlots) {
            _toastMessages.tryEmit("已添加前 $remainingSlots 张，共 ${uris.size} 张（上限 $MAX_ATTACHED_IMAGES 张）")
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isProcessingImages = true) }
            val added = mutableListOf<AttachedImageRef>()
            for (uri in toProcess) {
                runCatching {
                    imageAttachmentProcessor.processImageUri(uri)
                }.onSuccess { ref ->
                    added.add(ref)
                }.onFailure { err ->
                    if (err is CancellationException) throw err
                    _toastMessages.tryEmit(err.message ?: "无法处理图片")
                }
            }
            _uiState.update { state ->
                state.copy(
                    isProcessingImages = false,
                    attachedImages = (state.attachedImages + added).take(MAX_ATTACHED_IMAGES),
                )
            }
        }
    }

    fun removeAttachedImage(id: String) {
        _uiState.update { state ->
            state.copy(attachedImages = state.attachedImages.filterNot { it.id == id })
        }
    }

    fun buildActiveSessionMarkdownExport(): String? {
        val state = _uiState.value
        if (state.messages.isEmpty()) return null
        return ChatMarkdownExporter.exportSession(
            sessionTitle = state.activeSession?.title.orEmpty(),
            workspacePath = state.activeWorkspacePath,
            messages = state.messages,
        )
    }

    fun refreshGitBadge(directoryOverride: String? = null) {
        val dir = (directoryOverride ?: _uiState.value.activeWorkspacePath).trim()
        if (dir.isEmpty() || !_uiState.value.isConnected) {
            _uiState.update { it.copy(activeGitBranch = null, activeGitDirtyCount = 0) }
            return
        }
        gitBadgeJob?.cancel()
        gitBadgeJob = viewModelScope.launch {
            val summary = runCatching { gitRepository.loadSummary(dir) }.getOrNull()
            if (summary != null && summary.isGitRepo) {
                _uiState.update {
                    it.copy(
                        activeGitBranch = summary.branchName,
                        activeGitDirtyCount = summary.totalDirtyCount,
                    )
                }
            } else {
                _uiState.update { it.copy(activeGitBranch = null, activeGitDirtyCount = 0) }
            }
        }
    }

    fun selectModel(model: OpenCodeModel) {
        val shouldRetryWithNewModel = _uiState.value.pendingSwitchModelAndRetry
        _uiState.update {
            it.copy(
                selectedProviderId = model.providerId,
                selectedModelId = model.modelId,
                pendingSwitchModelAndRetry = false,
            )
        }
        viewModelScope.launch {
            preferencesRepository.setDefaultModel(model.providerId, model.modelId)
            if (shouldRetryWithNewModel) {
                retryLastPromptWithModel(model.providerId, model.modelId)
            }
        }
    }

    fun requestSwitchModelAndRetry() {
        _uiState.update { it.copy(pendingSwitchModelAndRetry = true) }
    }

    fun cancelPendingSwitchModelAndRetry() {
        _uiState.update { it.copy(pendingSwitchModelAndRetry = false) }
    }

    fun waitMoreForResponse() {
        _uiState.update { it.copy(isResponseStalled = false) }
        resetInactivityWatchdog()
    }

    fun buildDiagnosticsReport(): String {
        val state = _uiState.value
        val conn = connectionManager.connectionState.value as? SshConnectionState.Connected
        val now = System.currentTimeMillis()
        val timeStr = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))
        val sinceLastEventSec = if (lastSessionEventAtMs > 0L) (now - lastSessionEventAtMs) / 1000L else -1L
        val lastMsg = state.messages.lastOrNull()
        val report = buildString {
            appendLine("=== PocketPilot Chat Diagnostics ===")
            appendLine("时间：$timeStr")
            appendLine("服务器：${conn?.profile?.name ?: "无"}（${conn?.profile?.host ?: "无"}:${conn?.profile?.openCodePort ?: state.openCodePort}）")
            appendLine("SSH 隧道：${conn?.localTunnelPort?.let { "127.0.0.1:$it" } ?: "未启用"}")
            appendLine("工作区：${state.activeWorkspacePath.ifBlank { "默认" }}")
            appendLine("会话 ID：${state.activeSessionId ?: "无"}")
            appendLine("模型：${state.selectedProviderId}/${state.selectedModelId}（${state.selectedModelDisplay ?: "无"}）")
            appendLine("智能体：${state.selectedAgentName}")
            appendLine("SSE 状态：${state.sseStatus}")
            appendLine("会话忙碌：${state.isActiveSessionBusy}（正在发送=${state.isSending}）")
            appendLine("聊天消息数：${state.messages.size}")
            if (sinceLastEventSec >= 0L) {
                appendLine("距上次 SSE 事件：$sinceLastEventSec 秒")
            }
            if (!lastSessionError.isNullOrBlank()) {
                appendLine("最近错误：$lastSessionError")
            }
            if (lastMsg?.error?.isNotBlank() == true) {
                appendLine("最近一条消息的错误：${lastMsg.error}")
            }
        }
        return OpenCodeConfigHelper.sanitizeLogs(report)
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
        val images = state.attachedImages
        if (text.isEmpty() && attachments.isEmpty() && images.isEmpty()) return

        lastSentPromptText = text
        lastSentAttachments = attachments

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSending = true,
                    isResponseStalled = false,
                    composerText = "",
                    attachedFiles = emptyList(),
                    attachedImages = emptyList(),
                )
            }
            preferencesRepository.setLastChatDraft("")
            dispatchPromptToServer(
                text = text,
                attachments = attachments,
                imageAttachments = images,
                providerId = state.selectedProviderId.takeIf { it.isNotBlank() },
                modelId = state.selectedModelId.takeIf { it.isNotBlank() },
                agent = state.selectedAgentName.takeIf { it.isNotBlank() },
                restoreDraftOnFailure = true,
            )
        }
    }

    private suspend fun retryLastPromptWithModel(providerId: String, modelId: String) {
        val state = _uiState.value
        val activeId = state.activeSessionId
        if (activeId != null && state.isActiveSessionBusy) {
            runCatching { apiClient.abortSession(activeId) }
            markSessionBusy(activeId, busy = false)
        }
        val promptText = lastSentPromptText.ifBlank {
            state.messages.lastOrNull { it.isUser }?.let { ChatTimeFormatter.buildCopyableMessageText(it) }.orEmpty()
        }
        val attachments = if (lastSentPromptText.isNotBlank()) lastSentAttachments else emptyList()
        if (promptText.isBlank() && attachments.isEmpty()) {
            _toastMessages.tryEmit("模型已切换为 $modelId")
            return
        }
        _uiState.update { it.copy(isSending = true, isResponseStalled = false) }
        _toastMessages.tryEmit("正在使用模型 $modelId 重新发送…")
        dispatchPromptToServer(
            text = promptText,
            attachments = attachments,
            imageAttachments = emptyList(),
            providerId = providerId.takeIf { it.isNotBlank() },
            modelId = modelId.takeIf { it.isNotBlank() },
            agent = state.selectedAgentName.takeIf { it.isNotBlank() },
            restoreDraftOnFailure = false,
        )
    }

    private suspend fun dispatchPromptToServer(
        text: String,
        attachments: List<AttachedFileRef>,
        imageAttachments: List<AttachedImageRef>,
        providerId: String?,
        modelId: String?,
        agent: String?,
        restoreDraftOnFailure: Boolean,
    ) {
        try {
            val targetSessionId = _uiState.value.activeSessionId ?: run {
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

            lastSessionError = null
            markSessionBusy(targetSessionId, busy = true)
            resetInactivityWatchdog()
            apiClient.sendMessage(
                sessionId = targetSessionId,
                text = text,
                attachments = attachments,
                providerId = providerId,
                modelId = modelId,
                agent = agent,
                imageAttachments = imageAttachments,
            )
            _uiState.update { it.copy(isSending = false) }
            // Sync snapshot if SSE hasn't delivered the new user message yet
            if (_uiState.value.sseStatus != SseConnectionStatus.CONNECTED) {
                loadMessages(targetSessionId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            cancelInactivityWatchdog()
            lastSessionError = t.message
            _uiState.update {
                it.copy(
                    isSending = false,
                    isResponseStalled = false,
                    composerText = if (restoreDraftOnFailure && it.composerText.isEmpty()) text else it.composerText,
                    attachedFiles = if (restoreDraftOnFailure && it.attachedFiles.isEmpty()) attachments else it.attachedFiles,
                    attachedImages = if (restoreDraftOnFailure && it.attachedImages.isEmpty()) imageAttachments else it.attachedImages,
                )
            }
            _uiState.value.activeSessionId?.let { markSessionBusy(it, busy = false) }
            _toastMessages.tryEmit(t.message ?: "无法发送消息")
        }
    }

    fun abortActiveSession() {
        val sessionId = _uiState.value.activeSessionId ?: return
        cancelInactivityWatchdog()
        viewModelScope.launch {
            runCatching {
                apiClient.abortSession(sessionId)
            }.onSuccess {
                markSessionBusy(sessionId, busy = false)
                _uiState.update { it.copy(isSending = false, isResponseStalled = false) }
            }.onFailure { err ->
                _toastMessages.tryEmit(err.message ?: "无法停止生成")
            }
        }
    }

    fun revertToMessage(messageId: String) {
        val sessionId = _uiState.value.activeSessionId ?: return
        viewModelScope.launch {
            runCatching {
                apiClient.revertSession(sessionId, messageId)
            }.onSuccess {
                _toastMessages.tryEmit("更改已回退")
                loadMessages(sessionId)
                loadSessionDiffs(sessionId)
            }.onFailure { err ->
                _toastMessages.tryEmit(err.message ?: "无法回退更改")
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
                agentNotificationHelper.cancelPermissionNotification(request.id)
                _uiState.update { state ->
                    state.copy(
                        isSubmittingPermission = false,
                        pendingPermissions = state.pendingPermissions.filterNot { it.id == request.id },
                    )
                }
                val toast = when (option) {
                    PermissionReplyOption.ONCE -> "已允许一次"
                    PermissionReplyOption.ALWAYS -> "已对此会话允许"
                    PermissionReplyOption.REJECT -> "操作已拒绝"
                }
                _toastMessages.tryEmit(toast)
            }.onFailure { err ->
                _uiState.update { it.copy(isSubmittingPermission = false) }
                _toastMessages.tryEmit(err.message ?: "无法提交授权响应")
            }
        }
    }

    // Inline @ File Picker via SFTP (defaults to active workspace folder)
    fun openFilePicker(initialPath: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isPickerLoading = true) }
            val resolvedPath = initialPath?.takeIf { it.isNotBlank() }
                ?: _uiState.value.activeWorkspacePath.takeIf { it.isNotBlank() }
                ?: runCatching { sftpRepository.resolveInitialDirectory() }.getOrDefault("/")
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

    // Block C: Project Workspace Folder Picker (directories-only over SFTP)
    fun openWorkspacePicker() {
        val startPath = _uiState.value.activeWorkspacePath.ifBlank {
            _uiState.value.homeDirectoryPath.ifBlank { "/" }
        }
        loadWorkspacePickerDirectory(startPath)
    }

    fun loadWorkspacePickerDirectory(path: String) {
        val normalized = SftpRepository.normalizeRemotePath(path)
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    workspacePickerPath = normalized,
                    isWorkspacePickerLoading = true,
                    workspacePickerError = null,
                )
            }
            runCatching {
                sftpRepository.listDirectory(normalized, includeHidden = false)
                    .filter { it.isDirectory }
                    .sortedBy { it.name.lowercase() }
            }.onSuccess { folders ->
                _uiState.update {
                    it.copy(
                        workspacePickerPath = normalized,
                        workspacePickerFolders = folders,
                        isWorkspacePickerLoading = false,
                        workspacePickerError = null,
                    )
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                _uiState.update {
                    it.copy(
                        isWorkspacePickerLoading = false,
                        workspacePickerError = err.message ?: "无法通过 SFTP 打开文件夹",
                    )
                }
            }
        }
    }

    fun createWorkspaceFolder(folderName: String) {
        val cleanName = folderName.trim().trim('/')
        if (cleanName.isEmpty() || cleanName.contains('/')) return
        val parentPath = _uiState.value.workspacePickerPath
        val childPath = SftpRepository.joinRemotePath(parentPath, cleanName)
        viewModelScope.launch {
            _uiState.update { it.copy(isCreatingWorkspaceFolder = true, workspacePickerError = null) }
            runCatching {
                sftpRepository.createDirectory(parentPath = parentPath, name = cleanName)
            }.onSuccess { createdPath ->
                _uiState.update { it.copy(isCreatingWorkspaceFolder = false) }
                _toastMessages.tryEmit("文件夹“$cleanName”已创建")
                loadWorkspacePickerDirectory(createdPath)
            }.onFailure { err ->
                if (err is CancellationException) throw err
                _uiState.update {
                    it.copy(
                        isCreatingWorkspaceFolder = false,
                        workspacePickerError = err.message ?: "无法创建文件夹",
                    )
                }
            }
        }
    }

    fun selectWorkspace(path: String) {
        val normalized = SftpRepository.normalizeRemotePath(path)
        val connected = connectionManager.connectionState.value as? SshConnectionState.Connected
        val profileId = connected?.profile?.id ?: lastProfileId

        apiClient.setActiveDirectory(normalized)
        cancelInactivityWatchdog()

        _uiState.update { state ->
            val updatedRecent = (listOf(normalized) + state.recentWorkspaces.filterNot { it == normalized }).take(8)
            state.copy(
                activeWorkspacePath = normalized,
                recentWorkspaces = updatedRecent,
                activeSessionId = null,
                messages = emptyList(),
                sessionDiffs = emptyList(),
                isResponseStalled = false,
                errorMessage = null,
            )
        }

        viewModelScope.launch {
            if (profileId != null) {
                preferencesRepository.setActiveWorkspace(profileId, normalized)
            }
            preferencesRepository.setLastChatSessionId(null)
        }

        if (connected != null && _uiState.value.setup.serverStatus == OpenCodeServerStatus.RUNNING) {
            startSseStream(connected.localTunnelPort)
            refreshAll()
        }
        refreshGitBadge(normalized)
        _toastMessages.tryEmit("工作区：$normalized")
    }

    private fun ensureWorkspaceInitialized(profileId: Long, detectedHomeDir: String?) {
        val current = _uiState.value.activeWorkspacePath
        val home = detectedHomeDir?.trimEnd('/')?.takeIf { it.isNotBlank() }
        if (home != null && _uiState.value.homeDirectoryPath != home) {
            _uiState.update { it.copy(homeDirectoryPath = home) }
        }
        val saved = activeWorkspaceByProfile[profileId]
        val resolved = current.ifBlank { saved.orEmpty() }.ifBlank { home.orEmpty() }
        if (resolved.isNotBlank()) {
            val normalized = SftpRepository.normalizeRemotePath(resolved)
            apiClient.setActiveDirectory(normalized)
            if (_uiState.value.activeWorkspacePath != normalized) {
                _uiState.update { state ->
                    val recent = recentWorkspacesByProfile[profileId].orEmpty().ifEmpty { listOf(normalized) }
                    state.copy(activeWorkspacePath = normalized, recentWorkspaces = recent)
                }
            }
            if (saved.isNullOrBlank()) {
                viewModelScope.launch { preferencesRepository.setActiveWorkspace(profileId, normalized) }
            }
            refreshGitBadge(normalized)
        }
    }

    private fun resetInactivityWatchdog() {
        lastSessionEventAtMs = System.currentTimeMillis()
        inactivityWatchdogJob?.cancel()
        if (!_uiState.value.isActiveSessionBusy) return
        inactivityWatchdogJob = viewModelScope.launch {
            delay(INACTIVITY_WATCHDOG_MS)
            if (_uiState.value.isActiveSessionBusy && _uiState.value.activeSessionId != null) {
                _uiState.update { it.copy(isResponseStalled = true) }
            }
        }
    }

    private fun cancelInactivityWatchdog() {
        inactivityWatchdogJob?.cancel()
        inactivityWatchdogJob = null
    }

    private fun startSseStream(tunnelPort: Int) {
        stopSseStream()
        val workspaceDir = _uiState.value.activeWorkspacePath.takeIf { it.isNotBlank() }
        sseJob = viewModelScope.launch {
            apiClient.observeEvents(tunnelPort, workspaceDir).collect { (status, event) ->
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
                if (event.sessionId == _uiState.value.activeSessionId) {
                    cancelInactivityWatchdog()
                }
                _uiState.update { state ->
                    state.copy(
                        sessions = state.sessions.filterNot { it.id == event.sessionId },
                        activeSessionId = if (state.activeSessionId == event.sessionId) null else state.activeSessionId,
                        isResponseStalled = if (state.activeSessionId == event.sessionId) false else state.isResponseStalled,
                    )
                }
            }

            is OpenCodeEvent.SessionStatusChanged -> {
                val previousSession = _uiState.value.sessions.firstOrNull { it.id == event.sessionId }
                val wasBusy = previousSession?.isBusy == true ||
                    (event.sessionId == _uiState.value.activeSessionId && _uiState.value.isSending)
                markSessionBusy(event.sessionId, event.isBusy)
                if (wasBusy && !event.isBusy) {
                    agentNotificationHelper.notifyAgentFinished(
                        sessionId = event.sessionId,
                        sessionTitle = previousSession?.title,
                    )
                    refreshGitBadge()
                }
                if (event.sessionId == _uiState.value.activeSessionId) {
                    lastSessionEventAtMs = System.currentTimeMillis()
                    if (event.isBusy) {
                        _uiState.update { it.copy(isResponseStalled = false) }
                        resetInactivityWatchdog()
                    } else {
                        cancelInactivityWatchdog()
                        val completedNow = System.currentTimeMillis()
                        _uiState.update { state ->
                            val lastAssistantIdx = state.messages.indexOfLast { !it.isUser }
                            val updatedMessages = if (lastAssistantIdx >= 0 && state.messages[lastAssistantIdx].completedAtMillis == null) {
                                state.messages.mapIndexed { idx, msg ->
                                    if (idx == lastAssistantIdx) msg.copy(completedAtMillis = completedNow) else msg
                                }
                            } else {
                                state.messages
                            }
                            state.copy(
                                isSending = false,
                                isResponseStalled = false,
                                messages = updatedMessages,
                            )
                        }
                    }
                }
            }

            is OpenCodeEvent.SessionError -> {
                val activeId = _uiState.value.activeSessionId
                val matchesActive = activeId != null && (event.sessionId == null || event.sessionId == activeId)
                event.sessionId?.let { markSessionBusy(it, busy = false) }
                if (matchesActive && activeId != null) {
                    cancelInactivityWatchdog()
                    lastSessionError = event.message
                    lastSessionEventAtMs = System.currentTimeMillis()
                    markSessionBusy(activeId, busy = false)
                    val now = System.currentTimeMillis()
                    _uiState.update { state ->
                        val lastIdx = state.messages.lastIndex
                        val updatedMessages = if (lastIdx >= 0 && !state.messages[lastIdx].isUser) {
                            state.messages.mapIndexed { idx, msg ->
                                if (idx == lastIdx) {
                                    msg.copy(
                                        error = event.message,
                                        completedAtMillis = msg.completedAtMillis ?: now,
                                    )
                                } else {
                                    msg
                                }
                            }
                        } else {
                            state.messages + OpenCodeMessage(
                                id = "session_err_$now",
                                sessionId = activeId,
                                role = "assistant",
                                createdAtMillis = now,
                                completedAtMillis = now,
                                error = event.message,
                            )
                        }
                        state.copy(
                            isSending = false,
                            isResponseStalled = false,
                            messages = updatedMessages,
                        )
                    }
                }
            }

            is OpenCodeEvent.MessageUpdated -> {
                if (!event.message.isUser && event.message.tokens?.hasAny == true) {
                    val profileId = currentActiveProfileId()
                    val workspace = _uiState.value.activeWorkspacePath
                    if (profileId != null) {
                        viewModelScope.launch {
                            runCatching { usageRepository.recordMessage(profileId, workspace, event.message) }
                        }
                    }
                }
                if (event.message.sessionId != _uiState.value.activeSessionId) return
                if (!event.message.error.isNullOrBlank()) {
                    lastSessionError = event.message.error
                }
                _uiState.update { state ->
                    val existing = state.messages.firstOrNull { it.id == event.message.id }
                    val merged = if (existing != null) {
                        event.message.copy(
                            parts = existing.parts,
                            completedAtMillis = event.message.completedAtMillis ?: existing.completedAtMillis,
                            error = event.message.error ?: existing.error,
                            tokens = event.message.tokens ?: existing.tokens,
                            costUsd = event.message.costUsd ?: existing.costUsd,
                        )
                    } else {
                        event.message
                    }
                    val updatedList = (state.messages.filterNot { it.id == merged.id } + merged)
                        .sortedBy { it.createdAtMillis }
                    state.copy(messages = updatedList, isResponseStalled = false)
                }
                if (_uiState.value.isActiveSessionBusy) {
                    resetInactivityWatchdog()
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
                    state.copy(messages = updatedMsgs, isResponseStalled = false)
                }
                if (_uiState.value.isActiveSessionBusy) {
                    resetInactivityWatchdog()
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
                    state.copy(messages = updatedMsgs, isResponseStalled = false)
                }
                if (_uiState.value.isActiveSessionBusy) {
                    resetInactivityWatchdog()
                }
            }

            is OpenCodeEvent.PermissionAsked -> {
                agentNotificationHelper.notifyPermissionNeeded(event.request)
                _uiState.update { state ->
                    val withoutDup = state.pendingPermissions.filterNot { it.id == event.request.id }
                    state.copy(pendingPermissions = withoutDup + event.request, isResponseStalled = false)
                }
                if (event.request.sessionId == _uiState.value.activeSessionId && _uiState.value.isActiveSessionBusy) {
                    resetInactivityWatchdog()
                }
            }

            is OpenCodeEvent.PermissionReplied -> {
                agentNotificationHelper.cancelPermissionNotification(event.requestId)
                _uiState.update { state ->
                    state.copy(
                        pendingPermissions = state.pendingPermissions.filterNot { it.id == event.requestId },
                    )
                }
            }

            is OpenCodeEvent.SessionDiffUpdated -> {
                if (event.sessionId == _uiState.value.activeSessionId) {
                    _uiState.update { it.copy(sessionDiffs = event.diffs, isResponseStalled = false) }
                    if (_uiState.value.isActiveSessionBusy) {
                        resetInactivityWatchdog()
                    }
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

    // -------------------------------------------------------------------------
    // Block B: Turnkey Persistent Systemd OpenCode Installer & Model Setup
    // -------------------------------------------------------------------------

    fun onChatTabVisible() {
        val state = _uiState.value
        if (!state.isConnected) return
        if (state.setup.wizardStage != SetupWizardStage.IDLE || state.setup.isRunningAction) return
        // Silently re-check if server is running so stopping/removing service in Settings is reflected immediately
        checkOpenCodeServer(silentIfRunning = state.setup.serverStatus == OpenCodeServerStatus.RUNNING)
    }

    fun checkOpenCodeServer(silentIfRunning: Boolean = false) {
        val connected = connectionManager.connectionState.value as? SshConnectionState.Connected ?: return
        val profileId = connected.profile.id
        val port = connected.profile.openCodePort
        val tunnelPort = connected.localTunnelPort

        setupJob?.cancel()
        setupJob = viewModelScope.launch {
            if (!silentIfRunning) {
                _uiState.update { state ->
                    state.copy(
                        openCodePort = port,
                        setup = state.setup.copy(
                            serverStatus = OpenCodeServerStatus.CHECKING,
                            wizardStage = SetupWizardStage.IDLE,
                            steps = OpenCodeConfigHelper.buildDefaultSteps(
                                port = port,
                                alreadyInstalled = !state.setup.envInfo.opencodePath.isNullOrBlank(),
                                forceUpdateBinary = state.setup.forceUpdateBinary,
                                enablePasswordAuth = state.setup.enablePasswordAuth,
                                verifyPersistence = state.setup.verifyPersistenceOnRestart,
                            ),
                            errorMessage = null,
                            isRunningAction = false,
                        ),
                    )
                }
            }

            runCatching {
                serverInstaller.detectServerState(
                    profileId = profileId,
                    openCodePort = port,
                    localTunnelPort = tunnelPort,
                )
            }.onSuccess { (status, env) ->
                val wasRunning = _uiState.value.setup.serverStatus == OpenCodeServerStatus.RUNNING
                ensureWorkspaceInitialized(profileId = profileId, detectedHomeDir = env.homeDir)
                _uiState.update { state ->
                    state.copy(
                        openCodePortReachable = status == OpenCodeServerStatus.RUNNING,
                        setup = state.setup.copy(
                            serverStatus = status,
                            envInfo = env,
                            errorMessage = null,
                        ),
                    )
                }
                if (status == OpenCodeServerStatus.RUNNING) {
                    if (!wasRunning || _uiState.value.sessions.isEmpty()) {
                        refreshAll()
                        startSseStream(tunnelPort)
                    }
                } else {
                    stopSseStream()
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                if (!silentIfRunning) {
                    _uiState.update { state ->
                        state.copy(
                            setup = state.setup.copy(
                                serverStatus = OpenCodeServerStatus.NOT_INSTALLED,
                                errorMessage = OpenCodeConfigHelper.sanitizeLogs(
                                    err.message ?: "无法检查服务器上的 OpenCode 状态",
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun openInstallPreview(forceUpdateBinary: Boolean = false) {
        val state = _uiState.value
        val port = state.openCodePort
        val alreadyInstalled = !state.setup.envInfo.opencodePath.isNullOrBlank()
        _uiState.update { cur ->
            cur.copy(
                setup = cur.setup.copy(
                    wizardStage = SetupWizardStage.PREVIEW_COMMANDS,
                    forceUpdateBinary = forceUpdateBinary,
                    steps = OpenCodeConfigHelper.buildDefaultSteps(
                        port = port,
                        alreadyInstalled = alreadyInstalled,
                        forceUpdateBinary = forceUpdateBinary,
                        enablePasswordAuth = cur.setup.enablePasswordAuth,
                        verifyPersistence = cur.setup.verifyPersistenceOnRestart,
                    ),
                    failedStepIndex = null,
                    verificationReport = null,
                    errorMessage = null,
                ),
            )
        }
    }

    fun openRepairInstallPreview() {
        openInstallPreview(forceUpdateBinary = false)
    }

    fun setEnablePasswordAuth(enabled: Boolean) {
        _uiState.update { state ->
            val alreadyInstalled = !state.setup.envInfo.opencodePath.isNullOrBlank()
            state.copy(
                setup = state.setup.copy(
                    enablePasswordAuth = enabled,
                    steps = OpenCodeConfigHelper.buildDefaultSteps(
                        port = state.openCodePort,
                        alreadyInstalled = alreadyInstalled,
                        forceUpdateBinary = state.setup.forceUpdateBinary,
                        enablePasswordAuth = enabled,
                        verifyPersistence = state.setup.verifyPersistenceOnRestart,
                    ),
                ),
            )
        }
    }

    fun setVerifyPersistenceOnRestart(enabled: Boolean) {
        _uiState.update { state ->
            val alreadyInstalled = !state.setup.envInfo.opencodePath.isNullOrBlank()
            state.copy(
                setup = state.setup.copy(
                    verifyPersistenceOnRestart = enabled,
                    steps = OpenCodeConfigHelper.buildDefaultSteps(
                        port = state.openCodePort,
                        alreadyInstalled = alreadyInstalled,
                        forceUpdateBinary = state.setup.forceUpdateBinary,
                        enablePasswordAuth = state.setup.enablePasswordAuth,
                        verifyPersistence = enabled,
                    ),
                ),
            )
        }
    }

    fun cancelSetupWizard() {
        setupJob?.cancel()
        _uiState.update { state ->
            state.copy(
                setup = state.setup.copy(
                    wizardStage = SetupWizardStage.IDLE,
                    isRunningAction = false,
                    errorMessage = null,
                    apiKeyInput = "",
                    providerSaveSteps = emptyList(),
                    providerSaveWarning = null,
                    isRetryingProviderCheck = false,
                ),
            )
        }
    }

    fun confirmAndRunInstall(startFromIndex: Int = 0) {
        val connected = connectionManager.connectionState.value as? SshConnectionState.Connected ?: return
        val profileId = connected.profile.id
        val port = connected.profile.openCodePort
        val tunnelPort = connected.localTunnelPort

        setupJob?.cancel()
        setupJob = viewModelScope.launch {
            val snapshot = _uiState.value.setup
            val alreadyInstalled = !snapshot.envInfo.opencodePath.isNullOrBlank()
            val baseSteps = if (snapshot.steps.size == 5 && startFromIndex > 0) {
                snapshot.steps.mapIndexed { idx, step ->
                    if (idx >= startFromIndex) step.copy(status = InstallStepStatus.PENDING, detail = null)
                    else step
                }
            } else {
                OpenCodeConfigHelper.buildDefaultSteps(
                    port = port,
                    alreadyInstalled = alreadyInstalled,
                    forceUpdateBinary = snapshot.forceUpdateBinary,
                    enablePasswordAuth = snapshot.enablePasswordAuth,
                    verifyPersistence = snapshot.verifyPersistenceOnRestart,
                )
            }

            _uiState.update { state ->
                state.copy(
                    setup = state.setup.copy(
                        wizardStage = SetupWizardStage.INSTALLING,
                        steps = baseSteps,
                        failedStepIndex = null,
                        errorMessage = null,
                        verificationReport = null,
                        isRunningAction = true,
                        liveLog = if (startFromIndex == 0) "" else state.setup.liveLog + "\n--- 重试第 ${startFromIndex + 1} 步 ---\n",
                    ),
                )
            }

            var envInfo = _uiState.value.setup.envInfo
            var binaryPath = envInfo.opencodePath
            var installedAsUserService = envInfo.isUserService
            val forceUpdate = _uiState.value.setup.forceUpdateBinary
            val enablePasswordAuth = _uiState.value.setup.enablePasswordAuth
            val verifyPersistence = _uiState.value.setup.verifyPersistenceOnRestart

            val appendLog: (String) -> Unit = { line ->
                val safeLine = OpenCodeConfigHelper.sanitizeLogs(line)
                _uiState.update { state ->
                    val nextLog = (state.setup.liveLog + safeLine + "\n").takeLast(28_000)
                    state.copy(setup = state.setup.copy(liveLog = nextLog))
                }
            }

            for (stepIndex in startFromIndex until 5) {
                updateStepStatus(stepIndex, InstallStepStatus.RUNNING, null)
                val stepResult = runCatching {
                    when (stepIndex) {
                        0 -> {
                            envInfo = serverInstaller.inspectEnvironment(
                                openCodePort = port,
                                requireSystemd = true,
                                onLog = appendLog,
                            )
                            binaryPath = envInfo.opencodePath
                            _uiState.update { it.copy(setup = it.setup.copy(envInfo = envInfo)) }
                            val freeLabel = if (envInfo.freeSpaceMb >= 0) "${envInfo.freeSpaceMb} MB" else "无"
                            "${envInfo.osName} ${envInfo.arch}，剩余空间 $freeLabel，systemd=可用"
                        }

                        1 -> {
                            if (envInfo.osName.isBlank()) {
                                envInfo = serverInstaller.inspectEnvironment(port, requireSystemd = true, onLog = appendLog)
                            }
                            val (resolved, version) = serverInstaller.ensureOrUpdateOpenCodeBinary(
                                env = envInfo,
                                forceUpdate = forceUpdate,
                                onLog = appendLog,
                            )
                            binaryPath = resolved
                            envInfo = envInfo.copy(opencodePath = resolved, opencodeVersion = version)
                            _uiState.update { it.copy(setup = it.setup.copy(envInfo = envInfo)) }
                            if (version != null) "$resolved ($version)" else resolved
                        }

                        2 -> {
                            if (envInfo.osName.isBlank()) {
                                envInfo = serverInstaller.inspectEnvironment(port, requireSystemd = true, onLog = appendLog)
                            }
                            serverInstaller.migrateLegacyRuns(envInfo, appendLog)
                            "端口 $port 已释放，临时服务已停止"
                        }

                        3 -> {
                            if (envInfo.osName.isBlank()) {
                                envInfo = serverInstaller.inspectEnvironment(port, requireSystemd = true, onLog = appendLog)
                            }
                            val resolvedBin = binaryPath ?: envInfo.opencodePath
                                ?: serverInstaller.ensureOrUpdateOpenCodeBinary(envInfo, forceUpdate = false, onLog = appendLog).first
                            installedAsUserService = serverInstaller.installPersistentSystemdService(
                                profileId = profileId,
                                env = envInfo,
                                binaryPath = resolvedBin,
                                port = port,
                                enablePasswordAuth = enablePasswordAuth,
                                onLog = appendLog,
                            )
                            apiClient.invalidateDiscoveryCache()
                            val scopeLabel = if (installedAsUserService) "user-service + linger" else "systemd"
                            val authLabel = if (enablePasswordAuth) ", Basic Auth" else ""
                            "$scopeLabel opencode-serve (127.0.0.1:$port$authLabel)"
                        }

                        4 -> {
                            if (envInfo.osName.isBlank()) {
                                envInfo = serverInstaller.inspectEnvironment(port, requireSystemd = true, onLog = appendLog)
                                installedAsUserService = envInfo.isUserService
                            }
                            val report = serverInstaller.verifyInstallation(
                                profileId = profileId,
                                env = envInfo,
                                isUserService = installedAsUserService,
                                port = port,
                                localTunnelPort = tunnelPort,
                                verifyPersistence = verifyPersistence,
                                onLog = appendLog,
                            )
                            _uiState.update {
                                it.copy(setup = it.setup.copy(verificationReport = report))
                            }
                            if (report.restartSurvived == true) {
                                "active，127.0.0.1:$port，/session JSON 正常，重启检查通过"
                            } else {
                                "active, 127.0.0.1:$port, /session JSON OK"
                            }
                        }

                        else -> ""
                    }
                }

                if (stepResult.isSuccess) {
                    updateStepStatus(stepIndex, InstallStepStatus.SUCCESS, stepResult.getOrNull())
                } else {
                    val err = stepResult.exceptionOrNull()
                    if (err is CancellationException) throw err
                    val msg = OpenCodeConfigHelper.sanitizeLogs(err?.message ?: "步骤执行失败")
                    updateStepStatus(stepIndex, InstallStepStatus.ERROR, msg)
                    appendLog("✗ 错误：$msg")
                    _uiState.update { state ->
                        state.copy(
                            setup = state.setup.copy(
                                isRunningAction = false,
                                failedStepIndex = stepIndex,
                                errorMessage = msg,
                            ),
                        )
                    }
                    return@launch
                }
            }

            _uiState.update { state ->
                state.copy(
                    openCodePortReachable = true,
                    setup = state.setup.copy(
                        serverStatus = OpenCodeServerStatus.RUNNING,
                        wizardStage = SetupWizardStage.CONNECT_MODEL,
                        isRunningAction = false,
                        failedStepIndex = null,
                        errorMessage = null,
                    ),
                )
            }
            loadConfiguredProviders()
        }
    }

    fun retryFailedStep() {
        val idx = _uiState.value.setup.failedStepIndex ?: 0
        confirmAndRunInstall(startFromIndex = idx)
    }

    fun startStoppedServer() {
        val connected = connectionManager.connectionState.value as? SshConnectionState.Connected ?: return
        val profileId = connected.profile.id
        val port = connected.profile.openCodePort
        val tunnelPort = connected.localTunnelPort

        setupJob?.cancel()
        setupJob = viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    setup = state.setup.copy(
                        isRunningAction = true,
                        errorMessage = null,
                        liveLog = "",
                    ),
                )
            }
            val appendLog: (String) -> Unit = { line ->
                val safeLine = OpenCodeConfigHelper.sanitizeLogs(line)
                _uiState.update { state ->
                    val nextLog = (state.setup.liveLog + safeLine + "\n").takeLast(16_000)
                    state.copy(setup = state.setup.copy(liveLog = nextLog))
                }
            }

            runCatching {
                val env = serverInstaller.inspectEnvironment(openCodePort = port, requireSystemd = true, onLog = appendLog)
                if (!env.hasPersistentUnit) {
                    // Open preview to install persistent systemd unit cleanly
                    _uiState.update { state ->
                        state.copy(
                            setup = state.setup.copy(
                                envInfo = env,
                                isRunningAction = false,
                            ),
                        )
                    }
                    openInstallPreview(forceUpdateBinary = false)
                    return@launch
                }
                serverInstaller.startExistingServer(
                    profileId = profileId,
                    env = env,
                    port = port,
                    localTunnelPort = tunnelPort,
                    onLog = appendLog,
                )
            }.onSuccess {
                apiClient.invalidateDiscoveryCache()
                _uiState.update { state ->
                    state.copy(
                        openCodePortReachable = true,
                        setup = state.setup.copy(
                            serverStatus = OpenCodeServerStatus.RUNNING,
                            wizardStage = SetupWizardStage.IDLE,
                            isRunningAction = false,
                            errorMessage = null,
                        ),
                    )
                }
                refreshAll()
                startSseStream(tunnelPort)
                _toastMessages.tryEmit("OpenCode 服务已启动")
            }.onFailure { err ->
                if (err is CancellationException) throw err
                val msg = OpenCodeConfigHelper.sanitizeLogs(err.message ?: "无法启动 OpenCode 服务器")
                _uiState.update { state ->
                    state.copy(
                        setup = state.setup.copy(
                            isRunningAction = false,
                            errorMessage = msg,
                        ),
                    )
                }
            }
        }
    }

    fun openConnectModelStage() {
        _uiState.update { state ->
            state.copy(
                setup = state.setup.copy(
                    wizardStage = SetupWizardStage.CONNECT_MODEL,
                    useCustomProvider = true,
                    apiKeyInput = "",
                    errorMessage = null,
                    fetchModelsError = null,
                    modelTestResult = null,
                    configuredProvidersError = null,
                    providerSaveSteps = emptyList(),
                    providerSaveWarning = null,
                    isRetryingProviderCheck = false,
                ),
            )
        }
        loadConfiguredProviders()
    }

    fun loadConfiguredProviders() {
        val connected = connectionManager.connectionState.value as? SshConnectionState.Connected ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    setup = it.setup.copy(
                        isLoadingConfiguredProviders = true,
                        configuredProvidersError = null,
                    ),
                )
            }
            runCatching {
                serverInstaller.providerManager.loadConfiguredProviders(connected.profile.openCodePort)
            }.onSuccess { list ->
                _uiState.update {
                    it.copy(
                        setup = it.setup.copy(
                            configuredProviders = list,
                            isLoadingConfiguredProviders = false,
                            configuredProvidersError = null,
                        ),
                    )
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                _uiState.update {
                    it.copy(
                        setup = it.setup.copy(
                            isLoadingConfiguredProviders = false,
                            configuredProvidersError = OpenCodeConfigHelper.sanitizeLogs(
                                err.message ?: "无法读取供应商列表",
                            ),
                        ),
                    )
                }
            }
        }
    }

    fun startNewProviderEditor() {
        _uiState.update { state ->
            state.copy(
                setup = state.setup.copy(
                    useCustomProvider = true,
                    editingProviderId = null,
                    editingMaskedApiKey = null,
                    providerName = "Custom OpenAI",
                    baseUrl = "https://api.openai.com/v1",
                    apiKeyInput = "",
                    availableModels = emptyList(),
                    modelSearchQuery = "",
                    manualModelIdInput = "",
                    defaultSelectedModelId = "",
                    fetchModelsError = null,
                    fetchModelsCount = null,
                    modelTestResult = null,
                    errorMessage = null,
                ),
            )
        }
    }

    fun startEditProvider(provider: ConfiguredProviderInfo) {
        viewModelScope.launch {
            val savedPricing = runCatching {
                usageRepository.getPricingForProvider(provider.providerId)
            }.getOrDefault(emptyMap())

            val existingEntries = provider.models.map { m ->
                val p = savedPricing[m.id]
                CustomModelEntry(
                    id = m.id,
                    displayName = m.name,
                    selected = true,
                    multiplierText = formatDecimalInput(p?.multiplier ?: 1.0),
                    cacheMultiplierText = formatDecimalInput(p?.cacheMultiplier ?: 1.0),
                    inputPricePer1MText = (p?.inputPricePer1M ?: m.inputPricePer1M)?.let(::formatDecimalInput).orEmpty(),
                    outputPricePer1MText = (p?.outputPricePer1M ?: m.outputPricePer1M)?.let(::formatDecimalInput).orEmpty(),
                    contextLimit = m.contextLimit,
                )
            }
            val initialDefault = provider.defaultModelId
                ?.takeIf { defId -> existingEntries.any { it.id == defId } }
                ?: existingEntries.firstOrNull()?.id.orEmpty()

            _uiState.update { state ->
                state.copy(
                    setup = state.setup.copy(
                        useCustomProvider = true,
                        editingProviderId = provider.providerId,
                        editingMaskedApiKey = provider.maskedApiKey,
                        providerName = provider.name,
                        baseUrl = provider.baseUrl.ifBlank { "https://api.openai.com/v1" },
                        // Never store or prefill raw API keys in the app
                        apiKeyInput = "",
                        availableModels = existingEntries,
                        modelSearchQuery = "",
                        manualModelIdInput = "",
                        defaultSelectedModelId = initialDefault,
                        fetchModelsError = null,
                        fetchModelsCount = null,
                        modelTestResult = null,
                        errorMessage = null,
                    ),
                )
            }
        }
    }

    fun onSelectCustomProvider(useCustom: Boolean) {
        _uiState.update { state ->
            state.copy(
                setup = state.setup.copy(
                    useCustomProvider = useCustom,
                    errorMessage = null,
                ),
            )
        }
    }

    fun onProviderNameChange(value: String) {
        _uiState.update { it.copy(setup = it.setup.copy(providerName = value, errorMessage = null)) }
    }

    fun onBaseUrlChange(value: String) {
        _uiState.update {
            it.copy(
                setup = it.setup.copy(
                    baseUrl = value,
                    fetchModelsError = null,
                    modelTestResult = null,
                    errorMessage = null,
                ),
            )
        }
    }

    fun onApiKeyChange(value: String) {
        _uiState.update {
            it.copy(
                setup = it.setup.copy(
                    apiKeyInput = value,
                    fetchModelsError = null,
                    modelTestResult = null,
                    errorMessage = null,
                ),
            )
        }
    }

    fun onModelSearchQueryChange(value: String) {
        _uiState.update { it.copy(setup = it.setup.copy(modelSearchQuery = value)) }
    }

    fun onManualModelIdInputChange(value: String) {
        _uiState.update { it.copy(setup = it.setup.copy(manualModelIdInput = value, errorMessage = null)) }
    }

    fun addManualModel() {
        _uiState.update { state ->
            val rawId = state.setup.manualModelIdInput.trim()
            if (rawId.isEmpty()) return@update state
            val existing = state.setup.availableModels
            val updated = if (existing.any { it.id == rawId }) {
                existing.map { if (it.id == rawId) it.copy(selected = true) else it }
            } else {
                listOf(CustomModelEntry(id = rawId, displayName = rawId, selected = true)) + existing
            }
            val nextDefault = state.setup.defaultSelectedModelId.ifBlank { rawId }
            state.copy(
                setup = state.setup.copy(
                    availableModels = updated,
                    manualModelIdInput = "",
                    defaultSelectedModelId = nextDefault,
                    errorMessage = null,
                ),
            )
        }
    }

    fun toggleModelSelection(modelId: String) {
        _uiState.update { state ->
            val updated = state.setup.availableModels.map { entry ->
                if (entry.id == modelId) entry.copy(selected = !entry.selected) else entry
            }
            val selectedIds = updated.filter { it.selected }.map { it.id }
            val nextDefault = when {
                state.setup.defaultSelectedModelId in selectedIds -> state.setup.defaultSelectedModelId
                else -> selectedIds.firstOrNull().orEmpty()
            }
            state.copy(
                setup = state.setup.copy(
                    availableModels = updated,
                    defaultSelectedModelId = nextDefault,
                    errorMessage = null,
                ),
            )
        }
    }

    fun updateModelDisplayName(modelId: String, newName: String) {
        _uiState.update { state ->
            val updated = state.setup.availableModels.map { entry ->
                if (entry.id == modelId) entry.copy(displayName = newName) else entry
            }
            state.copy(setup = state.setup.copy(availableModels = updated))
        }
    }

    fun updateModelMultiplier(modelId: String, multiplierText: String) {
        val filtered = filterDecimalInput(multiplierText)
        _uiState.update { state ->
            val updated = state.setup.availableModels.map { entry ->
                if (entry.id == modelId) entry.copy(multiplierText = filtered) else entry
            }
            state.copy(setup = state.setup.copy(availableModels = updated))
        }
    }

    fun updateModelCacheMultiplier(modelId: String, cacheMultiplierText: String) {
        val filtered = filterDecimalInput(cacheMultiplierText)
        _uiState.update { state ->
            val updated = state.setup.availableModels.map { entry ->
                if (entry.id == modelId) entry.copy(cacheMultiplierText = filtered) else entry
            }
            state.copy(setup = state.setup.copy(availableModels = updated))
        }
    }

    fun updateModelInputPrice(modelId: String, priceText: String) {
        val filtered = filterDecimalInput(priceText)
        _uiState.update { state ->
            val updated = state.setup.availableModels.map { entry ->
                if (entry.id == modelId) entry.copy(inputPricePer1MText = filtered) else entry
            }
            state.copy(setup = state.setup.copy(availableModels = updated))
        }
    }

    fun updateModelOutputPrice(modelId: String, priceText: String) {
        val filtered = filterDecimalInput(priceText)
        _uiState.update { state ->
            val updated = state.setup.availableModels.map { entry ->
                if (entry.id == modelId) entry.copy(outputPricePer1MText = filtered) else entry
            }
            state.copy(setup = state.setup.copy(availableModels = updated))
        }
    }

    fun selectDefaultModelInEditor(modelId: String) {
        _uiState.update { state ->
            val updated = state.setup.availableModels.map { entry ->
                if (entry.id == modelId) entry.copy(selected = true) else entry
            }
            state.copy(
                setup = state.setup.copy(
                    availableModels = updated,
                    defaultSelectedModelId = modelId,
                    modelTestResult = state.setup.modelTestResult?.takeIf { it.modelId == modelId },
                ),
            )
        }
    }

    fun fetchModelsFromProvider() {
        val connected = connectionManager.connectionState.value as? SshConnectionState.Connected ?: return
        val s = _uiState.value.setup
        if (s.baseUrl.isBlank()) return
        val secretKey = s.apiKeyInput

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    setup = it.setup.copy(
                        isFetchingModels = true,
                        fetchModelsError = null,
                        errorMessage = null,
                    ),
                )
            }
            runCatching {
                serverInstaller.providerManager.fetchRemoteModels(
                    baseUrl = s.baseUrl,
                    apiKeyInput = secretKey,
                    existingProviderId = s.editingProviderId,
                    openCodePort = connected.profile.openCodePort,
                )
            }.onSuccess { fetched ->
                val targetProviderId = s.editingProviderId ?: OpenCodeConfigHelper.sanitizeProviderId(s.providerName)
                val savedPricing = runCatching {
                    usageRepository.getPricingForProvider(targetProviderId)
                }.getOrDefault(emptyMap())

                _uiState.update { state ->
                    val previouslyEditedById = state.setup.availableModels
                        .associateBy { it.id }

                    val mergedMap = LinkedHashMap<String, CustomModelEntry>()
                    // Preserve already-selected models (with customized display names & pricing/multipliers)
                    previouslyEditedById.values.filter { it.selected }.forEach { entry ->
                        mergedMap[entry.id] = entry
                    }
                    // Add fetched models (name initialized from ID; apply any stored pricing/multipliers)
                    fetched.forEach { remote ->
                        if (!mergedMap.containsKey(remote.id)) {
                            val prev = previouslyEditedById[remote.id]
                            val p = savedPricing[remote.id]
                            mergedMap[remote.id] = when {
                                prev != null -> prev
                                p != null -> remote.copy(
                                    multiplierText = formatDecimalInput(p.multiplier),
                                    cacheMultiplierText = formatDecimalInput(p.cacheMultiplier),
                                    inputPricePer1MText = p.inputPricePer1M?.let(::formatDecimalInput).orEmpty(),
                                    outputPricePer1MText = p.outputPricePer1M?.let(::formatDecimalInput).orEmpty(),
                                )
                                else -> remote
                            }
                        }
                    }

                    val mergedList = mergedMap.values.toList().let { list ->
                        if (list.none { it.selected } && list.size == 1) {
                            listOf(list.first().copy(selected = true))
                        } else {
                            list
                        }
                    }
                    val nextDefault = state.setup.defaultSelectedModelId
                        .takeIf { defId -> mergedList.any { it.selected && it.id == defId } }
                        ?: mergedList.firstOrNull { it.selected }?.id.orEmpty()

                    state.copy(
                        setup = state.setup.copy(
                            availableModels = mergedList,
                            defaultSelectedModelId = nextDefault,
                            isFetchingModels = false,
                            fetchModelsError = null,
                            fetchModelsCount = fetched.size,
                        ),
                    )
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                val safeMsg = OpenCodeConfigHelper.sanitizeLogs(
                    err.message ?: "无法加载模型列表",
                    listOf(secretKey),
                )
                _uiState.update {
                    it.copy(
                        setup = it.setup.copy(
                            isFetchingModels = false,
                            fetchModelsError = safeMsg,
                        ),
                    )
                }
            }
        }
    }

    fun testSelectedModel() {
        val connected = connectionManager.connectionState.value as? SshConnectionState.Connected ?: return
        val s = _uiState.value.setup
        val targetModelId = s.effectiveTestModelId
        if (s.baseUrl.isBlank() || targetModelId.isBlank()) {
            _uiState.update {
                it.copy(
                    setup = it.setup.copy(
                        modelTestResult = ModelTestResult(
                            success = false,
                            httpCode = null,
                            modelId = targetModelId,
                            message = "请先至少选择一个模型",
                        ),
                    ),
                )
            }
            return
        }
        val secretKey = s.apiKeyInput

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    setup = it.setup.copy(
                        isTestingModel = true,
                        modelTestResult = null,
                    ),
                )
            }
            runCatching {
                serverInstaller.providerManager.testModelCompletion(
                    baseUrl = s.baseUrl,
                    apiKeyInput = secretKey,
                    existingProviderId = s.editingProviderId,
                    modelId = targetModelId,
                    openCodePort = connected.profile.openCodePort,
                )
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        setup = it.setup.copy(
                            isTestingModel = false,
                            modelTestResult = result,
                        ),
                    )
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                val safeMsg = OpenCodeConfigHelper.sanitizeLogs(
                    err.message ?: "无法测试模型",
                    listOf(secretKey),
                )
                _uiState.update {
                    it.copy(
                        setup = it.setup.copy(
                            isTestingModel = false,
                            modelTestResult = ModelTestResult(
                                success = false,
                                httpCode = null,
                                modelId = targetModelId,
                                message = safeMsg,
                            ),
                        ),
                    )
                }
            }
        }
    }

    fun saveCustomProviderAndFinish() {
        val connected = connectionManager.connectionState.value as? SshConnectionState.Connected ?: return
        val s = _uiState.value.setup
        if (s.baseUrl.isBlank()) return

        val chosenModels = s.selectedModels.ifEmpty {
            val manual = s.manualModelIdInput.trim()
            if (manual.isNotEmpty()) listOf(CustomModelEntry(id = manual, displayName = manual, selected = true))
            else emptyList()
        }
        if (chosenModels.isEmpty()) {
            _uiState.update {
                it.copy(setup = it.setup.copy(errorMessage = "请至少从列表中选择一个模型，或手动输入模型 ID"))
            }
            return
        }

        val secretKey = s.apiKeyInput
        val isEditing = !s.editingProviderId.isNullOrBlank()
        val providerId = s.editingProviderId ?: OpenCodeConfigHelper.sanitizeProviderId(s.providerName)
        val defaultModelId = s.defaultSelectedModelId
            .takeIf { id -> chosenModels.any { it.id == id } }
            ?: chosenModels.first().id

        val request = CustomProviderSaveRequest(
            providerId = providerId,
            providerName = s.providerName.trim().ifBlank { providerId },
            baseUrl = s.baseUrl.trim(),
            apiKey = secretKey.trim().takeIf { it.isNotEmpty() },
            selectedModels = chosenModels,
            defaultModelId = defaultModelId,
            replaceProviderModels = isEditing,
        )

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    setup = it.setup.copy(
                        isSavingProvider = true,
                        errorMessage = null,
                        providerSaveSteps = buildProviderSaveSteps(),
                        providerSaveWarning = null,
                    ),
                )
            }
            runCatching {
                serverInstaller.providerManager.saveProviderAndRestart(
                    profileId = connected.profile.id,
                    request = request,
                    openCodePort = connected.profile.openCodePort,
                    localTunnelPort = connected.localTunnelPort,
                    onStepUpdate = ::updateProviderSaveStep,
                )
            }.onSuccess { outcome ->
                when (outcome) {
                    is ProviderApplyOutcome.Success -> {
                        val savedProviderId = outcome.providerId ?: providerId
                        val savedDefaultModelId = outcome.defaultModelId ?: defaultModelId
                        persistModelPricingForProvider(savedProviderId, chosenModels)
                        completeProviderSaveAndOpenChat(
                            providerId = savedProviderId,
                            defaultModelId = savedDefaultModelId,
                            tunnelPort = outcome.tunnelPort ?: connected.localTunnelPort,
                            toastMessage = "供应商已保存，服务已重启，当前模型为 $savedDefaultModelId",
                        )
                    }

                    is ProviderApplyOutcome.SavedWithCheckWarning -> {
                        val savedProviderId = outcome.providerId ?: providerId
                        persistModelPricingForProvider(savedProviderId, chosenModels)
                        // Config was written & service is active — keep opencode.json, show warning with Retry/Continue
                        _uiState.update { state ->
                            state.copy(
                                setup = state.setup.copy(
                                    isSavingProvider = false,
                                    apiKeyInput = "",
                                    errorMessage = null,
                                    providerSaveWarning = ProviderSaveWarning(
                                        message = outcome.warningMessage,
                                        providerId = savedProviderId,
                                        defaultModelId = outcome.defaultModelId ?: defaultModelId,
                                    ),
                                ),
                            )
                        }
                        loadConfiguredProviders()
                    }

                    is ProviderApplyOutcome.RolledBackServiceInactive -> {
                        _uiState.update { state ->
                            state.copy(
                                setup = state.setup.copy(
                                    isSavingProvider = false,
                                    providerSaveWarning = null,
                                    errorMessage = outcome.errorMessage,
                                ),
                            )
                        }
                        loadConfiguredProviders()
                    }
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                val safeMsg = OpenCodeConfigHelper.sanitizeLogs(
                    err.message ?: "无法将配置保存到服务器",
                    listOf(secretKey),
                )
                updateProviderSaveStep(0, InstallStepStatus.ERROR, safeMsg)
                _uiState.update {
                    it.copy(
                        setup = it.setup.copy(
                            isSavingProvider = false,
                            providerSaveWarning = null,
                            errorMessage = safeMsg,
                        ),
                    )
                }
            }
        }
    }

    fun retryProviderHealthCheck() {
        val connected = connectionManager.connectionState.value as? SshConnectionState.Connected ?: return
        val warning = _uiState.value.setup.providerSaveWarning ?: return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    setup = it.setup.copy(
                        isRetryingProviderCheck = true,
                        errorMessage = null,
                    ),
                )
            }
            runCatching {
                serverInstaller.providerManager.retryServerHealthCheck(
                    profileId = connected.profile.id,
                    localTunnelPort = connected.localTunnelPort,
                    providerId = warning.providerId,
                    defaultModelId = warning.defaultModelId,
                    onStepUpdate = ::updateProviderSaveStep,
                )
            }.onSuccess { outcome ->
                when (outcome) {
                    is ProviderApplyOutcome.Success -> {
                        val tunnel = outcome.tunnelPort ?: connectionManager.getActiveLocalTunnelPort() ?: connected.localTunnelPort
                        if (!outcome.providerId.isNullOrBlank() && !outcome.defaultModelId.isNullOrBlank()) {
                            completeProviderSaveAndOpenChat(
                                providerId = outcome.providerId,
                                defaultModelId = outcome.defaultModelId,
                                tunnelPort = tunnel,
                                toastMessage = "服务器已响应（${outcome.detail}），当前模型为 ${outcome.defaultModelId}",
                            )
                        } else {
                            apiClient.invalidateDiscoveryCache()
                            _uiState.update { state ->
                                state.copy(
                                    setup = state.setup.copy(
                                        isRetryingProviderCheck = false,
                                        providerSaveWarning = null,
                                    ),
                                )
                            }
                            loadConfiguredProviders()
                            refreshAll()
                            startSseStream(tunnel)
                            _toastMessages.tryEmit("OpenCode 服务器已响应（${outcome.detail}）")
                        }
                    }

                    is ProviderApplyOutcome.SavedWithCheckWarning -> {
                        _uiState.update { state ->
                            state.copy(
                                setup = state.setup.copy(
                                    isRetryingProviderCheck = false,
                                    providerSaveWarning = warning.copy(message = outcome.warningMessage),
                                ),
                            )
                        }
                    }

                    is ProviderApplyOutcome.RolledBackServiceInactive -> {
                        _uiState.update { state ->
                            state.copy(
                                setup = state.setup.copy(
                                    isRetryingProviderCheck = false,
                                    providerSaveWarning = null,
                                    errorMessage = outcome.errorMessage,
                                ),
                            )
                        }
                    }
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                _uiState.update {
                    it.copy(
                        setup = it.setup.copy(
                            isRetryingProviderCheck = false,
                            errorMessage = OpenCodeConfigHelper.sanitizeLogs(err.message ?: "重新检查失败"),
                        ),
                    )
                }
            }
        }
    }

    fun continueAfterProviderWarning() {
        val connected = connectionManager.connectionState.value as? SshConnectionState.Connected
        val warning = _uiState.value.setup.providerSaveWarning ?: return
        val tunnelPort = connectionManager.getActiveLocalTunnelPort() ?: connected?.localTunnelPort

        viewModelScope.launch {
            if (!warning.providerId.isNullOrBlank() && !warning.defaultModelId.isNullOrBlank()) {
                completeProviderSaveAndOpenChat(
                    providerId = warning.providerId,
                    defaultModelId = warning.defaultModelId,
                    tunnelPort = tunnelPort,
                    toastMessage = "配置已保存。当前模型为 ${warning.defaultModelId}",
                )
            } else {
                apiClient.invalidateDiscoveryCache()
                _uiState.update { state ->
                    state.copy(
                        setup = state.setup.copy(
                            providerSaveWarning = null,
                            isRetryingProviderCheck = false,
                        ),
                    )
                }
                loadConfiguredProviders()
                refreshAll()
                if (tunnelPort != null) startSseStream(tunnelPort)
            }
        }
    }

    private suspend fun completeProviderSaveAndOpenChat(
        providerId: String,
        defaultModelId: String,
        tunnelPort: Int?,
        toastMessage: String,
    ) {
        apiClient.invalidateDiscoveryCache()
        _uiState.update { state ->
            state.copy(
                selectedProviderId = providerId,
                selectedModelId = defaultModelId,
                openCodePortReachable = true,
                setup = state.setup.copy(
                    serverStatus = OpenCodeServerStatus.RUNNING,
                    wizardStage = SetupWizardStage.IDLE,
                    isSavingProvider = false,
                    isRetryingProviderCheck = false,
                    providerSaveWarning = null,
                    editingProviderId = null,
                    editingMaskedApiKey = null,
                    apiKeyInput = "",
                    modelTestResult = null,
                    fetchModelsError = null,
                    errorMessage = null,
                ),
            )
        }
        preferencesRepository.setDefaultModel(providerId, defaultModelId)
        refreshAll()
        if (tunnelPort != null) {
            startSseStream(tunnelPort)
        }
        _toastMessages.tryEmit(toastMessage)
    }

    fun changeProviderApiKey(provider: ConfiguredProviderInfo, newApiKey: String) {
        val connected = connectionManager.connectionState.value as? SshConnectionState.Connected ?: return
        val cleanKey = newApiKey.trim()
        if (cleanKey.isEmpty()) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    setup = it.setup.copy(
                        isProviderActionBusy = true,
                        configuredProvidersError = null,
                        errorMessage = null,
                        providerSaveSteps = buildProviderSaveSteps(),
                        providerSaveWarning = null,
                    ),
                )
            }
            runCatching {
                serverInstaller.providerManager.updateProviderKeyAndRestart(
                    profileId = connected.profile.id,
                    providerId = provider.providerId,
                    baseUrl = provider.baseUrl,
                    newApiKey = cleanKey,
                    openCodePort = connected.profile.openCodePort,
                    localTunnelPort = connected.localTunnelPort,
                    onStepUpdate = ::updateProviderSaveStep,
                )
            }.onSuccess { outcome ->
                when (outcome) {
                    is ProviderApplyOutcome.Success -> {
                        apiClient.invalidateDiscoveryCache()
                        _uiState.update { state ->
                            state.copy(
                                setup = state.setup.copy(
                                    isProviderActionBusy = false,
                                    editingMaskedApiKey = if (state.setup.editingProviderId == provider.providerId) {
                                        OpenCodeConfigHelper.maskApiKey(cleanKey)
                                    } else {
                                        state.setup.editingMaskedApiKey
                                    },
                                    configuredProvidersError = null,
                                    providerSaveWarning = null,
                                ),
                            )
                        }
                        loadConfiguredProviders()
                        refreshAll()
                        _toastMessages.tryEmit("供应商“${provider.name}”的 API 密钥已更新")
                    }

                    is ProviderApplyOutcome.SavedWithCheckWarning -> {
                        _uiState.update { state ->
                            state.copy(
                                setup = state.setup.copy(
                                    isProviderActionBusy = false,
                                    editingMaskedApiKey = if (state.setup.editingProviderId == provider.providerId) {
                                        OpenCodeConfigHelper.maskApiKey(cleanKey)
                                    } else {
                                        state.setup.editingMaskedApiKey
                                    },
                                    providerSaveWarning = ProviderSaveWarning(
                                        message = outcome.warningMessage,
                                    ),
                                ),
                            )
                        }
                        loadConfiguredProviders()
                    }

                    is ProviderApplyOutcome.RolledBackServiceInactive -> {
                        _uiState.update { state ->
                            state.copy(
                                setup = state.setup.copy(
                                    isProviderActionBusy = false,
                                    configuredProvidersError = outcome.errorMessage,
                                ),
                            )
                        }
                        loadConfiguredProviders()
                    }
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                val safeMsg = OpenCodeConfigHelper.sanitizeLogs(
                    err.message ?: "无法更新 API 密钥",
                    listOf(cleanKey),
                )
                updateProviderSaveStep(0, InstallStepStatus.ERROR, safeMsg)
                _uiState.update {
                    it.copy(
                        setup = it.setup.copy(
                            isProviderActionBusy = false,
                            configuredProvidersError = safeMsg,
                        ),
                    )
                }
            }
        }
    }

    fun deleteConfiguredProvider(provider: ConfiguredProviderInfo) {
        val connected = connectionManager.connectionState.value as? SshConnectionState.Connected ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    setup = it.setup.copy(
                        isProviderActionBusy = true,
                        configuredProvidersError = null,
                        errorMessage = null,
                        providerSaveSteps = buildProviderSaveSteps(),
                        providerSaveWarning = null,
                    ),
                )
            }
            runCatching {
                serverInstaller.providerManager.deleteProviderAndRestart(
                    profileId = connected.profile.id,
                    providerId = provider.providerId,
                    openCodePort = connected.profile.openCodePort,
                    localTunnelPort = connected.localTunnelPort,
                    onStepUpdate = ::updateProviderSaveStep,
                )
            }.onSuccess { outcome ->
                when (outcome) {
                    is ProviderApplyOutcome.Success -> {
                        runCatching { usageRepository.deletePricingForProvider(provider.providerId) }
                        apiClient.invalidateDiscoveryCache()
                        val wasSelected = _uiState.value.selectedProviderId == provider.providerId
                        val wasEditing = _uiState.value.setup.editingProviderId == provider.providerId
                        _uiState.update { state ->
                            state.copy(
                                selectedProviderId = if (wasSelected) "" else state.selectedProviderId,
                                selectedModelId = if (wasSelected) "" else state.selectedModelId,
                                setup = state.setup.copy(
                                    isProviderActionBusy = false,
                                    configuredProvidersError = null,
                                    providerSaveWarning = null,
                                ),
                            )
                        }
                        if (wasSelected) {
                            preferencesRepository.setDefaultModel("", "")
                        }
                        if (wasEditing) {
                            startNewProviderEditor()
                        }
                        loadConfiguredProviders()
                        refreshAll()
                        _toastMessages.tryEmit("供应商“${provider.name}”已删除")
                    }

                    is ProviderApplyOutcome.SavedWithCheckWarning -> {
                        runCatching { usageRepository.deletePricingForProvider(provider.providerId) }
                        val wasSelected = _uiState.value.selectedProviderId == provider.providerId
                        _uiState.update { state ->
                            state.copy(
                                selectedProviderId = if (wasSelected) "" else state.selectedProviderId,
                                selectedModelId = if (wasSelected) "" else state.selectedModelId,
                                setup = state.setup.copy(
                                    isProviderActionBusy = false,
                                    providerSaveWarning = ProviderSaveWarning(message = outcome.warningMessage),
                                ),
                            )
                        }
                        if (wasSelected) {
                            preferencesRepository.setDefaultModel("", "")
                        }
                        loadConfiguredProviders()
                    }

                    is ProviderApplyOutcome.RolledBackServiceInactive -> {
                        _uiState.update { state ->
                            state.copy(
                                setup = state.setup.copy(
                                    isProviderActionBusy = false,
                                    configuredProvidersError = outcome.errorMessage,
                                ),
                            )
                        }
                        loadConfiguredProviders()
                    }
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                val safeMsg = OpenCodeConfigHelper.sanitizeLogs(
                    err.message ?: "无法删除供应商",
                )
                updateProviderSaveStep(0, InstallStepStatus.ERROR, safeMsg)
                _uiState.update {
                    it.copy(
                        setup = it.setup.copy(
                            isProviderActionBusy = false,
                            configuredProvidersError = safeMsg,
                        ),
                    )
                }
            }
        }
    }

    private fun buildProviderSaveSteps(): List<ProviderSaveStepItem> = listOf(
        ProviderSaveStepItem(id = "write_config", title = "正在写入配置（opencode.json）"),
        ProviderSaveStepItem(id = "restart_service", title = "正在重启 opencode-serve 服务"),
        ProviderSaveStepItem(id = "wait_server", title = "等待服务器响应（最多 40 秒）"),
    )

    private fun updateProviderSaveStep(index: Int, status: InstallStepStatus, detail: String?) {
        _uiState.update { state ->
            val current = state.setup.providerSaveSteps.ifEmpty { buildProviderSaveSteps() }
            val updated = current.mapIndexed { idx, item ->
                if (idx == index) item.copy(status = status, detail = detail) else item
            }
            state.copy(setup = state.setup.copy(providerSaveSteps = updated))
        }
    }

    fun finishWithFreeModels() {
        val connected = connectionManager.connectionState.value as? SshConnectionState.Connected
        apiClient.invalidateDiscoveryCache()
        _uiState.update { state ->
            state.copy(
                openCodePortReachable = true,
                setup = state.setup.copy(
                    serverStatus = OpenCodeServerStatus.RUNNING,
                    wizardStage = SetupWizardStage.IDLE,
                    apiKeyInput = "",
                    errorMessage = null,
                    providerSaveWarning = null,
                ),
            )
        }
        if (connected != null) {
            refreshAll()
            startSseStream(connected.localTunnelPort)
        }
    }

    private fun updateStepStatus(index: Int, status: InstallStepStatus, detail: String?) {
        _uiState.update { state ->
            val updated = state.setup.steps.mapIndexed { idx, step ->
                if (idx == index) step.copy(status = status, detail = detail) else step
            }
            state.copy(setup = state.setup.copy(steps = updated))
        }
    }

    private suspend fun persistModelPricingForProvider(
        providerId: String,
        models: List<CustomModelEntry>,
    ) {
        val pricingList = models.map { entry ->
            ModelPricing(
                providerId = providerId,
                modelId = entry.id.trim(),
                multiplier = entry.parsedMultiplier,
                cacheMultiplier = entry.parsedCacheMultiplier,
                inputPricePer1M = entry.parsedInputPricePer1M,
                outputPricePer1M = entry.parsedOutputPricePer1M,
            )
        }
        runCatching { usageRepository.saveProviderModelPricing(providerId, pricingList) }
    }

    private fun currentActiveProfileId(): Long? {
        val connected = connectionManager.connectionState.value as? SshConnectionState.Connected
        return connected?.profile?.id ?: lastProfileId
    }

    private fun filterDecimalInput(raw: String): String {
        val normalized = raw.replace(',', '.')
        val sb = StringBuilder()
        var hasDot = false
        for (ch in normalized) {
            when {
                ch.isDigit() -> sb.append(ch)
                ch == '.' && !hasDot -> {
                    hasDot = true
                    sb.append(ch)
                }
            }
        }
        return sb.toString().take(12)
    }

    private fun formatDecimalInput(value: Double): String {
        return if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            value.toBigDecimal().stripTrailingZeros().toPlainString()
        }
    }

    override fun onCleared() {
        stopSseStream()
        cancelInactivityWatchdog()
        setupJob?.cancel()
        draftSaveJob?.cancel()
        gitBadgeJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val INACTIVITY_WATCHDOG_MS = 60_000L
        private const val MAX_ATTACHED_IMAGES = 4
    }

    class Factory(
        private val connectionManager: SshConnectionManager,
        private val apiClient: OpenCodeApiClient,
        private val sftpRepository: SftpRepository,
        private val preferencesRepository: AppPreferencesRepository,
        private val serverInstaller: OpenCodeServerInstaller,
        private val usageRepository: UsageRepository,
        private val agentNotificationHelper: AgentNotificationHelper,
        private val promptTemplateRepository: PromptTemplateRepository,
        private val gitRepository: GitRepository,
        private val imageAttachmentProcessor: ImageAttachmentProcessor,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(
                connectionManager = connectionManager,
                apiClient = apiClient,
                sftpRepository = sftpRepository,
                preferencesRepository = preferencesRepository,
                serverInstaller = serverInstaller,
                usageRepository = usageRepository,
                agentNotificationHelper = agentNotificationHelper,
                promptTemplateRepository = promptTemplateRepository,
                gitRepository = gitRepository,
                imageAttachmentProcessor = imageAttachmentProcessor,
            ) as T
        }
    }
}
