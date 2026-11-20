package com.example.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.TerminalTabStatus
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.MonoFontFamily
import kotlinx.coroutines.launch

private val TerminalSurfaceColor = Color(0xFF0B1017)
private val TerminalDefaultTextColor = Color(0xFFE6EDF3)
private const val INPUT_SENTINEL = " "

@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    onOpenServers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    var showSnippetsSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.toastMessages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    if (!uiState.isConnected) {
        DisconnectedTerminalPane(
            onOpenServers = onOpenServers,
            modifier = modifier,
        )
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Tab Bar
            TerminalTabBar(
                tabs = uiState.tabs,
                activeTabId = uiState.activeTab?.id,
                onSelectTab = viewModel::selectTab,
                onCloseTab = viewModel::closeTab,
                onNewTab = viewModel::openNewTab,
            )

            // Quick Actions Bar (Snippets, Paste, Copy, Clear, Zoom, Keyboard)
            TerminalToolsBar(
                activeTab = uiState.activeTab,
                onOpenSnippets = { showSnippetsSheet = true },
                onPaste = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clipText = clipboard?.primaryClip
                        ?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)
                        ?.coerceToText(context)
                        ?.toString()
                        .orEmpty()
                    viewModel.pasteText(clipText)
                },
                onCopyOutput = {
                    val text = viewModel.getActivePlainOutput()
                    if (text.isNotBlank()) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("terminal_output", text))
                        scope.launch {
                            snackbarHostState.showSnackbar(context.getString(R.string.terminal_copied_toast))
                        }
                    }
                },
                onClear = viewModel::clearActiveScreen,
                onReconnect = viewModel::reconnectActiveTab,
                onFontSmaller = { viewModel.adjustFontSize(-1f) },
                onFontLarger = { viewModel.adjustFontSize(+1f) },
                onToggleKeyboard = {
                    runCatching { focusRequester.requestFocus() }
                    keyboardController?.show()
                },
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Hidden IME bridge input
            TerminalImeInputBridge(
                focusRequester = focusRequester,
                onTypedText = viewModel::sendTypedText,
                onBackspace = { viewModel.sendSpecialKey("\u007F") },
                onEnter = { viewModel.sendSpecialKey("\r") },
                onEscape = { viewModel.sendSpecialKey("\u001B") },
                onTab = { viewModel.sendSpecialKey("\t") },
                onArrow = viewModel::sendSpecialKey,
            )

            // Terminal ANSI Viewport
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(TerminalSurfaceColor),
            ) {
                val activeTab = uiState.activeTab
                if (activeTab == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Button(
                            onClick = viewModel::openNewTab,
                            modifier = Modifier.testTag("terminal_empty_new_tab_button"),
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.terminal_new_tab))
                        }
                    }
                } else {
                    TerminalViewport(
                        tab = activeTab,
                        fontSizeSp = uiState.fontSizeSp,
                        onViewportResized = viewModel::onViewportResized,
                        onTapToFocus = {
                            runCatching { focusRequester.requestFocus() }
                            keyboardController?.show()
                        },
                        onReconnect = viewModel::reconnectActiveTab,
                    )
                }
            }

            // Accessory Special Keys Bar (Ctrl / Alt / Esc / Tab / Arrows / Home / End)
            TerminalAccessoryKeyBar(
                ctrlArmed = uiState.ctrlArmed,
                altArmed = uiState.altArmed,
                onToggleCtrl = viewModel::toggleCtrl,
                onToggleAlt = viewModel::toggleAlt,
                onSendSpecial = viewModel::sendSpecialKey,
                onSendCtrlChar = viewModel::sendControlChar,
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 52.dp),
        )
    }

    if (showSnippetsSheet) {
        TerminalSnippetsSheet(
            snippets = uiState.snippets,
            onRunSnippet = viewModel::runSnippet,
            onSaveSnippet = viewModel::saveSnippet,
            onDeleteSnippet = viewModel::deleteSnippet,
            onDismiss = { showSnippetsSheet = false },
        )
    }

    uiState.pendingDangerousCommand?.let { pending ->
        DangerousCommandDialog(
            command = pending.command,
            onConfirm = viewModel::confirmDangerousCommand,
            onDismiss = viewModel::dismissDangerousCommand,
        )
    }
}

@Composable
private fun DisconnectedTerminalPane(
    onOpenServers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(44.dp),
                )
                Text(
                    text = stringResource(R.string.terminal_not_connected_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.terminal_not_connected_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onOpenServers,
                    modifier = Modifier.testTag("terminal_choose_server_button"),
                ) {
                    Icon(Icons.Rounded.Dns, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.workspace_choose_server))
                }
            }
        }
    }
}

@Composable
private fun TerminalTabBar(
    tabs: List<TerminalTabItem>,
    activeTabId: String?,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: () -> Unit,
) {
    val statusColors = LocalStatusColors.current

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(tabs, key = { it.id }) { tab ->
                    val selected = tab.id == activeTabId
                    val dotColor = when (tab.status) {
                        TerminalTabStatus.CONNECTED -> statusColors.connected
                        TerminalTabStatus.CONNECTING -> statusColors.warning
                        TerminalTabStatus.CLOSED -> MaterialTheme.colorScheme.onSurfaceVariant
                        TerminalTabStatus.ERROR -> MaterialTheme.colorScheme.error
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelectTab(tab.id) },
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(dotColor),
                            )
                            Text(
                                text = tab.title,
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(
                                onClick = { onCloseTab(tab.id) },
                                modifier = Modifier.size(22.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.terminal_close_tab),
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
            }

            FilledTonalIconButton(
                onClick = onNewTab,
                modifier = Modifier
                    .size(34.dp)
                    .testTag("terminal_new_tab_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.terminal_new_tab),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun TerminalToolsBar(
    activeTab: TerminalTabItem?,
    onOpenSnippets: () -> Unit,
    onPaste: () -> Unit,
    onCopyOutput: () -> Unit,
    onClear: () -> Unit,
    onReconnect: () -> Unit,
    onFontSmaller: () -> Unit,
    onFontLarger: () -> Unit,
    onToggleKeyboard: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconButton(
            onClick = onOpenSnippets,
            modifier = Modifier.testTag("terminal_snippets_button"),
        ) {
            Icon(
                imageVector = Icons.Rounded.Bolt,
                contentDescription = stringResource(R.string.terminal_snippets_title),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(
            onClick = onPaste,
            modifier = Modifier.testTag("terminal_paste_button"),
        ) {
            Icon(
                imageVector = Icons.Rounded.ContentPaste,
                contentDescription = stringResource(R.string.terminal_paste),
            )
        }
        IconButton(
            onClick = onCopyOutput,
            modifier = Modifier.testTag("terminal_copy_button"),
        ) {
            Icon(
                imageVector = Icons.Rounded.ContentCopy,
                contentDescription = stringResource(R.string.terminal_copy_output),
            )
        }
        IconButton(onClick = onClear) {
            Icon(
                imageVector = Icons.Rounded.ClearAll,
                contentDescription = stringResource(R.string.terminal_clear_screen),
            )
        }
        IconButton(onClick = onFontSmaller) {
            Icon(
                imageVector = Icons.Rounded.TextDecrease,
                contentDescription = stringResource(R.string.terminal_font_smaller),
            )
        }
        IconButton(onClick = onFontLarger) {
            Icon(
                imageVector = Icons.Rounded.TextIncrease,
                contentDescription = stringResource(R.string.terminal_font_larger),
            )
        }
        IconButton(onClick = onToggleKeyboard) {
            Icon(
                imageVector = Icons.Rounded.Keyboard,
                contentDescription = stringResource(R.string.terminal_keyboard_toggle),
            )
        }
        if (activeTab?.status == TerminalTabStatus.CLOSED || activeTab?.status == TerminalTabStatus.ERROR) {
            IconButton(onClick = onReconnect) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.terminal_reconnect_tab),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun TerminalViewport(
    tab: TerminalTabItem,
    fontSizeSp: Float,
    onViewportResized: (cols: Int, rows: Int) -> Unit,
    onTapToFocus: () -> Unit,
    onReconnect: () -> Unit,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val lineHeightSp = (fontSizeSp * 1.32f)
    val monoStyle = remember(fontSizeSp) {
        TextStyle(
            fontFamily = MonoFontFamily,
            fontSize = fontSizeSp.sp,
            lineHeight = lineHeightSp.sp,
            color = TerminalDefaultTextColor,
        )
    }

    val lines = tab.snapshot.lines
    val isNearBottom by remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total == 0 || lastVisible >= total - 3
        }
    }

    LaunchedEffect(tab.snapshot.revision, tab.id) {
        if (lines.isNotEmpty() && isNearBottom) {
            listState.scrollToItem(lines.lastIndex)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTapToFocus,
            ),
    ) {
        val horizontalPaddingPx = with(density) { 16.dp.toPx() }
        val verticalPaddingPx = with(density) { 12.dp.toPx() }
        val availableWidthPx = (constraints.maxWidth - horizontalPaddingPx).coerceAtLeast(100f)
        val availableHeightPx = (constraints.maxHeight - verticalPaddingPx).coerceAtLeast(100f)

        val charMetrics = remember(monoStyle) {
            textMeasurer.measure("MMMMMMMMMM", style = monoStyle)
        }
        val charWidthPx = (charMetrics.size.width / 10f).coerceAtLeast(6f)
        val lineHeightPx = charMetrics.size.height.toFloat().coerceAtLeast(12f)

        val measuredCols = (availableWidthPx / charWidthPx).toInt().coerceIn(32, 220)
        val measuredRows = (availableHeightPx / lineHeightPx).toInt().coerceIn(10, 100)

        LaunchedEffect(measuredCols, measuredRows) {
            onViewportResized(measuredCols, measuredRows)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("terminal_viewport"),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        ) {
            itemsIndexed(lines) { _, line ->
                Text(
                    text = if (line.isEmpty()) androidx.compose.ui.text.AnnotatedString(" ") else line,
                    style = monoStyle,
                    softWrap = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (tab.status == TerminalTabStatus.CONNECTING) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xCC111827))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.terminal_status_connecting),
                    style = MaterialTheme.typography.labelSmall,
                    color = TerminalDefaultTextColor,
                )
            }
        }

        if (tab.status == TerminalTabStatus.CLOSED || tab.status == TerminalTabStatus.ERROR) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xEE18202B),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = tab.errorMessage ?: stringResource(R.string.terminal_status_closed),
                        style = MaterialTheme.typography.labelSmall,
                        color = TerminalDefaultTextColor,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    TextButton(onClick = onReconnect) {
                        Text(stringResource(R.string.terminal_reconnect_tab))
                    }
                }
            }
        }

        if (!isNearBottom && lines.isNotEmpty()) {
            SmallFloatingActionButton(
                onClick = {
                    scope.launch { listState.animateScrollToItem(lines.lastIndex) }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.terminal_scroll_bottom),
                )
            }
        }
    }
}

@Composable
private fun TerminalImeInputBridge(
    focusRequester: FocusRequester,
    onTypedText: (String) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onEscape: () -> Unit,
    onTab: () -> Unit,
    onArrow: (String) -> Unit,
) {
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(INPUT_SENTINEL, TextRange(INPUT_SENTINEL.length)))
    }

    BasicTextField(
        value = fieldValue,
        onValueChange = { incoming ->
            val txt = incoming.text
            when {
                txt.isEmpty() -> {
                    onBackspace()
                }

                txt.length > INPUT_SENTINEL.length -> {
                    val inserted = if (txt.startsWith(INPUT_SENTINEL)) {
                        txt.removePrefix(INPUT_SENTINEL)
                    } else {
                        txt
                    }
                    if (inserted.isNotEmpty()) {
                        onTypedText(inserted)
                    }
                }

                txt != INPUT_SENTINEL -> {
                    onTypedText(txt)
                }
            }
            fieldValue = TextFieldValue(INPUT_SENTINEL, TextRange(INPUT_SENTINEL.length))
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Send,
        ),
        keyboardActions = KeyboardActions(
            onSend = { onEnter() },
            onDone = { onEnter() },
        ),
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (keyEvent.key) {
                    Key.Enter -> {
                        onEnter(); true
                    }
                    Key.Backspace -> {
                        onBackspace(); true
                    }
                    Key.Escape -> {
                        onEscape(); true
                    }
                    Key.Tab -> {
                        onTab(); true
                    }
                    Key.DirectionUp -> {
                        onArrow("\u001B[A"); true
                    }
                    Key.DirectionDown -> {
                        onArrow("\u001B[B"); true
                    }
                    Key.DirectionRight -> {
                        onArrow("\u001B[C"); true
                    }
                    Key.DirectionLeft -> {
                        onArrow("\u001B[D"); true
                    }
                    else -> false
                }
            }
            .testTag("terminal_ime_input"),
    )
}

@Composable
private fun TerminalAccessoryKeyBar(
    ctrlArmed: Boolean,
    altArmed: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onSendSpecial: (String) -> Unit,
    onSendCtrlChar: (Char) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TerminalKeyChip(label = "ESC", onClick = { onSendSpecial("\u001B") })
            TerminalKeyChip(label = "TAB", onClick = { onSendSpecial("\t") })
            TerminalKeyChip(
                label = "CTRL",
                active = ctrlArmed,
                onClick = onToggleCtrl,
                tag = "terminal_key_ctrl",
            )
            TerminalKeyChip(
                label = "ALT",
                active = altArmed,
                onClick = onToggleAlt,
                tag = "terminal_key_alt",
            )
            TerminalKeyChip(label = "^C", onClick = { onSendCtrlChar('C') })
            TerminalKeyChip(label = "^D", onClick = { onSendCtrlChar('D') })
            TerminalKeyChip(label = "^L", onClick = { onSendCtrlChar('L') })
            TerminalKeyChip(label = "←", onClick = { onSendSpecial("\u001B[D") })
            TerminalKeyChip(label = "↓", onClick = { onSendSpecial("\u001B[B") })
            TerminalKeyChip(label = "↑", onClick = { onSendSpecial("\u001B[A") })
            TerminalKeyChip(label = "→", onClick = { onSendSpecial("\u001B[C") })
            TerminalKeyChip(label = "HOME", onClick = { onSendSpecial("\u001B[H") })
            TerminalKeyChip(label = "END", onClick = { onSendSpecial("\u001B[F") })
            TerminalKeyChip(label = "PGUP", onClick = { onSendSpecial("\u001B[5~") })
            TerminalKeyChip(label = "PGDN", onClick = { onSendSpecial("\u001B[6~") })
        }
    }
}

@Composable
private fun TerminalKeyChip(
    label: String,
    active: Boolean = false,
    tag: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .let { if (tag != null) it.testTag(tag) else it },
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = MonoFontFamily,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun DangerousCommandDialog(
    command: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(stringResource(R.string.terminal_dangerous_cmd_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.terminal_dangerous_cmd_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = command.take(600),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .padding(10.dp),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.testTag("dangerous_cmd_confirm_button"),
            ) {
                Text(stringResource(R.string.terminal_dangerous_cmd_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
