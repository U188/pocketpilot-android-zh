package com.example.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppPreferencesRepository
import com.example.data.opencode.OpenCodeConfigHelper
import com.example.data.opencode.OpenCodeServerInstaller
import com.example.data.repository.KnownHostsRepository
import com.example.data.repository.ProfileBackupManager
import com.example.data.repository.ProfileImportResult
import com.example.data.repository.TerminalSnippetRepository
import com.example.data.ssh.SshConnectionManager
import com.example.data.update.AppUpdateManager
import com.example.data.update.AppUpdateUiState
import com.example.domain.model.AppSettings
import com.example.domain.model.KnownHost
import com.example.domain.model.SshConnectionState
import com.example.domain.model.SystemdServiceState
import com.example.domain.model.TerminalSnippet
import com.example.domain.model.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerAdminState(
    val isRefreshingStatus: Boolean = false,
    val serviceState: SystemdServiceState = SystemdServiceState.UNKNOWN,
    val opencodeVersion: String? = null,
    val isUserService: Boolean = false,
    val isTransientService: Boolean = false,
    val hasPasswordProtection: Boolean = false,
    val isRunningAction: Boolean = false,
    val statusMessage: String? = null,
    val isError: Boolean = false,
    val showLogsDialog: Boolean = false,
    val isLoadingLogs: Boolean = false,
    val serverLogs: String = "",
)

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val knownHosts: List<KnownHost> = emptyList(),
    val snippets: List<TerminalSnippet> = emptyList(),
    val isSshConnected: Boolean = false,
    val activeServerName: String = "",
    val serverAdmin: ServerAdminState = ServerAdminState(),
    val appUpdate: AppUpdateUiState = AppUpdateUiState(),
)

class SettingsViewModel(
    private val preferencesRepository: AppPreferencesRepository,
    private val knownHostsRepository: KnownHostsRepository,
    private val terminalSnippetRepository: TerminalSnippetRepository,
    private val connectionManager: SshConnectionManager,
    private val serverInstaller: OpenCodeServerInstaller,
    private val appUpdateManager: AppUpdateManager,
    private val profileBackupManager: ProfileBackupManager,
) : ViewModel() {

    private val serverAdminFlow = MutableStateFlow(ServerAdminState())

    init {
        viewModelScope.launch {
            terminalSnippetRepository.ensureDefaultSnippets()
        }
    }

    private val coreStateFlow = combine(
        preferencesRepository.settings,
        knownHostsRepository.knownHosts,
        terminalSnippetRepository.snippets,
        connectionManager.connectionState,
        serverAdminFlow,
    ) { settings, hosts, snippets, conn, admin ->
        val connected = conn as? SshConnectionState.Connected
        SettingsUiState(
            settings = settings,
            knownHosts = hosts,
            snippets = snippets,
            isSshConnected = connected != null,
            activeServerName = connected?.profile?.name.orEmpty(),
            serverAdmin = admin,
        )
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        coreStateFlow,
        appUpdateManager.state,
    ) { core, updateState ->
        core.copy(appUpdate = updateState)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            preferencesRepository.setThemeMode(mode)
        }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setDynamicColor(enabled)
        }
    }

    fun setEditorFontSize(sizeSp: Float) {
        viewModelScope.launch {
            preferencesRepository.setEditorFontSizeSp(sizeSp)
        }
    }

    fun setEditorWordWrap(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setEditorWordWrap(enabled)
        }
    }

    fun setDefaultAgent(agentName: String) {
        viewModelScope.launch {
            preferencesRepository.setDefaultAgent(agentName)
        }
    }

    fun setKeepAliveInBackground(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setKeepAliveInBackground(enabled)
        }
    }

    fun setBiometricLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setBiometricLockEnabled(enabled)
        }
    }

    fun setBiometricLockTimeoutSec(seconds: Int) {
        viewModelScope.launch {
            preferencesRepository.setBiometricLockTimeoutSec(seconds)
        }
    }

    fun setSecureScreenEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setSecureScreenEnabled(enabled)
        }
    }

    fun setAutoCheckUpdates(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAutoCheckUpdates(enabled)
        }
    }

    fun checkForUpdatesManual() {
        viewModelScope.launch {
            appUpdateManager.checkForUpdates(force = true)
        }
    }

    fun openAvailableUpdateDialog() {
        appUpdateManager.openUpdateDialog()
    }

    fun saveSnippet(snippet: TerminalSnippet) {
        viewModelScope.launch {
            terminalSnippetRepository.saveSnippet(snippet)
        }
    }

    fun deleteSnippet(id: Long) {
        viewModelScope.launch {
            terminalSnippetRepository.deleteSnippet(id)
        }
    }

    fun removeKnownHost(hostPortKey: String) {
        viewModelScope.launch {
            knownHostsRepository.remove(hostPortKey)
        }
    }

    fun refreshServerStatus() {
        val connected = connectionManager.connectionState.value as? SshConnectionState.Connected ?: return
        viewModelScope.launch {
            serverAdminFlow.update { it.copy(isRefreshingStatus = true) }
            runCatching {
                serverInstaller.inspectEnvironment(
                    openCodePort = connected.profile.openCodePort,
                    requireSystemd = false,
                )
            }.onSuccess { env ->
                serverAdminFlow.update {
                    it.copy(
                        isRefreshingStatus = false,
                        serviceState = env.serviceState,
                        opencodeVersion = env.opencodeVersion,
                        isUserService = env.isUserService,
                        isTransientService = env.isTransientService,
                        hasPasswordProtection = env.hasPasswordProtection,
                    )
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                serverAdminFlow.update {
                    it.copy(
                        isRefreshingStatus = false,
                        serviceState = SystemdServiceState.UNKNOWN,
                    )
                }
            }
        }
    }

    fun updateOpenCodeOnServer() {
        val connected = connectionManager.connectionState.value as? SshConnectionState.Connected ?: return
        viewModelScope.launch {
            serverAdminFlow.update {
                it.copy(
                    isRunningAction = true,
                    statusMessage = "Обновление OpenCode…",
                    isError = false,
                )
            }
            runCatching {
                serverInstaller.updateOpenCode(
                    profileId = connected.profile.id,
                    port = connected.profile.openCodePort,
                    localTunnelPort = connected.localTunnelPort,
                    onLog = {},
                )
            }.onSuccess {
                serverAdminFlow.update {
                    it.copy(
                        isRunningAction = false,
                        statusMessage = "OpenCode обновлён и запущен на 127.0.0.1:${connected.profile.openCodePort}",
                        isError = false,
                    )
                }
                refreshServerStatus()
            }.onFailure { err ->
                if (err is CancellationException) throw err
                serverAdminFlow.update {
                    it.copy(
                        isRunningAction = false,
                        statusMessage = OpenCodeConfigHelper.sanitizeLogs(err.message ?: "Ошибка обновления OpenCode"),
                        isError = true,
                    )
                }
            }
        }
    }

    fun restartOpenCodeOnServer() {
        val connected = connectionManager.connectionState.value as? SshConnectionState.Connected ?: return
        viewModelScope.launch {
            serverAdminFlow.update {
                it.copy(
                    isRunningAction = true,
                    statusMessage = "Перезапуск службы opencode-serve…",
                    isError = false,
                )
            }
            runCatching {
                serverInstaller.restartOpenCodeServer(
                    profileId = connected.profile.id,
                    port = connected.profile.openCodePort,
                    localTunnelPort = connected.localTunnelPort,
                    onLog = {},
                )
            }.onSuccess {
                serverAdminFlow.update {
                    it.copy(
                        isRunningAction = false,
                        statusMessage = "Служба opencode-serve перезапущена",
                        isError = false,
                    )
                }
                refreshServerStatus()
            }.onFailure { err ->
                if (err is CancellationException) throw err
                serverAdminFlow.update {
                    it.copy(
                        isRunningAction = false,
                        statusMessage = OpenCodeConfigHelper.sanitizeLogs(err.message ?: "Не удалось перезапустить службу"),
                        isError = true,
                    )
                }
                refreshServerStatus()
            }
        }
    }

    fun stopOpenCodeOnServer() {
        val connected = connectionManager.connectionState.value as? SshConnectionState.Connected ?: return
        viewModelScope.launch {
            serverAdminFlow.update {
                it.copy(
                    isRunningAction = true,
                    statusMessage = "Остановка службы opencode-serve…",
                    isError = false,
                )
            }
            runCatching {
                serverInstaller.stopOpenCodeServer(
                    port = connected.profile.openCodePort,
                    onLog = {},
                )
            }.onSuccess {
                serverAdminFlow.update {
                    it.copy(
                        isRunningAction = false,
                        statusMessage = "Служба opencode-serve остановлена",
                        isError = false,
                    )
                }
                refreshServerStatus()
            }.onFailure { err ->
                if (err is CancellationException) throw err
                serverAdminFlow.update {
                    it.copy(
                        isRunningAction = false,
                        statusMessage = OpenCodeConfigHelper.sanitizeLogs(err.message ?: "Не удалось остановить службу"),
                        isError = true,
                    )
                }
                refreshServerStatus()
            }
        }
    }

    fun removeOpenCodeService() {
        val connected = connectionManager.connectionState.value as? SshConnectionState.Connected ?: return
        viewModelScope.launch {
            serverAdminFlow.update {
                it.copy(
                    isRunningAction = true,
                    statusMessage = "Удаление службы opencode-serve…",
                    isError = false,
                )
            }
            runCatching {
                serverInstaller.removeOpenCodeService(
                    profileId = connected.profile.id,
                    port = connected.profile.openCodePort,
                    onLog = {},
                )
            }.onSuccess {
                serverAdminFlow.update {
                    it.copy(
                        isRunningAction = false,
                        statusMessage = "Служба opencode-serve удалена с сервера",
                        isError = false,
                    )
                }
                refreshServerStatus()
            }.onFailure { err ->
                if (err is CancellationException) throw err
                serverAdminFlow.update {
                    it.copy(
                        isRunningAction = false,
                        statusMessage = OpenCodeConfigHelper.sanitizeLogs(err.message ?: "Не удалось удалить службу"),
                        isError = true,
                    )
                }
                refreshServerStatus()
            }
        }
    }

    fun openServerLogs() {
        val connected = connectionManager.connectionState.value as? SshConnectionState.Connected ?: return
        serverAdminFlow.update {
            it.copy(showLogsDialog = true, isLoadingLogs = true)
        }
        viewModelScope.launch {
            runCatching {
                serverInstaller.fetchServerLogs(
                    profileId = connected.profile.id,
                    lines = 100,
                )
            }.onSuccess { logs ->
                serverAdminFlow.update {
                    it.copy(
                        isLoadingLogs = false,
                        serverLogs = logs,
                    )
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                serverAdminFlow.update {
                    it.copy(
                        isLoadingLogs = false,
                        serverLogs = "Не удалось получить логи (${connected.profile.name}): ${OpenCodeConfigHelper.sanitizeLogs(err.message.orEmpty())}",
                    )
                }
            }
        }
    }

    fun dismissServerLogs() {
        serverAdminFlow.update { it.copy(showLogsDialog = false) }
    }

    suspend fun exportEncryptedBackup(password: String): Result<String> {
        return try {
            Result.success(profileBackupManager.exportEncryptedBackup(password))
        } catch (c: CancellationException) {
            throw c
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    suspend fun importEncryptedBackup(
        encryptedContent: String,
        password: String,
    ): Result<ProfileImportResult> {
        return try {
            Result.success(profileBackupManager.importEncryptedBackup(encryptedContent, password))
        } catch (c: CancellationException) {
            throw c
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    class Factory(
        private val preferencesRepository: AppPreferencesRepository,
        private val knownHostsRepository: KnownHostsRepository,
        private val terminalSnippetRepository: TerminalSnippetRepository,
        private val connectionManager: SshConnectionManager,
        private val serverInstaller: OpenCodeServerInstaller,
        private val appUpdateManager: AppUpdateManager,
        private val profileBackupManager: ProfileBackupManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(
                preferencesRepository = preferencesRepository,
                knownHostsRepository = knownHostsRepository,
                terminalSnippetRepository = terminalSnippetRepository,
                connectionManager = connectionManager,
                serverInstaller = serverInstaller,
                appUpdateManager = appUpdateManager,
                profileBackupManager = profileBackupManager,
            ) as T
        }
    }
}
