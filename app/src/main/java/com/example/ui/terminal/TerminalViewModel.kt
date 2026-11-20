package com.example.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.repository.TerminalSnippetRepository
import com.example.data.ssh.SshConnectionManager
import com.example.data.terminal.SshTerminalSession
import com.example.data.terminal.TerminalScreenSnapshot
import com.example.domain.model.DangerousCommandDetector
import com.example.domain.model.SshConnectionState
import com.example.domain.model.TerminalSnippet
import com.example.domain.model.TerminalTabStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class TerminalTabItem(
    val id: String,
    val title: String,
    val status: TerminalTabStatus,
    val errorMessage: String? = null,
    val snapshot: TerminalScreenSnapshot = TerminalScreenSnapshot(),
)

data class PendingDangerousCommand(
    val command: String,
    val autoExecute: Boolean,
)

data class TerminalUiState(
    val isConnected: Boolean = false,
    val isReconnecting: Boolean = false,
    val tabs: List<TerminalTabItem> = emptyList(),
    val activeTabId: String? = null,
    val ctrlArmed: Boolean = false,
    val altArmed: Boolean = false,
    val fontSizeSp: Float = 13f,
    val snippets: List<TerminalSnippet> = emptyList(),
    val pendingDangerousCommand: PendingDangerousCommand? = null,
) {
    val activeTab: TerminalTabItem?
        get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.firstOrNull()
}

class TerminalViewModel(
    private val connectionManager: SshConnectionManager,
    private val snippetRepository: TerminalSnippetRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TerminalUiState())
    val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

    private val _toastMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastMessages: SharedFlow<String> = _toastMessages.asSharedFlow()

    private val sessions = LinkedHashMap<String, SshTerminalSession>()
    private val sessionObserverJobs = HashMap<String, Job>()
    private var tabCounter = 0
    private var lastViewportCols = 80
    private var lastViewportRows = 24
    private var lastConnectedHostKey: String? = null

    init {
        viewModelScope.launch {
            snippetRepository.ensureDefaultSnippets()
            snippetRepository.snippets.collect { list ->
                _uiState.update { it.copy(snippets = list) }
            }
        }

        viewModelScope.launch {
            connectionManager.connectionState.collect { conn ->
                when (conn) {
                    is SshConnectionState.Connected -> {
                        val hostKey = "${conn.profile.id}:${conn.connectedAtMillis}"
                        val isNewConnection = lastConnectedHostKey != hostKey
                        lastConnectedHostKey = hostKey
                        _uiState.update { it.copy(isConnected = true, isReconnecting = false) }
                        if (isNewConnection) {
                            closeAllTabs()
                            openNewTab(attachTmux = true)
                        } else if (sessions.isEmpty()) {
                            openNewTab(attachTmux = true)
                        }
                    }

                    is SshConnectionState.Reconnecting -> {
                        _uiState.update {
                            it.copy(
                                isReconnecting = true,
                                ctrlArmed = false,
                                altArmed = false,
                            )
                        }
                    }

                    else -> {
                        lastConnectedHostKey = null
                        closeAllTabs()
                        _uiState.update {
                            it.copy(
                                isConnected = false,
                                isReconnecting = false,
                                ctrlArmed = false,
                                altArmed = false,
                                pendingDangerousCommand = null,
                            )
                        }
                    }
                }
            }
        }
    }

    fun openNewTab(attachTmux: Boolean = sessions.isEmpty()) {
        if (!_uiState.value.isConnected) return
        tabCounter++
        val id = UUID.randomUUID().toString()
        val defaultTitle = if (attachTmux) "tmux: pocket" else "Shell $tabCounter"
        val session = SshTerminalSession(
            id = id,
            defaultTitle = defaultTitle,
            connectionManager = connectionManager,
            initialCols = lastViewportCols,
            initialRows = lastViewportRows,
            attachTmuxSession = if (attachTmux) DEFAULT_TMUX_SESSION else null,
        )
        sessions[id] = session

        val initialItem = TerminalTabItem(
            id = id,
            title = defaultTitle,
            status = TerminalTabStatus.CONNECTING,
        )
        _uiState.update { state ->
            state.copy(
                tabs = state.tabs + initialItem,
                activeTabId = id,
            )
        }

        val observer = viewModelScope.launch {
            combine(
                session.status,
                session.errorMessage,
                session.snapshot,
            ) { status, error, snapshot ->
                TerminalTabItem(
                    id = id,
                    title = snapshot.title?.takeIf { it.isNotBlank() } ?: defaultTitle,
                    status = status,
                    errorMessage = error,
                    snapshot = snapshot,
                )
            }.collect { updatedItem ->
                _uiState.update { state ->
                    state.copy(
                        tabs = state.tabs.map { if (it.id == id) updatedItem else it },
                    )
                }
            }
        }
        sessionObserverJobs[id] = observer
        session.start()
    }

    fun selectTab(tabId: String) {
        if (_uiState.value.tabs.any { it.id == tabId }) {
            _uiState.update { it.copy(activeTabId = tabId) }
        }
    }

    fun closeTab(tabId: String) {
        sessionObserverJobs.remove(tabId)?.cancel()
        sessions.remove(tabId)?.close()
        _uiState.update { state ->
            val remaining = state.tabs.filterNot { it.id == tabId }
            val nextActive = if (state.activeTabId == tabId) {
                remaining.lastOrNull()?.id
            } else {
                state.activeTabId
            }
            state.copy(tabs = remaining, activeTabId = nextActive)
        }
    }

    fun reconnectActiveTab() {
        val activeId = _uiState.value.activeTab?.id ?: return
        sessions[activeId]?.start()
    }

    fun onViewportResized(cols: Int, rows: Int) {
        lastViewportCols = cols.coerceIn(20, 240)
        lastViewportRows = rows.coerceIn(8, 120)
        sessions.values.forEach { it.resize(lastViewportCols, lastViewportRows) }
    }

    fun toggleCtrl() {
        _uiState.update { it.copy(ctrlArmed = !it.ctrlArmed) }
    }

    fun toggleAlt() {
        _uiState.update { it.copy(altArmed = !it.altArmed) }
    }

    fun adjustFontSize(deltaSp: Float) {
        _uiState.update { state ->
            state.copy(fontSizeSp = (state.fontSizeSp + deltaSp).coerceIn(9f, 22f))
        }
    }

    fun sendTypedText(text: String) {
        val session = activeSession() ?: return
        if (text.isEmpty()) return

        val state = _uiState.value
        if ((state.ctrlArmed || state.altArmed) && text.length == 1) {
            val ch = text[0]
            when {
                state.ctrlArmed -> {
                    val ctrlByte = mapCtrlByte(ch)
                    if (ctrlByte != null) {
                        session.sendBytes(byteArrayOf(ctrlByte))
                    } else {
                        session.sendText(text)
                    }
                    _uiState.update { it.copy(ctrlArmed = false) }
                }

                state.altArmed -> {
                    session.sendBytes(byteArrayOf(0x1B) + text.toByteArray())
                    _uiState.update { it.copy(altArmed = false) }
                }
            }
            return
        }

        session.sendText(text.replace("\n", "\r"))
    }

    fun sendSpecialKey(sequence: String) {
        val session = activeSession() ?: return
        val state = _uiState.value
        if (state.altArmed) {
            session.sendText("\u001B$sequence")
            _uiState.update { it.copy(altArmed = false) }
        } else {
            session.sendText(sequence)
        }
    }

    fun sendControlChar(letter: Char) {
        val session = activeSession() ?: return
        val ctrlByte = mapCtrlByte(letter) ?: return
        session.sendBytes(byteArrayOf(ctrlByte))
        _uiState.update { it.copy(ctrlArmed = false) }
    }

    fun pasteText(text: String) {
        if (text.isEmpty()) {
            _toastMessages.tryEmit("Буфер обмена пуст")
            return
        }
        if (DangerousCommandDetector.isDangerous(text)) {
            _uiState.update {
                it.copy(
                    pendingDangerousCommand = PendingDangerousCommand(
                        command = text,
                        autoExecute = false,
                    ),
                )
            }
            return
        }
        sendChunkedInternal(text, autoExecute = false)
    }

    fun runSnippet(snippet: TerminalSnippet) {
        if (DangerousCommandDetector.isDangerous(snippet.command)) {
            _uiState.update {
                it.copy(
                    pendingDangerousCommand = PendingDangerousCommand(
                        command = snippet.command,
                        autoExecute = snippet.autoExecute,
                    ),
                )
            }
            return
        }
        sendChunkedInternal(snippet.command, autoExecute = snippet.autoExecute)
    }

    fun confirmDangerousCommand() {
        val pending = _uiState.value.pendingDangerousCommand ?: return
        _uiState.update { it.copy(pendingDangerousCommand = null) }
        sendChunkedInternal(pending.command, autoExecute = pending.autoExecute)
    }

    fun dismissDangerousCommand() {
        _uiState.update { it.copy(pendingDangerousCommand = null) }
    }

    fun clearActiveScreen() {
        activeSession()?.clearScreen()
    }

    fun getActivePlainOutput(): String = activeSession()?.getPlainOutput().orEmpty()

    fun saveSnippet(snippet: TerminalSnippet) {
        viewModelScope.launch {
            snippetRepository.saveSnippet(snippet)
        }
    }

    fun deleteSnippet(id: Long) {
        viewModelScope.launch {
            snippetRepository.deleteSnippet(id)
        }
    }

    private fun sendChunkedInternal(text: String, autoExecute: Boolean) {
        val session = activeSession() ?: return
        val payload = if (autoExecute) "$text\r" else text
        viewModelScope.launch {
            if (payload.length > SshTerminalSession.PASTE_CHUNK_BYTES) {
                _toastMessages.tryEmit("Вставка ${payload.length} символов порциями…")
            }
            session.sendTextChunked(payload)
        }
    }

    private fun activeSession(): SshTerminalSession? {
        val activeId = _uiState.value.activeTab?.id ?: return null
        return sessions[activeId]
    }

    private fun closeAllTabs() {
        sessionObserverJobs.values.forEach { it.cancel() }
        sessionObserverJobs.clear()
        sessions.values.forEach { it.close() }
        sessions.clear()
        tabCounter = 0
        _uiState.update { it.copy(tabs = emptyList(), activeTabId = null) }
    }

    private fun mapCtrlByte(ch: Char): Byte? {
        val upper = ch.uppercaseChar()
        return when (upper) {
            in 'A'..'Z' -> (upper.code - 'A'.code + 1).toByte()
            '[' -> 27.toByte()
            '\\' -> 28.toByte()
            ']' -> 29.toByte()
            '^' -> 30.toByte()
            '_' -> 31.toByte()
            ' ' -> 0.toByte()
            else -> null
        }
    }

    override fun onCleared() {
        closeAllTabs()
        super.onCleared()
    }

    class Factory(
        private val connectionManager: SshConnectionManager,
        private val snippetRepository: TerminalSnippetRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return TerminalViewModel(
                connectionManager = connectionManager,
                snippetRepository = snippetRepository,
            ) as T
        }
    }

    companion object {
        const val DEFAULT_TMUX_SESSION = "pocket"
    }
}
