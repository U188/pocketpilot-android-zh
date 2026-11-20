package com.example.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppPreferencesRepository
import com.example.data.repository.KnownHostsRepository
import com.example.data.repository.TerminalSnippetRepository
import com.example.domain.model.AppSettings
import com.example.domain.model.KnownHost
import com.example.domain.model.TerminalSnippet
import com.example.domain.model.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val knownHosts: List<KnownHost> = emptyList(),
    val snippets: List<TerminalSnippet> = emptyList(),
)

class SettingsViewModel(
    private val preferencesRepository: AppPreferencesRepository,
    private val knownHostsRepository: KnownHostsRepository,
    private val terminalSnippetRepository: TerminalSnippetRepository,
) : ViewModel() {

    init {
        viewModelScope.launch {
            terminalSnippetRepository.ensureDefaultSnippets()
        }
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        preferencesRepository.settings,
        knownHostsRepository.knownHosts,
        terminalSnippetRepository.snippets,
    ) { settings, hosts, snippets ->
        SettingsUiState(
            settings = settings,
            knownHosts = hosts,
            snippets = snippets,
        )
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

    class Factory(
        private val preferencesRepository: AppPreferencesRepository,
        private val knownHostsRepository: KnownHostsRepository,
        private val terminalSnippetRepository: TerminalSnippetRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(
                preferencesRepository = preferencesRepository,
                knownHostsRepository = knownHostsRepository,
                terminalSnippetRepository = terminalSnippetRepository,
            ) as T
        }
    }
}
