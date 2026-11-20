package com.example.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.repository.ServerProfileRepository
import com.example.data.ssh.SshConnectionManager
import com.example.data.ssh.SshKeyGenerator
import com.example.domain.model.AuthType
import com.example.domain.model.ConnectionTestState
import com.example.domain.model.GeneratedSshKey
import com.example.domain.model.ServerProfile
import com.example.domain.model.ServerSecrets
import com.example.domain.model.SshKeyAlgorithm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerEditorFormState(
    val profileId: Long = 0L,
    val isEditing: Boolean = false,
    val isLoaded: Boolean = false,
    val name: String = "",
    val host: String = "",
    val sshPortText: String = "22",
    val openCodePortText: String = "4096",
    val username: String = "",
    val authType: AuthType = AuthType.PRIVATE_KEY,
    val passwordInput: String = "",
    val privateKeyInput: String = "",
    val passphraseInput: String = "",
    val hasStoredPassword: Boolean = false,
    val hasStoredPrivateKey: Boolean = false,
    val hasStoredPassphrase: Boolean = false,
    val importedKeyName: String? = null,
    val generatedKey: GeneratedSshKey? = null,
    val isGeneratingKey: Boolean = false,
    val autoReconnect: Boolean = true,
    val validationErrorRes: Int? = null,
    val isSaving: Boolean = false,
    val savedSuccessfully: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastConnectedAt: Long? = null,
)

data class ServerEditorUiState(
    val form: ServerEditorFormState = ServerEditorFormState(),
    val testState: ConnectionTestState = ConnectionTestState.Idle,
)

class ServerEditorViewModel(
    private val initialProfileId: Long,
    private val profileRepository: ServerProfileRepository,
    private val keyGenerator: SshKeyGenerator,
    private val connectionManager: SshConnectionManager,
) : ViewModel() {

    private val formState = MutableStateFlow(
        ServerEditorFormState(
            profileId = if (initialProfileId > 0L) initialProfileId else 0L,
            isEditing = initialProfileId > 0L,
            isLoaded = initialProfileId <= 0L,
        ),
    )

    val uiState: StateFlow<ServerEditorUiState> = combine(
        formState,
        connectionManager.testState,
    ) { form, testState ->
        ServerEditorUiState(form = form, testState = testState)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ServerEditorUiState(form = formState.value),
    )

    init {
        connectionManager.cancelConnectionTest()
        if (initialProfileId > 0L) {
            loadExistingProfile(initialProfileId)
        }
    }

    private fun loadExistingProfile(profileId: Long) {
        viewModelScope.launch {
            val profile = profileRepository.getProfile(profileId)
            if (profile == null) {
                formState.update { it.copy(isLoaded = true, isEditing = false, profileId = 0L) }
                return@launch
            }
            val hasPwd = profileRepository.hasStoredPassword(profileId)
            val hasKey = profileRepository.hasStoredPrivateKey(profileId)
            val hasPass = profileRepository.hasStoredPassphrase(profileId)

            formState.update {
                it.copy(
                    profileId = profile.id,
                    isEditing = true,
                    isLoaded = true,
                    name = profile.name,
                    host = profile.host,
                    sshPortText = profile.sshPort.toString(),
                    openCodePortText = profile.openCodePort.toString(),
                    username = profile.username,
                    authType = profile.authType,
                    hasStoredPassword = hasPwd,
                    hasStoredPrivateKey = hasKey,
                    hasStoredPassphrase = hasPass,
                    autoReconnect = profile.autoReconnect,
                    createdAt = profile.createdAt,
                    lastConnectedAt = profile.lastConnectedAt,
                )
            }
        }
    }

    fun onNameChange(value: String) = updateForm { copy(name = value, validationErrorRes = null) }
    fun onHostChange(value: String) = updateForm { copy(host = value, validationErrorRes = null) }
    fun onSshPortChange(value: String) = updateForm {
        copy(sshPortText = value.filter { it.isDigit() }.take(5), validationErrorRes = null)
    }

    fun onOpenCodePortChange(value: String) = updateForm {
        copy(openCodePortText = value.filter { it.isDigit() }.take(5), validationErrorRes = null)
    }

    fun onUsernameChange(value: String) = updateForm { copy(username = value, validationErrorRes = null) }
    fun onAuthTypeChange(type: AuthType) = updateForm { copy(authType = type, validationErrorRes = null) }
    fun onPasswordChange(value: String) = updateForm { copy(passwordInput = value, validationErrorRes = null) }
    fun onPrivateKeyChange(value: String) = updateForm {
        copy(
            privateKeyInput = value,
            importedKeyName = null,
            generatedKey = null,
            validationErrorRes = null,
        )
    }

    fun onPassphraseChange(value: String) = updateForm { copy(passphraseInput = value, validationErrorRes = null) }
    fun onAutoReconnectChange(enabled: Boolean) = updateForm { copy(autoReconnect = enabled) }

    fun onKeyImported(fileName: String, content: String) {
        updateForm {
            copy(
                privateKeyInput = content.trim(),
                importedKeyName = fileName,
                generatedKey = null,
                validationErrorRes = null,
            )
        }
    }

    fun generateKey(algorithm: SshKeyAlgorithm) {
        if (formState.value.isGeneratingKey) return
        viewModelScope.launch {
            formState.update { it.copy(isGeneratingKey = true, validationErrorRes = null) }
            runCatching {
                val comment = formState.value.username.trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let { "$it@opencode-android" }
                    ?: "opencode-android"
                keyGenerator.generate(algorithm, comment)
            }.onSuccess { generated ->
                formState.update {
                    it.copy(
                        isGeneratingKey = false,
                        privateKeyInput = generated.privateKeyPem,
                        generatedKey = generated,
                        importedKeyName = null,
                        passphraseInput = "",
                    )
                }
            }.onFailure {
                formState.update { it.copy(isGeneratingKey = false) }
            }
        }
    }

    fun testConnection() {
        val current = formState.value
        val errorRes = validate(current)
        if (errorRes != null) {
            formState.update { it.copy(validationErrorRes = errorRes) }
            return
        }

        viewModelScope.launch {
            val draftProfile = buildProfile(current)
            val stored = if (current.isEditing && current.profileId > 0L) {
                profileRepository.getSecrets(current.profileId)
            } else {
                ServerSecrets()
            }
            val secrets = ServerSecrets(
                password = current.passwordInput.takeIf { it.isNotEmpty() } ?: stored.password,
                privateKeyPem = current.privateKeyInput.takeIf { it.isNotBlank() } ?: stored.privateKeyPem,
                passphrase = current.passphraseInput.takeIf { it.isNotEmpty() } ?: stored.passphrase,
            )
            connectionManager.testConnection(draftProfile, secrets)
        }
    }

    fun cancelTestConnection() {
        connectionManager.cancelConnectionTest()
    }

    fun resolveTestHostKey(approved: Boolean) {
        connectionManager.resolveTestHostKeyDecision(approved)
    }

    fun save() {
        val current = formState.value
        if (current.isSaving) return
        val errorRes = validate(current)
        if (errorRes != null) {
            formState.update { it.copy(validationErrorRes = errorRes) }
            return
        }

        viewModelScope.launch {
            formState.update { it.copy(isSaving = true, validationErrorRes = null) }
            val profile = buildProfile(current)
            profileRepository.saveProfile(
                profile = profile,
                newPassword = current.passwordInput.takeIf { it.isNotEmpty() },
                newPrivateKeyPem = current.privateKeyInput.takeIf { it.isNotBlank() },
                newPassphrase = current.passphraseInput.takeIf { it.isNotEmpty() },
                keepExistingSecrets = current.isEditing,
            )
            connectionManager.cancelConnectionTest()
            formState.update { it.copy(isSaving = false, savedSuccessfully = true) }
        }
    }

    private fun validate(state: ServerEditorFormState): Int? {
        if (state.name.isBlank()) return com.example.R.string.editor_error_name_required
        if (state.host.isBlank()) return com.example.R.string.editor_error_host_required
        val sshPort = state.sshPortText.toIntOrNull()
        if (sshPort == null || sshPort !in 1..65535) return com.example.R.string.editor_error_port_invalid
        val ocPort = state.openCodePortText.toIntOrNull()
        if (ocPort == null || ocPort !in 1..65535) return com.example.R.string.editor_error_port_invalid
        if (state.username.isBlank()) return com.example.R.string.editor_error_username_required

        return when (state.authType) {
            AuthType.PASSWORD -> {
                if (state.passwordInput.isEmpty() && !state.hasStoredPassword) {
                    com.example.R.string.editor_error_password_required
                } else {
                    null
                }
            }

            AuthType.PRIVATE_KEY -> {
                if (state.privateKeyInput.isBlank() && !state.hasStoredPrivateKey) {
                    com.example.R.string.editor_error_key_required
                } else {
                    null
                }
            }
        }
    }

    private fun buildProfile(state: ServerEditorFormState): ServerProfile = ServerProfile(
        id = state.profileId,
        name = state.name.trim(),
        host = state.host.trim(),
        sshPort = state.sshPortText.toIntOrNull() ?: 22,
        openCodePort = state.openCodePortText.toIntOrNull() ?: 4096,
        username = state.username.trim(),
        authType = state.authType,
        hasPassphrase = state.passphraseInput.isNotEmpty() || state.hasStoredPassphrase,
        autoReconnect = state.autoReconnect,
        createdAt = state.createdAt,
        lastConnectedAt = state.lastConnectedAt,
    )

    private inline fun updateForm(transform: ServerEditorFormState.() -> ServerEditorFormState) {
        formState.update(transform)
        connectionManager.resetTestState()
    }

    override fun onCleared() {
        connectionManager.cancelConnectionTest()
        super.onCleared()
    }

    class Factory(
        private val profileId: Long,
        private val profileRepository: ServerProfileRepository,
        private val keyGenerator: SshKeyGenerator,
        private val connectionManager: SshConnectionManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ServerEditorViewModel(
                initialProfileId = profileId,
                profileRepository = profileRepository,
                keyGenerator = keyGenerator,
                connectionManager = connectionManager,
            ) as T
        }
    }
}
