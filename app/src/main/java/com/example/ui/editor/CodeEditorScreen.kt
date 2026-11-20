package com.example.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.WrapText
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.MonoFontFamily

private val SPECIAL_SYMBOLS = listOf(
    "Tab" to "    ",
    "{" to "{",
    "}" to "}",
    "[" to "[",
    "]" to "]",
    "(" to "(",
    ")" to ")",
    "<" to "<",
    ">" to ">",
    ";" to ";",
    ":" to ":",
    "\"" to "\"",
    "'" to "'",
    "/" to "/",
    "\\" to "\\",
    "|" to "|",
    "=" to "=",
    "_" to "_",
    "-" to "-",
    "$" to "$",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeEditorScreen(
    viewModel: CodeEditorViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }

    val requestExit = {
        if (uiState.isDirty) {
            showUnsavedDialog = true
        } else {
            onNavigateBack()
        }
    }

    BackHandler(onBack = requestExit)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                CodeEditorEvent.Saved ->
                    snackbarHostState.showSnackbar(context.getString(R.string.editor_code_saved))
                CodeEditorEvent.ReloadedFromServer ->
                    snackbarHostState.showSnackbar(context.getString(R.string.editor_code_reloaded))
                CodeEditorEvent.CloseRequested -> onNavigateBack()
                is CodeEditorEvent.ErrorToast ->
                    snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    val isDarkSurface = MaterialTheme.colorScheme.background.luminance() < 0.5f

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (uiState.isDirty) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(LocalStatusColors.current.warning),
                                )
                            }
                            Text(
                                text = uiState.fileName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = "${uiState.language.label} • ${stringResource(R.string.editor_code_lines_count, uiState.lineCount)}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = requestExit,
                        modifier = Modifier.testTag("code_editor_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::undo,
                        enabled = uiState.canUndo,
                        modifier = Modifier.testTag("code_editor_undo_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Undo,
                            contentDescription = stringResource(R.string.editor_code_undo),
                        )
                    }
                    IconButton(
                        onClick = viewModel::redo,
                        enabled = uiState.canRedo,
                        modifier = Modifier.testTag("code_editor_redo_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Redo,
                            contentDescription = stringResource(R.string.editor_code_redo),
                        )
                    }
                    IconButton(
                        onClick = viewModel::toggleFindBar,
                        modifier = Modifier.testTag("code_editor_find_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = stringResource(R.string.editor_code_find),
                        )
                    }
                    IconButton(
                        onClick = { viewModel.save() },
                        enabled = !uiState.isSaving && !uiState.isLoading && uiState.isDirty,
                        modifier = Modifier.testTag("code_editor_save_button"),
                    ) {
                        if (uiState.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Save,
                                contentDescription = stringResource(R.string.editor_code_save),
                                tint = if (uiState.isDirty) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                },
                            )
                        }
                    }
                    Box {
                        IconButton(
                            onClick = { showOverflowMenu = true },
                            modifier = Modifier.testTag("code_editor_menu_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = null,
                            )
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "${stringResource(R.string.editor_code_word_wrap)}: ${if (uiState.wordWrap) "Вкл" else "Выкл"}",
                                    )
                                },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Rounded.WrapText, contentDescription = null)
                                },
                                onClick = {
                                    viewModel.toggleWordWrap()
                                    showOverflowMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.editor_code_font_larger)) },
                                leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                                onClick = { viewModel.adjustFontSize(1f) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.editor_code_font_smaller)) },
                                leadingIcon = { Icon(Icons.Rounded.Remove, contentDescription = null) },
                                onClick = { viewModel.adjustFontSize(-1f) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_refresh)) },
                                leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                                onClick = {
                                    showOverflowMenu = false
                                    viewModel.loadFile(emitReloadEvent = true)
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            SymbolAccessoryBar(
                onInsert = viewModel::insertSymbol,
                modifier = Modifier
                    .imePadding()
                    .navigationBarsPadding(),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            AnimatedVisibility(visible = uiState.remoteChangedExternally) {
                Surface(
                    color = LocalStatusColors.current.warning.copy(alpha = 0.16f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = LocalStatusColors.current.warning,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.editor_code_remote_changed_banner),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.loadFile(emitReloadEvent = true) }) {
                            Text(text = stringResource(R.string.editor_code_reload_short))
                        }
                        IconButton(
                            onClick = viewModel::dismissRemoteChangedBanner,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.action_close),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = uiState.findVisible) {
                FindReplaceBar(
                    findQuery = uiState.findQuery,
                    replaceQuery = uiState.replaceQuery,
                    matchCase = uiState.matchCase,
                    matchCount = uiState.searchMatches.size,
                    activeIndex = uiState.activeMatchIndex,
                    onFindChange = viewModel::onFindQueryChange,
                    onReplaceChange = viewModel::onReplaceQueryChange,
                    onToggleMatchCase = viewModel::toggleMatchCase,
                    onPrev = viewModel::previousMatch,
                    onNext = viewModel::nextMatch,
                    onReplace = viewModel::replaceCurrent,
                    onReplaceAll = viewModel::replaceAll,
                    onClose = viewModel::toggleFindBar,
                )
            }

            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                uiState.errorMessage != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = uiState.errorMessage!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            FilledTonalButton(onClick = { viewModel.loadFile() }) {
                                Text(text = stringResource(R.string.action_refresh))
                            }
                        }
                    }
                }

                else -> {
                    CodeEditorSurface(
                        uiState = uiState,
                        darkTheme = isDarkSurface,
                        onValueChange = viewModel::onTextValueChange,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                }
            }
        }
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text(stringResource(R.string.editor_code_unsaved_title)) },
            text = { Text(stringResource(R.string.editor_code_unsaved_message, uiState.fileName)) },
            confirmButton = {
                Button(
                    onClick = {
                        showUnsavedDialog = false
                        viewModel.save(closeAfterSave = true)
                    },
                    modifier = Modifier.testTag("unsaved_save_exit_button"),
                ) {
                    Text(stringResource(R.string.editor_code_save_and_exit))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            showUnsavedDialog = false
                            onNavigateBack()
                        },
                        modifier = Modifier.testTag("unsaved_discard_button"),
                    ) {
                        Text(
                            text = stringResource(R.string.editor_code_discard),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(onClick = { showUnsavedDialog = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
        )
    }

    if (uiState.showConflictDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissConflictDialog,
            title = { Text(stringResource(R.string.editor_code_conflict_title)) },
            text = { Text(stringResource(R.string.editor_code_conflict_message)) },
            confirmButton = {
                Button(
                    onClick = { viewModel.save(forceOverwrite = true) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.testTag("conflict_overwrite_button"),
                ) {
                    Text(stringResource(R.string.editor_code_overwrite))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.loadFile(emitReloadEvent = true) },
                        modifier = Modifier.testTag("conflict_reload_button"),
                    ) {
                        Text(stringResource(R.string.editor_code_reload_server))
                    }
                    TextButton(onClick = viewModel::dismissConflictDialog) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
        )
    }
}

@Composable
private fun CodeEditorSurface(
    uiState: CodeEditorUiState,
    darkTheme: Boolean,
    onValueChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
) {
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val fontSize = uiState.fontSizeSp.sp
    val lineHeight = (uiState.fontSizeSp * 1.48f).sp

    val textStyle = TextStyle(
        fontFamily = MonoFontFamily,
        fontSize = fontSize,
        lineHeight = lineHeight,
        color = MaterialTheme.colorScheme.onBackground,
    )

    val lineNumbersText = remember(uiState.lineCount) {
        (1..uiState.lineCount).joinToString("\n")
    }

    val visualTransformation = remember(
        uiState.language,
        darkTheme,
        uiState.searchMatches,
        uiState.activeMatchIndex,
    ) {
        SyntaxVisualTransformation(
            language = uiState.language,
            darkTheme = darkTheme,
            searchRanges = uiState.searchMatches,
            activeMatchIndex = uiState.activeMatchIndex,
        )
    }

    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(verticalScroll),
    ) {
        // Line number gutter
        Text(
            text = lineNumbersText,
            style = textStyle.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.End,
            ),
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 10.dp, vertical = 12.dp)
                .widthIn(min = 34.dp),
        )

        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant),
        )

        val editorModifier = if (uiState.wordWrap) {
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 12.dp)
        } else {
            Modifier
                .weight(1f)
                .horizontalScroll(horizontalScroll)
                .padding(horizontal = 12.dp, vertical = 12.dp)
        }

        BasicTextField(
            value = uiState.textValue,
            onValueChange = onValueChange,
            textStyle = textStyle,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = visualTransformation,
            modifier = editorModifier.testTag("code_editor_text_field"),
        )
    }
}

@Composable
private fun FindReplaceBar(
    findQuery: String,
    replaceQuery: String,
    matchCase: Boolean,
    matchCount: Int,
    activeIndex: Int,
    onFindChange: (String) -> Unit,
    onReplaceChange: (String) -> Unit,
    onToggleMatchCase: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = findQuery,
                    onValueChange = onFindChange,
                    placeholder = { Text(stringResource(R.string.editor_code_find_placeholder)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("editor_find_input"),
                )
                Text(
                    text = if (matchCount == 0) "0/0" else "${activeIndex + 1}/$matchCount",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onPrev, enabled = matchCount > 0) {
                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = null)
                }
                IconButton(onClick = onNext, enabled = matchCount > 0) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close))
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = replaceQuery,
                    onValueChange = onReplaceChange,
                    placeholder = { Text(stringResource(R.string.editor_code_replace_placeholder)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("editor_replace_input"),
                )
                TextButton(
                    onClick = onToggleMatchCase,
                    modifier = Modifier.testTag("editor_match_case_button"),
                ) {
                    Text(
                        text = "Aa",
                        color = if (matchCase) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                TextButton(
                    onClick = onReplace,
                    enabled = matchCount > 0,
                    modifier = Modifier.testTag("editor_replace_one_button"),
                ) {
                    Text(stringResource(R.string.editor_code_replace))
                }
                TextButton(
                    onClick = onReplaceAll,
                    enabled = matchCount > 0,
                    modifier = Modifier.testTag("editor_replace_all_button"),
                ) {
                    Text(stringResource(R.string.editor_code_replace_all))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun SymbolAccessoryBar(
    onInsert: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.testTag("editor_symbol_bar"),
        ) {
            items(SPECIAL_SYMBOLS, key = { it.first }) { (label, value) ->
                Surface(
                    onClick = { onInsert(value) },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .height(38.dp)
                        .widthIn(min = 38.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
