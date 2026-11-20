package com.example.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Difference
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.AttachedFileRef
import com.example.domain.model.ModelPricing
import com.example.domain.model.OpenCodeMessage
import com.example.domain.model.OpenCodePart
import com.example.domain.model.OpenCodeServerStatus
import com.example.domain.model.OpenCodeSession
import com.example.domain.model.SessionContextUsage
import com.example.domain.model.SetupWizardStage
import com.example.domain.model.SseConnectionStatus
import com.example.domain.model.UsageCalculator
import com.example.ui.components.StatusChip
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.MonoFontFamily
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenServers: () -> Unit,
    onOpenCodeEditor: (remotePath: String, line: Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showModelSheet by remember { mutableStateOf(false) }
    var showAgentSheet by remember { mutableStateOf(false) }
    var showFilePickerSheet by remember { mutableStateOf(false) }
    var showWorkspaceSheet by remember { mutableStateOf(false) }
    var showDiffSheet by remember { mutableStateOf(false) }
    var focusedDiffFile by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<OpenCodeSession?>(null) }
    var deleteTarget by remember { mutableStateOf<OpenCodeSession?>(null) }
    var revertTargetMessageId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.toastMessages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(uiState.isConnected) {
        if (uiState.isConnected) {
            viewModel.onChatTabVisible()
        }
    }

    if (!uiState.isConnected && !uiState.isReconnecting) {
        DisconnectedChatPane(
            onOpenServers = onOpenServers,
            modifier = modifier,
        )
        return
    }

    val showSetupView = uiState.isConnected && (
        uiState.setup.serverStatus != OpenCodeServerStatus.RUNNING ||
            uiState.setup.wizardStage != SetupWizardStage.IDLE
        )

    if (showSetupView) {
        if (uiState.setup.wizardStage != SetupWizardStage.IDLE && !uiState.setup.isRunningAction) {
            BackHandler { viewModel.cancelSetupWizard() }
        }
        Box(modifier = modifier.fillMaxSize()) {
            OpenCodeSetupView(
                setupState = uiState.setup,
                openCodePort = uiState.openCodePort,
                onRecheck = { viewModel.checkOpenCodeServer(silentIfRunning = false) },
                onOpenInstallPreview = { viewModel.openInstallPreview(forceUpdateBinary = false) },
                onOpenUpdatePreview = { viewModel.openInstallPreview(forceUpdateBinary = true) },
                onTogglePasswordAuth = viewModel::setEnablePasswordAuth,
                onToggleVerifyPersistence = viewModel::setVerifyPersistenceOnRestart,
                onConfirmInstall = { viewModel.confirmAndRunInstall(0) },
                onCancelWizard = viewModel::cancelSetupWizard,
                onRetryFailedStep = viewModel::retryFailedStep,
                onStartServer = viewModel::startStoppedServer,
                onRefreshConfiguredProviders = viewModel::loadConfiguredProviders,
                onStartNewProvider = viewModel::startNewProviderEditor,
                onEditConfiguredProvider = viewModel::startEditProvider,
                onChangeProviderKey = viewModel::changeProviderApiKey,
                onDeleteConfiguredProvider = viewModel::deleteConfiguredProvider,
                onSelectCustomProvider = viewModel::onSelectCustomProvider,
                onProviderNameChange = viewModel::onProviderNameChange,
                onBaseUrlChange = viewModel::onBaseUrlChange,
                onApiKeyChange = viewModel::onApiKeyChange,
                onFetchModels = viewModel::fetchModelsFromProvider,
                onModelSearchChange = viewModel::onModelSearchQueryChange,
                onToggleModelSelection = viewModel::toggleModelSelection,
                onUpdateModelDisplayName = viewModel::updateModelDisplayName,
                onUpdateModelMultiplier = viewModel::updateModelMultiplier,
                onUpdateModelCacheMultiplier = viewModel::updateModelCacheMultiplier,
                onUpdateModelInputPrice = viewModel::updateModelInputPrice,
                onUpdateModelOutputPrice = viewModel::updateModelOutputPrice,
                onSelectDefaultModel = viewModel::selectDefaultModelInEditor,
                onManualModelIdChange = viewModel::onManualModelIdInputChange,
                onAddManualModel = viewModel::addManualModel,
                onTestSelectedModel = viewModel::testSelectedModel,
                onSaveCustomProvider = viewModel::saveCustomProviderAndFinish,
                onRetryProviderCheck = viewModel::retryProviderHealthCheck,
                onContinueAfterProviderWarning = viewModel::continueAfterProviderWarning,
                onFinishWithFreeModels = viewModel::finishWithFreeModels,
                onLogCopied = {
                    scope.launch {
                        snackbarHostState.showSnackbar(context.getString(R.string.setup_log_copied))
                    }
                },
            )
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (uiState.isReconnecting) {
                Surface(
                    color = LocalStatusColors.current.warning.copy(alpha = 0.16f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("chat_reconnecting_banner"),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = LocalStatusColors.current.warning,
                        )
                        Text(
                            text = stringResource(R.string.reconnect_banner_chat),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            WorkspaceHeaderBar(
                workspacePath = uiState.activeWorkspacePath,
                onClick = {
                    viewModel.openWorkspacePicker()
                    showWorkspaceSheet = true
                },
            )

            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val isDualPane = maxWidth >= 840.dp

                if (!isDualPane && uiState.activeSessionId != null) {
                    BackHandler { viewModel.closeActiveSession() }
                }

                Row(modifier = Modifier.fillMaxSize()) {
                if (isDualPane || uiState.activeSessionId == null) {
                    SessionsListPane(
                        uiState = uiState,
                        onCreateSession = { viewModel.createSession() },
                        onRefresh = viewModel::refreshAll,
                        onOpenSession = viewModel::openSession,
                        onRenameSession = { renameTarget = it },
                        onForkSession = { viewModel.forkSession(it.id) },
                        onDeleteSession = { deleteTarget = it },
                        modifier = if (isDualPane) {
                            Modifier
                                .width(310.dp)
                                .fillMaxHeight()
                        } else {
                            Modifier.fillMaxSize()
                        },
                    )
                }

                if (isDualPane) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }

                if (isDualPane || uiState.activeSessionId != null) {
                    ActiveSessionPane(
                        uiState = uiState,
                        showBackButton = !isDualPane,
                        onBack = viewModel::closeActiveSession,
                        onRefreshMessages = {
                            uiState.activeSessionId?.let { id ->
                                viewModel.loadMessages(id)
                                viewModel.loadSessionDiffs(id)
                            }
                        },
                        onOpenDiffSheet = { focusFile ->
                            focusedDiffFile = focusFile
                            showDiffSheet = true
                            viewModel.loadSessionDiffs()
                        },
                        onForkSession = {
                            uiState.activeSessionId?.let { viewModel.forkSession(it) }
                        },
                        onRevertMessage = { revertTargetMessageId = it },
                        onOpenFile = { path -> onOpenCodeEditor(path, null) },
                        onCopyMessage = { msg ->
                            val text = ChatTimeFormatter.buildCopyableMessageText(msg)
                            if (text.isNotBlank()) {
                                copyPlainTextToClipboard(context, "Message", text)
                                scope.launch {
                                    snackbarHostState.showSnackbar(context.getString(R.string.chat_message_copied))
                                }
                            }
                        },
                        onCodeCopied = {
                            scope.launch {
                                snackbarHostState.showSnackbar(context.getString(R.string.chat_code_copied))
                            }
                        },
                        onWaitMore = viewModel::waitMoreForResponse,
                        onSwitchModelAndRetry = {
                            viewModel.requestSwitchModelAndRetry()
                            showModelSheet = true
                        },
                        onCopyDiagnostics = {
                            val report = viewModel.buildDiagnosticsReport()
                            copyPlainTextToClipboard(context, "PocketPilot Diagnostics", report)
                            scope.launch {
                                snackbarHostState.showSnackbar(context.getString(R.string.chat_diagnostics_copied))
                            }
                        },
                        onComposerChange = { next ->
                            val justTypedAt = next.endsWith("@") && !uiState.composerText.endsWith("@")
                            viewModel.onComposerTextChange(next)
                            if (justTypedAt) {
                                viewModel.openFilePicker()
                                showFilePickerSheet = true
                            }
                        },
                        onAttachClick = {
                            viewModel.openFilePicker()
                            showFilePickerSheet = true
                        },
                        onRemoveAttachment = viewModel::removeAttachedFile,
                        onOpenAgentPicker = { showAgentSheet = true },
                        onOpenModelPicker = {
                            viewModel.cancelPendingSwitchModelAndRetry()
                            showModelSheet = true
                        },
                        onSend = viewModel::sendMessage,
                        onStop = viewModel::abortActiveSession,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (showModelSheet) {
        ModelPickerBottomSheet(
            providers = uiState.providers,
            selectedProviderId = uiState.selectedProviderId,
            selectedModelId = uiState.selectedModelId,
            onSelect = viewModel::selectModel,
            onDismiss = {
                showModelSheet = false
                viewModel.cancelPendingSwitchModelAndRetry()
            },
        )
    }

    if (showAgentSheet) {
        AgentPickerBottomSheet(
            agents = uiState.agents,
            selectedAgentName = uiState.selectedAgentName,
            onSelect = viewModel::selectAgent,
            onDismiss = { showAgentSheet = false },
        )
    }

    if (showFilePickerSheet) {
        ServerFilePickerBottomSheet(
            currentPath = uiState.pickerPath,
            entries = uiState.pickerEntries,
            isLoading = uiState.isPickerLoading,
            onNavigate = viewModel::loadPickerDirectory,
            onAttachFile = { entry ->
                viewModel.attachFile(entry.path, entry.name)
            },
            onDismiss = { showFilePickerSheet = false },
        )
    }

    if (showWorkspaceSheet) {
        WorkspacePickerBottomSheet(
            currentPath = uiState.workspacePickerPath,
            activeWorkspacePath = uiState.activeWorkspacePath,
            recentWorkspaces = uiState.recentWorkspaces,
            folders = uiState.workspacePickerFolders,
            isLoading = uiState.isWorkspacePickerLoading,
            isCreatingFolder = uiState.isCreatingWorkspaceFolder,
            errorMessage = uiState.workspacePickerError,
            onNavigate = viewModel::loadWorkspacePickerDirectory,
            onCreateFolder = viewModel::createWorkspaceFolder,
            onSelectWorkspace = viewModel::selectWorkspace,
            onDismiss = { showWorkspaceSheet = false },
        )
    }

    renameTarget?.let { session ->
        RenameSessionDialog(
            initialTitle = session.title,
            onConfirm = { newTitle ->
                renameTarget = null
                viewModel.renameSession(session.id, newTitle)
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { session ->
        DeleteSessionDialog(
            sessionTitle = session.title.ifBlank { stringResource(R.string.chat_session_untitled) },
            onConfirm = {
                deleteTarget = null
                viewModel.deleteSession(session.id)
            },
            onDismiss = { deleteTarget = null },
        )
    }

    if (showDiffSheet) {
        SessionDiffSheet(
            diffs = uiState.sessionDiffs,
            isLoading = uiState.isLoadingDiffs,
            focusedFilePath = focusedDiffFile,
            onRefresh = { viewModel.loadSessionDiffs() },
            onOpenInEditor = { path ->
                showDiffSheet = false
                onOpenCodeEditor(path, null)
            },
            onDismiss = {
                showDiffSheet = false
                focusedDiffFile = null
            },
        )
    }

    revertTargetMessageId?.let { msgId ->
        RevertSessionDialog(
            onConfirm = {
                revertTargetMessageId = null
                viewModel.revertToMessage(msgId)
            },
            onDismiss = { revertTargetMessageId = null },
        )
    }
}

@Composable
private fun DisconnectedChatPane(
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
                    imageVector = Icons.Rounded.ChatBubbleOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(44.dp),
                )
                Text(
                    text = stringResource(R.string.chat_not_connected_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.chat_not_connected_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onOpenServers,
                    modifier = Modifier.testTag("chat_choose_server_button"),
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
private fun SessionsListPane(
    uiState: ChatUiState,
    onCreateSession: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSession: (String) -> Unit,
    onRenameSession: (OpenCodeSession) -> Unit,
    onForkSession: (OpenCodeSession) -> Unit,
    onDeleteSession: (OpenCodeSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusColors = LocalStatusColors.current

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.chat_sessions_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                val (sseLabel, sseColor) = when (uiState.sseStatus) {
                    SseConnectionStatus.CONNECTED -> stringResource(R.string.chat_sse_connected) to statusColors.connected
                    SseConnectionStatus.CONNECTING,
                    SseConnectionStatus.RECONNECTING -> stringResource(R.string.chat_sse_reconnecting) to statusColors.warning
                    SseConnectionStatus.DISCONNECTED -> stringResource(R.string.chat_sse_disconnected) to statusColors.disconnected
                }
                Text(
                    text = sseLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = sseColor,
                )
            }

            IconButton(
                onClick = onRefresh,
                modifier = Modifier.testTag("chat_refresh_sessions_button"),
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }

            FilledTonalButton(
                onClick = onCreateSession,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.testTag("chat_new_session_button"),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.chat_new_session))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        when {
            uiState.isLoadingSessions && uiState.sessions.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            uiState.errorMessage != null && uiState.sessions.isEmpty() -> {
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
                            text = uiState.errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        FilledTonalButton(onClick = onRefresh) {
                            Text(stringResource(R.string.action_refresh))
                        }
                    }
                }
            }

            uiState.sessions.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.chat_sessions_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.chat_sessions_empty_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onCreateSession) {
                            Icon(Icons.Rounded.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.chat_new_session))
                        }
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.sessions, key = { it.id }) { session ->
                        SessionCard(
                            session = session,
                            selected = session.id == uiState.activeSessionId,
                            onClick = { onOpenSession(session.id) },
                            onRename = { onRenameSession(session) },
                            onFork = { onForkSession(session) },
                            onDelete = { onDeleteSession(session) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: OpenCodeSession,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onFork: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val updatedTime = remember(session.updatedAtMillis) {
        if (session.updatedAtMillis <= 0L) ""
        else DateTimeFormatter.ofPattern("dd.MM HH:mm")
            .format(Instant.ofEpochMilli(session.updatedAtMillis).atZone(ZoneId.systemDefault()))
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (session.isBusy) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(LocalStatusColors.current.warning),
                        )
                    }
                    Text(
                        text = session.title.ifBlank { stringResource(R.string.chat_session_untitled) },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = if (session.isBusy) {
                        stringResource(R.string.chat_session_busy)
                    } else {
                        updatedTime.ifBlank { session.id.take(12) }
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                    color = if (session.isBusy) {
                        LocalStatusColors.current.warning
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = null)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_session_rename)) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_session_fork)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.CallSplit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onFork()
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.chat_session_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveSessionPane(
    uiState: ChatUiState,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onRefreshMessages: () -> Unit,
    onOpenDiffSheet: (focusFile: String?) -> Unit,
    onForkSession: () -> Unit,
    onRevertMessage: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onCopyMessage: (OpenCodeMessage) -> Unit,
    onCodeCopied: () -> Unit,
    onWaitMore: () -> Unit,
    onSwitchModelAndRetry: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onComposerChange: (String) -> Unit,
    onAttachClick: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onOpenAgentPicker: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(
        uiState.messages.size,
        uiState.messages.lastOrNull()?.parts?.size,
        uiState.isResponseStalled,
    ) {
        val totalItems = uiState.messages.size + if (uiState.isResponseStalled) 1 else 0
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    Column(
        modifier = modifier.imePadding(),
    ) {
        // Session Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBackButton) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("chat_back_to_sessions"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f).padding(start = if (showBackButton) 0.dp else 8.dp)) {
                Text(
                    text = uiState.activeSession?.title?.ifBlank { stringResource(R.string.chat_session_untitled) }
                        ?: stringResource(R.string.chat_new_session),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sessionTotals = uiState.sessionUsageTotals
                if (uiState.isActiveSessionBusy) {
                    Text(
                        text = stringResource(R.string.chat_session_busy),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalStatusColors.current.warning,
                    )
                } else if (sessionTotals.hasAnyTokens) {
                    val costLabel = when {
                        sessionTotals.hasAnyCurrencyCost -> UsageCalculator.formatUsd(sessionTotals.totalCurrencyCostUsd)
                        else -> stringResource(R.string.usage_cost_not_set)
                    }
                    val unitsSuffix = if (sessionTotals.hasAnyCustomPricing && sessionTotals.totalProviderUnits > 0.0) {
                        " • ${UsageCalculator.formatUnits(sessionTotals.totalProviderUnits)}"
                    } else {
                        ""
                    }
                    Text(
                        text = stringResource(
                            R.string.chat_session_tokens_summary,
                            UsageCalculator.formatTokenCount(sessionTotals.totalTokens),
                            costLabel,
                        ) + unitsSuffix,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("chat_session_usage_header"),
                    )
                }
            }
            if (uiState.activeSessionId != null) {
                AssistChip(
                    onClick = { onOpenDiffSheet(null) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Difference,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    label = {
                        Text(
                            text = if (uiState.sessionDiffs.isNotEmpty()) {
                                stringResource(R.string.diff_button_count, uiState.sessionDiffs.size)
                            } else {
                                stringResource(R.string.diff_button_label)
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    modifier = Modifier.testTag("chat_open_diff_button"),
                )
                IconButton(onClick = onForkSession) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.CallSplit,
                        contentDescription = stringResource(R.string.chat_session_fork),
                    )
                }
                IconButton(onClick = onRefreshMessages) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.action_refresh),
                    )
                }
            }
        }
        uiState.sessionContextUsage?.let { ctx ->
            ContextWindowUsageBar(contextUsage = ctx)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Messages list
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                uiState.isLoadingMessages && uiState.messages.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                uiState.messages.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.chat_empty_messages),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        itemsIndexed(uiState.messages, key = { _, m -> m.id }) { index, msg ->
                            val prevUserCreatedAt = remember(uiState.messages, index) {
                                if (msg.isUser) {
                                    null
                                } else {
                                    var found: Long? = null
                                    for (i in (index - 1) downTo 0) {
                                        val candidate = uiState.messages[i]
                                        if (candidate.isUser && candidate.createdAtMillis > 0L) {
                                            found = candidate.createdAtMillis
                                            break
                                        }
                                    }
                                    found
                                }
                            }
                            ChatMessageBubble(
                                message = msg,
                                previousUserCreatedAtMillis = prevUserCreatedAt,
                                pricingByKey = uiState.modelPricingByKey,
                                onOpenFile = onOpenFile,
                                onOpenDiff = onOpenDiffSheet,
                                onCopy = { onCopyMessage(msg) },
                                onRevert = { onRevertMessage(msg.id) },
                                onCodeCopied = onCodeCopied,
                            )
                        }
                        if (uiState.isResponseStalled) {
                            item(key = "chat_stalled_banner") {
                                ResponseStalledCard(
                                    onWaitMore = onWaitMore,
                                    onStop = onStop,
                                    onSwitchModelAndRetry = onSwitchModelAndRetry,
                                    onCopyDiagnostics = onCopyDiagnostics,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Composer
        ChatComposerBar(
            text = uiState.composerText,
            attachments = uiState.attachedFiles,
            agentName = uiState.selectedAgentName,
            modelDisplay = uiState.selectedModelDisplay ?: stringResource(R.string.settings_model_not_selected),
            isBusy = uiState.isActiveSessionBusy,
            onTextChange = onComposerChange,
            onAttachClick = onAttachClick,
            onRemoveAttachment = onRemoveAttachment,
            onOpenAgentPicker = onOpenAgentPicker,
            onOpenModelPicker = onOpenModelPicker,
            onSend = onSend,
            onStop = onStop,
        )
    }
}

@Composable
private fun ContextWindowUsageBar(
    contextUsage: SessionContextUsage,
) {
    val warningColor = LocalStatusColors.current.warning
    val barColor = when {
        contextUsage.percentInt >= 95 -> MaterialTheme.colorScheme.error
        contextUsage.isWarning -> warningColor
        else -> MaterialTheme.colorScheme.primary
    }
    val bgColor = if (contextUsage.isWarning) {
        warningColor.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("chat_context_window_bar"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (contextUsage.isWarning) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = barColor,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = stringResource(
                    R.string.chat_context_usage_label,
                    UsageCalculator.formatTokenCount(contextUsage.usedTokens),
                    UsageCalculator.formatTokenCount(contextUsage.limitTokens),
                    contextUsage.percentInt,
                ),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                color = if (contextUsage.isWarning) barColor else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (contextUsage.isWarning) {
                Text(
                    text = stringResource(R.string.chat_context_warning_80),
                    style = MaterialTheme.typography.labelSmall,
                    color = barColor,
                    maxLines = 1,
                )
            }
        }
        LinearProgressIndicator(
            progress = { contextUsage.fraction },
            color = barColor,
            trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun ChatMessageBubble(
    message: OpenCodeMessage,
    previousUserCreatedAtMillis: Long?,
    pricingByKey: Map<String, ModelPricing>,
    onOpenFile: (String) -> Unit,
    onOpenDiff: (String?) -> Unit,
    onCopy: () -> Unit,
    onRevert: () -> Unit,
    onCodeCopied: () -> Unit,
) {
    val containerColor = if (message.isUser) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val borderColor = if (message.isUser) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    val timestampLabel = remember(message.createdAtMillis) {
        ChatTimeFormatter.formatMessageTimestamp(message.createdAtMillis)
    }
    val durationLabel = remember(
        message.isUser,
        message.createdAtMillis,
        message.completedAtMillis,
        previousUserCreatedAtMillis,
    ) {
        ChatTimeFormatter.resolveAssistantDuration(message, previousUserCreatedAtMillis)
    }
    val metaLine = remember(timestampLabel, durationLabel) {
        listOfNotNull(
            timestampLabel.takeIf { it.isNotBlank() },
            durationLabel?.takeIf { it.isNotBlank() },
        ).joinToString(" • ")
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(if (message.isUser) R.string.chat_role_user else R.string.chat_role_assistant),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (message.isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                        )
                        message.agent?.let { agent ->
                            StatusChip(
                                label = agent,
                                accentColor = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                    if (metaLine.isNotBlank()) {
                        Text(
                            text = metaLine,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    IconButton(
                        onClick = onCopy,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("chat_copy_message_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = stringResource(R.string.chat_copy_message),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    IconButton(
                        onClick = onRevert,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = stringResource(R.string.chat_revert_action),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            message.parts.forEach { part ->
                when (part) {
                    is OpenCodePart.Text -> {
                        if (part.text.isNotBlank()) {
                            MarkdownMessageContent(
                                markdown = part.text,
                                onCodeCopied = onCodeCopied,
                            )
                        }
                    }

                    is OpenCodePart.Reasoning -> {
                        if (part.text.isNotBlank()) {
                            ReasoningCard(part = part)
                        }
                    }

                    is OpenCodePart.Tool -> {
                        ToolCallCard(
                            part = part,
                            onOpenFile = onOpenFile,
                        )
                    }

                    is OpenCodePart.FileAttachment -> {
                        AssistChip(
                            onClick = {
                                val path = part.url.removePrefix("file://")
                                if (path.startsWith("/")) onOpenFile(path)
                            },
                            label = {
                                Text(
                                    text = "@${part.filename}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                )
                            },
                        )
                    }

                    is OpenCodePart.Patch -> {
                        if (part.files.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    item(key = "view_diff_chip") {
                                        AssistChip(
                                            onClick = { onOpenDiff(part.files.firstOrNull()) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Rounded.Difference,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(15.dp),
                                                )
                                            },
                                            label = {
                                                Text(
                                                    text = stringResource(R.string.diff_patch_view_action),
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            },
                                        )
                                    }
                                    items(part.files) { path ->
                                        AssistChip(
                                            onClick = { onOpenFile(path) },
                                            label = {
                                                Text(
                                                    text = path.substringAfterLast('/'),
                                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            message.error?.takeIf { it.isNotBlank() }?.let { err ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            if (!message.isUser && (message.tokens?.hasAny == true || !message.modelId.isNullOrBlank())) {
                val tokens = message.tokens
                val pricing = pricingByKey[ModelPricing.keyOf(message.providerId, message.modelId)]
                val computed = tokens?.let { UsageCalculator.computeMessageCost(it, message.costUsd, pricing) }
                val notSetText = stringResource(R.string.usage_cost_not_set)
                val footerText = remember(message.modelId, tokens, computed, pricing, notSetText) {
                    buildList {
                        message.modelId?.takeIf { it.isNotBlank() }?.let(::add)
                        if (tokens != null && tokens.hasAny) {
                            val ioStr = "${UsageCalculator.formatTokenCount(tokens.input)} / ${UsageCalculator.formatTokenCount(tokens.output)}"
                            val cacheSuffix = if (tokens.totalCacheTokens > 0L) {
                                " (+кэш ${UsageCalculator.formatTokenCount(tokens.totalCacheTokens)})"
                            } else {
                                ""
                            }
                            add("$ioStr ток.$cacheSuffix")
                        }
                        if (computed != null) {
                            if (computed.hasCurrencyCost && computed.currencyCostUsd != null) {
                                add(UsageCalculator.formatUsd(computed.currencyCostUsd))
                            } else {
                                add(notSetText)
                            }
                            if (pricing != null && computed.providerUnits > 0.0) {
                                add("${UsageCalculator.formatUnits(computed.providerUnits)} (оценка)")
                            }
                        }
                    }.joinToString(" • ")
                }
                if (footerText.isNotBlank()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    Text(
                        text = footerText,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("chat_message_usage_footer"),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatComposerBar(
    text: String,
    attachments: List<AttachedFileRef>,
    agentName: String,
    modelDisplay: String,
    isBusy: Boolean,
    onTextChange: (String) -> Unit,
    onAttachClick: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onOpenAgentPicker: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Agent & Model selector chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item(key = "agent_chip") {
                    AssistChip(
                        onClick = onOpenAgentPicker,
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.SmartToy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        label = {
                            Text(
                                text = agentName,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                            )
                        },
                        modifier = Modifier.testTag("chat_agent_chip"),
                    )
                }
                item(key = "model_chip") {
                    AssistChip(
                        onClick = onOpenModelPicker,
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        label = {
                            Text(
                                text = modelDisplay,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        modifier = Modifier.testTag("chat_model_chip"),
                    )
                }
            }

            // Attached files chips
            if (attachments.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(attachments, key = { it.path }) { file ->
                        InputChip(
                            selected = true,
                            onClick = { onRemoveAttachment(file.path) },
                            label = {
                                Text(
                                    text = "@${file.name}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.action_close),
                                    modifier = Modifier.size(14.dp),
                                )
                            },
                        )
                    }
                }
            }

            // Text field + @ + Send/Stop
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconButton(
                    onClick = onAttachClick,
                    modifier = Modifier.testTag("chat_attach_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AlternateEmail,
                        contentDescription = stringResource(R.string.chat_attach_file),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.chat_input_placeholder),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    maxLines = 5,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_composer_input"),
                )

                if (isBusy) {
                    FilledIconButton(
                        onClick = onStop,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        modifier = Modifier.testTag("chat_stop_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = stringResource(R.string.chat_stop),
                        )
                    }
                } else {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = text.isNotBlank() || attachments.isNotEmpty(),
                        modifier = Modifier.testTag("chat_send_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = stringResource(R.string.chat_send),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceHeaderBar(
    workspacePath: String,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("chat_workspace_header_bar"),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.workspace_folder_label) + ":",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = workspacePath.ifBlank { stringResource(R.string.workspace_folder_not_set) },
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = stringResource(R.string.workspace_picker_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResponseStalledCard(
    onWaitMore: () -> Unit,
    onStop: () -> Unit,
    onSwitchModelAndRetry: () -> Unit,
    onCopyDiagnostics: () -> Unit,
) {
    val warningColor = LocalStatusColors.current.warning
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = warningColor.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, warningColor.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("chat_stalled_card"),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = warningColor,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.chat_stalled_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = stringResource(R.string.chat_stalled_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = onWaitMore,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("chat_stalled_wait_button"),
                ) {
                    Icon(Icons.Rounded.HourglassTop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.chat_stalled_wait),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("chat_stalled_stop_button"),
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.chat_stalled_stop),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                OutlinedButton(
                    onClick = onSwitchModelAndRetry,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("chat_stalled_switch_button"),
                ) {
                    Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.chat_stalled_switch_retry),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                OutlinedButton(
                    onClick = onCopyDiagnostics,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("chat_stalled_diag_button"),
                ) {
                    Icon(Icons.Rounded.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.chat_stalled_copy_diag),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

private fun copyPlainTextToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
}

