package com.example.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppPreferencesRepository
import com.example.data.sftp.SftpRepository
import com.example.domain.model.SaveFileResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class CodeEditorUiState(
    val remotePath: String = "",
    val fileName: String = "",
    val language: CodeLanguage = CodeLanguage.PLAIN,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val textValue: TextFieldValue = TextFieldValue(""),
    val originalText: String = "",
    val lastSyncedMtimeEpochSec: Long? = null,
    val remoteChangedExternally: Boolean = false,
    val showConflictDialog: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val fontSizeSp: Float = 13f,
    val wordWrap: Boolean = true,
    val findVisible: Boolean = false,
    val findQuery: String = "",
    val replaceQuery: String = "",
    val matchCase: Boolean = false,
    val searchMatches: List<IntRange> = emptyList(),
    val activeMatchIndex: Int = -1,
) {
    val isDirty: Boolean
        get() = textValue.text != originalText

    val lineCount: Int
        get() = textValue.text.count { it == '\n' } + 1
}

sealed interface CodeEditorEvent {
    data object Saved : CodeEditorEvent
    data object ReloadedFromServer : CodeEditorEvent
    data object CloseRequested : CodeEditorEvent
    data class ErrorToast(val message: String) : CodeEditorEvent
}

class CodeEditorViewModel(
    private val remotePath: String,
    private val initialLine: Int?,
    private val sftpRepository: SftpRepository,
    private val preferencesRepository: AppPreferencesRepository,
) : ViewModel() {

    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()

    private val _uiState = MutableStateFlow(
        CodeEditorUiState(
            remotePath = remotePath,
            fileName = remotePath.substringAfterLast('/', remotePath),
            language = CodeLanguage.detect(remotePath.substringAfterLast('/', remotePath)),
        ),
    )
    val uiState: StateFlow<CodeEditorUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<CodeEditorEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<CodeEditorEvent> = _events.asSharedFlow()

    init {
        observePreferences()
        loadFile(jumpToLine = initialLine)
        startRemoteChangeWatcher()
    }

    private fun observePreferences() {
        viewModelScope.launch {
            preferencesRepository.settings.collect { settings ->
                _uiState.update {
                    it.copy(
                        fontSizeSp = settings.editorFontSizeSp,
                        wordWrap = settings.editorWordWrap,
                    )
                }
            }
        }
    }

    fun loadFile(jumpToLine: Int? = null, emitReloadEvent: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                sftpRepository.readTextFile(remotePath)
            }.onSuccess { content ->
                undoStack.clear()
                redoStack.clear()
                val cursorOffset = jumpToLine?.let { computeOffsetForLine(content.text, it) } ?: 0
                val initialValue = TextFieldValue(
                    text = content.text,
                    selection = TextRange(cursorOffset),
                )
                _uiState.update { state ->
                    val matches = computeSearchMatches(content.text, state.findQuery, state.matchCase)
                    state.copy(
                        fileName = content.fileName,
                        language = CodeLanguage.detect(content.fileName),
                        isLoading = false,
                        errorMessage = null,
                        textValue = initialValue,
                        originalText = content.text,
                        lastSyncedMtimeEpochSec = content.modifiedAtEpochSec,
                        remoteChangedExternally = false,
                        showConflictDialog = false,
                        canUndo = false,
                        canRedo = false,
                        searchMatches = matches,
                        activeMatchIndex = if (matches.isNotEmpty()) 0 else -1,
                    )
                }
                if (emitReloadEvent) {
                    _events.tryEmit(CodeEditorEvent.ReloadedFromServer)
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = err.message ?: "Не удалось открыть файл",
                    )
                }
            }
        }
    }

    fun onTextValueChange(newValue: TextFieldValue) {
        val prev = _uiState.value.textValue
        if (newValue.text != prev.text) {
            pushUndo(prev)
            redoStack.clear()
        }
        _uiState.update { state ->
            val matches = if (state.findVisible && state.findQuery.isNotEmpty()) {
                computeSearchMatches(newValue.text, state.findQuery, state.matchCase)
            } else {
                emptyList()
            }
            val nextIdx = if (matches.isEmpty()) -1 else state.activeMatchIndex.coerceIn(0, matches.lastIndex)
            state.copy(
                textValue = newValue,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                searchMatches = matches,
                activeMatchIndex = nextIdx,
            )
        }
    }

    fun insertSymbol(symbol: String) {
        val current = _uiState.value.textValue
        val text = current.text
        val start = current.selection.min.coerceIn(0, text.length)
        val end = current.selection.max.coerceIn(0, text.length)
        val updatedText = text.substring(0, start) + symbol + text.substring(end)
        val newCursor = start + symbol.length
        onTextValueChange(
            TextFieldValue(
                text = updatedText,
                selection = TextRange(newCursor),
            ),
        )
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        val current = _uiState.value.textValue
        redoStack.addLast(current)
        _uiState.update { state ->
            val matches = computeSearchMatches(previous.text, state.findQuery, state.matchCase)
            state.copy(
                textValue = previous,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                searchMatches = matches,
                activeMatchIndex = if (matches.isEmpty()) -1 else 0,
            )
        }
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        val current = _uiState.value.textValue
        undoStack.addLast(current)
        _uiState.update { state ->
            val matches = computeSearchMatches(next.text, state.findQuery, state.matchCase)
            state.copy(
                textValue = next,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                searchMatches = matches,
                activeMatchIndex = if (matches.isEmpty()) -1 else 0,
            )
        }
    }

    fun toggleFindBar() {
        _uiState.update { state ->
            val nextVisible = !state.findVisible
            val matches = if (nextVisible) {
                computeSearchMatches(state.textValue.text, state.findQuery, state.matchCase)
            } else {
                emptyList()
            }
            state.copy(
                findVisible = nextVisible,
                searchMatches = matches,
                activeMatchIndex = if (matches.isNotEmpty()) 0 else -1,
            )
        }
    }

    fun onFindQueryChange(query: String) {
        _uiState.update { state ->
            val matches = computeSearchMatches(state.textValue.text, query, state.matchCase)
            val activeIdx = if (matches.isNotEmpty()) 0 else -1
            val updatedValue = if (activeIdx >= 0) {
                val r = matches[activeIdx]
                state.textValue.copy(selection = TextRange(r.first, r.last + 1))
            } else {
                state.textValue
            }
            state.copy(
                findQuery = query,
                searchMatches = matches,
                activeMatchIndex = activeIdx,
                textValue = updatedValue,
            )
        }
    }

    fun onReplaceQueryChange(query: String) {
        _uiState.update { it.copy(replaceQuery = query) }
    }

    fun toggleMatchCase() {
        _uiState.update { state ->
            val nextCase = !state.matchCase
            val matches = computeSearchMatches(state.textValue.text, state.findQuery, nextCase)
            state.copy(
                matchCase = nextCase,
                searchMatches = matches,
                activeMatchIndex = if (matches.isNotEmpty()) 0 else -1,
            )
        }
    }

    fun nextMatch() {
        val state = _uiState.value
        if (state.searchMatches.isEmpty()) return
        val nextIdx = (state.activeMatchIndex + 1) % state.searchMatches.size
        jumpToMatch(nextIdx)
    }

    fun previousMatch() {
        val state = _uiState.value
        if (state.searchMatches.isEmpty()) return
        val prevIdx = if (state.activeMatchIndex <= 0) state.searchMatches.lastIndex else state.activeMatchIndex - 1
        jumpToMatch(prevIdx)
    }

    fun replaceCurrent() {
        val state = _uiState.value
        val idx = state.activeMatchIndex
        if (idx !in state.searchMatches.indices) return
        val range = state.searchMatches[idx]
        val text = state.textValue.text
        val replaced = text.substring(0, range.first) + state.replaceQuery + text.substring(range.last + 1)
        val newCursor = range.first + state.replaceQuery.length
        onTextValueChange(TextFieldValue(replaced, TextRange(newCursor)))
    }

    fun replaceAll() {
        val state = _uiState.value
        if (state.findQuery.isEmpty() || state.searchMatches.isEmpty()) return
        val replaced = buildString {
            var lastEnd = 0
            for (range in state.searchMatches) {
                append(state.textValue.text, lastEnd, range.first)
                append(state.replaceQuery)
                lastEnd = range.last + 1
            }
            append(state.textValue.text, lastEnd, state.textValue.text.length)
        }
        onTextValueChange(TextFieldValue(replaced, TextRange(0)))
    }

    fun toggleWordWrap() {
        viewModelScope.launch {
            preferencesRepository.setEditorWordWrap(!_uiState.value.wordWrap)
        }
    }

    fun adjustFontSize(deltaSp: Float) {
        viewModelScope.launch {
            preferencesRepository.setEditorFontSizeSp(_uiState.value.fontSizeSp + deltaSp)
        }
    }

    fun dismissRemoteChangedBanner() {
        _uiState.update { it.copy(remoteChangedExternally = false) }
    }

    fun dismissConflictDialog() {
        _uiState.update { it.copy(showConflictDialog = false) }
    }

    fun save(forceOverwrite: Boolean = false, closeAfterSave: Boolean = false) {
        val state = _uiState.value
        if (state.isSaving || state.isLoading) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, showConflictDialog = false) }
            val result = runCatching {
                sftpRepository.writeTextFile(
                    path = remotePath,
                    text = state.textValue.text,
                    expectedMtimeEpochSec = state.lastSyncedMtimeEpochSec,
                    forceOverwrite = forceOverwrite,
                )
            }.getOrElse { err ->
                SaveFileResult.Error(err.message ?: "Ошибка сохранения файла")
            }

            when (result) {
                is SaveFileResult.Saved -> {
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            originalText = state.textValue.text,
                            lastSyncedMtimeEpochSec = result.modifiedAtEpochSec,
                            remoteChangedExternally = false,
                        )
                    }
                    _events.tryEmit(CodeEditorEvent.Saved)
                    if (closeAfterSave) {
                        _events.tryEmit(CodeEditorEvent.CloseRequested)
                    }
                }

                is SaveFileResult.Conflict -> {
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            showConflictDialog = true,
                        )
                    }
                }

                is SaveFileResult.Error -> {
                    _uiState.update { it.copy(isSaving = false) }
                    _events.tryEmit(CodeEditorEvent.ErrorToast(result.message))
                }
            }
        }
    }

    private fun startRemoteChangeWatcher() {
        viewModelScope.launch {
            while (isActive) {
                delay(REMOTE_POLL_INTERVAL_MS)
                val state = _uiState.value
                val knownMtime = state.lastSyncedMtimeEpochSec
                if (state.isLoading || state.isSaving || knownMtime == null) continue

                val remoteStat = runCatching { sftpRepository.stat(remotePath) }.getOrNull() ?: continue
                if (remoteStat.modifiedAtEpochSec != knownMtime) {
                    if (!state.isDirty) {
                        loadFile(emitReloadEvent = true)
                    } else {
                        _uiState.update { it.copy(remoteChangedExternally = true) }
                    }
                }
            }
        }
    }

    private fun jumpToMatch(index: Int) {
        _uiState.update { state ->
            val range = state.searchMatches.getOrNull(index) ?: return@update state
            state.copy(
                activeMatchIndex = index,
                textValue = state.textValue.copy(selection = TextRange(range.first, range.last + 1)),
            )
        }
    }

    private fun pushUndo(value: TextFieldValue) {
        if (undoStack.size >= MAX_UNDO_HISTORY) {
            undoStack.removeFirst()
        }
        undoStack.addLast(value)
    }

    private fun computeSearchMatches(text: String, query: String, matchCase: Boolean): List<IntRange> {
        if (query.isEmpty()) return emptyList()
        val matches = mutableListOf<IntRange>()
        var startIndex = 0
        while (startIndex <= text.length - query.length) {
            val found = text.indexOf(query, startIndex = startIndex, ignoreCase = !matchCase)
            if (found < 0) break
            matches.add(found until (found + query.length))
            startIndex = found + query.length
            if (matches.size >= 500) break
        }
        return matches
    }

    private fun computeOffsetForLine(text: String, targetLine: Int): Int {
        if (targetLine <= 1) return 0
        var line = 1
        for (i in text.indices) {
            if (text[i] == '\n') {
                line++
                if (line == targetLine) return (i + 1).coerceAtMost(text.length)
            }
        }
        return 0
    }

    companion object {
        private const val MAX_UNDO_HISTORY = 100
        private const val REMOTE_POLL_INTERVAL_MS = 5_000L
    }

    class Factory(
        private val remotePath: String,
        private val initialLine: Int?,
        private val sftpRepository: SftpRepository,
        private val preferencesRepository: AppPreferencesRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CodeEditorViewModel(
                remotePath = remotePath,
                initialLine = initialLine,
                sftpRepository = sftpRepository,
                preferencesRepository = preferencesRepository,
            ) as T
        }
    }
}
