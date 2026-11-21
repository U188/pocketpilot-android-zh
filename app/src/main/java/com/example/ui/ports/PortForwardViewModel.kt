package com.example.ui.ports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.ssh.PortForwardManager
import com.example.data.ssh.PortForwardParser
import com.example.domain.model.DiscoveredRemotePort
import com.example.domain.model.PortForwardRule
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PortForwardUiState(
    val rules: List<PortForwardRule> = emptyList(),
    val discoveredPorts: List<DiscoveredRemotePort> = emptyList(),
    val isDiscovering: Boolean = false,
    val isSubmitting: Boolean = false,
    val discoveryError: String? = null,
    val formRemotePort: String = "",
    val formLabel: String = "",
    val formRemoteHost: String = "127.0.0.1",
    val formLocalPort: String = "",
    val formError: String? = null,
    val showAdvancedHostFields: Boolean = false,
) {
    val activeCount: Int
        get() = rules.count { it.isActive }
}

class PortForwardViewModel(
    private val portForwardManager: PortForwardManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PortForwardUiState())
    val uiState: StateFlow<PortForwardUiState> = _uiState.asStateFlow()

    private val _toastMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastMessages: SharedFlow<String> = _toastMessages.asSharedFlow()

    init {
        viewModelScope.launch {
            portForwardManager.rules.collect { rules ->
                _uiState.update { it.copy(rules = rules) }
            }
        }
    }

    fun onSheetOpened() {
        refreshDiscoveredPorts()
    }

    fun refreshDiscoveredPorts() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDiscovering = true, discoveryError = null) }
            portForwardManager.discoverListeningPorts()
                .onSuccess { ports ->
                    _uiState.update {
                        it.copy(
                            discoveredPorts = ports,
                            isDiscovering = false,
                            discoveryError = null,
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isDiscovering = false,
                            discoveryError = err.message ?: "无法扫描服务器端口",
                        )
                    }
                }
        }
    }

    fun onFormRemotePortChange(value: String) {
        val digitsOnly = value.filter { it.isDigit() }.take(5)
        _uiState.update { it.copy(formRemotePort = digitsOnly, formError = null) }
    }

    fun onFormLabelChange(value: String) {
        _uiState.update { it.copy(formLabel = value.take(40)) }
    }

    fun onFormRemoteHostChange(value: String) {
        _uiState.update { it.copy(formRemoteHost = value.take(128), formError = null) }
    }

    fun onFormLocalPortChange(value: String) {
        val digitsOnly = value.filter { it.isDigit() }.take(5)
        _uiState.update { it.copy(formLocalPort = digitsOnly, formError = null) }
    }

    fun toggleAdvancedHostFields() {
        _uiState.update { it.copy(showAdvancedHostFields = !it.showAdvancedHostFields) }
    }

    fun forwardDiscoveredPort(port: DiscoveredRemotePort) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, formError = null) }
            val label = port.processName?.takeIf { it.isNotBlank() }
                ?: if (port.isOpenCodePort) "OpenCode" else "端口 ${port.port}"
            portForwardManager.startForward(
                remotePort = port.port,
                remoteHost = "127.0.0.1",
                preferredLocalPort = 0,
                label = label,
            ).onSuccess { rule ->
                _uiState.update { it.copy(isSubmitting = false) }
                _toastMessages.tryEmit("隧道已启动：127.0.0.1:${rule.localPort} → :${rule.remotePort}")
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        formError = err.message ?: "端口转发失败",
                    )
                }
            }
        }
    }

    fun addManualForward() {
        val state = _uiState.value
        val remotePort = PortForwardParser.validatePort(state.formRemotePort)
        if (remotePort == null) {
            _uiState.update { it.copy(formError = "请输入远程端口（1–65535）") }
            return
        }
        val remoteHost = PortForwardParser.validateRemoteHost(state.formRemoteHost)
        if (remoteHost == null) {
            _uiState.update { it.copy(formError = "远程主机地址无效") }
            return
        }
        val preferredLocal = if (state.formLocalPort.isBlank()) {
            0
        } else {
            PortForwardParser.validatePort(state.formLocalPort) ?: run {
                _uiState.update { it.copy(formError = "本地端口必须在 1–65535 范围内") }
                return
            }
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, formError = null) }
            portForwardManager.startForward(
                remotePort = remotePort,
                remoteHost = remoteHost,
                preferredLocalPort = preferredLocal,
                label = state.formLabel,
            ).onSuccess { rule ->
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        formRemotePort = "",
                        formLabel = "",
                        formLocalPort = "",
                        formError = null,
                    )
                }
                _toastMessages.tryEmit("远程端口 :${rule.remotePort} 已映射到 ${rule.localUrl}")
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        formError = err.message ?: "无法创建端口转发",
                    )
                }
            }
        }
    }

    fun stopForward(id: String) {
        viewModelScope.launch {
            portForwardManager.stopForward(id)
            _toastMessages.tryEmit("端口转发已停止")
        }
    }

    fun stopAllForwards() {
        viewModelScope.launch {
            portForwardManager.stopAllForwards()
            _toastMessages.tryEmit("所有自定义隧道均已停止")
        }
    }

    class Factory(
        private val portForwardManager: PortForwardManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PortForwardViewModel(portForwardManager = portForwardManager) as T
        }
    }
}
