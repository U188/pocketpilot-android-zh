package com.example.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.repository.ProfileBackupManager
import com.example.data.repository.ProfileImportResult
import com.example.data.repository.ServerProfileRepository
import com.example.data.ssh.SshConnectionManager
import com.example.domain.model.ServerProfile
import com.example.domain.model.SshConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ServersUiState(
    val profiles: List<ServerProfile> = emptyList(),
    val connectionState: SshConnectionState = SshConnectionState.Disconnected,
)

class ServersViewModel(
    private val profileRepository: ServerProfileRepository,
    private val connectionManager: SshConnectionManager,
    private val profileBackupManager: ProfileBackupManager,
) : ViewModel() {

    val uiState: StateFlow<ServersUiState> = combine(
        profileRepository.profiles,
        connectionManager.connectionState,
    ) { profiles, connectionState ->
        ServersUiState(
            profiles = profiles,
            connectionState = connectionState,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ServersUiState(),
    )

    fun connect(profileId: Long) {
        connectionManager.connect(profileId)
    }

    fun disconnect() {
        connectionManager.disconnect()
    }

    fun reconnect() {
        connectionManager.reconnectActive()
    }

    fun deleteProfile(profileId: Long) {
        viewModelScope.launch {
            val activeId = when (val state = connectionManager.connectionState.value) {
                is SshConnectionState.Connected -> state.profile.id
                is SshConnectionState.Connecting -> state.profile.id
                is SshConnectionState.AwaitingHostKeyVerification -> state.profile.id
                is SshConnectionState.Reconnecting -> state.profile.id
                is SshConnectionState.Error -> state.profile?.id
                SshConnectionState.Disconnected -> null
            }
            if (activeId == profileId) {
                connectionManager.disconnect()
            }
            profileRepository.deleteProfile(profileId)
        }
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
        private val profileRepository: ServerProfileRepository,
        private val connectionManager: SshConnectionManager,
        private val profileBackupManager: ProfileBackupManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ServersViewModel(profileRepository, connectionManager, profileBackupManager) as T
        }
    }
}
