package com.example.ui.files

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.ClipboardOperation
import com.example.domain.model.FileSearchMatch
import com.example.domain.model.FileSearchMode
import com.example.domain.model.FileSortBy
import com.example.domain.model.LocalSafEntry
import com.example.domain.model.RemoteFileEntry
import com.example.ui.chat.RootWorkspaceWarningDialog
import com.example.ui.git.GitPanelView
import com.example.ui.git.GitViewModel
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.MonoFontFamily
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class FilesSubTab {
    FILES,
    GIT,
}

@Composable
fun FileManagerScreen(
    viewModel: FileManagerViewModel,
    gitViewModel: GitViewModel,
    selectedSubTab: FilesSubTab = FilesSubTab.FILES,
    onSelectSubTab: (FilesSubTab) -> Unit = {},
    activeWorkspacePath: String = "",
    onOpenServers: () -> Unit,
    onOpenCodeEditor: (remotePath: String, line: Int?) -> Unit,
    onAttachToChat: (RemoteFileEntry) -> Unit = {},
    onSetWorkspace: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val gitUiState by gitViewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showCreateFileDialog by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<RemoteFileEntry?>(null) }
    var moveTargets by remember { mutableStateOf<List<RemoteFileEntry>?>(null) }
    var deleteTargets by remember { mutableStateOf<List<RemoteFileEntry>?>(null) }
    var chmodTarget by remember { mutableStateOf<RemoteFileEntry?>(null) }
    var propertiesTarget by remember { mutableStateOf<RemoteFileEntry?>(null) }
    var pendingDownloadEntry by remember { mutableStateOf<RemoteFileEntry?>(null) }
    var showFolderTree by remember { mutableStateOf(false) }
    var showTransferQueue by remember { mutableStateOf(false) }
    var pendingRootWorkspace by remember { mutableStateOf(false) }

    val requestSetWorkspace: (String) -> Unit = { targetPath ->
        val normalized = com.example.data.sftp.SftpRepository.normalizeRemotePath(targetPath)
        if (normalized == "/") {
            pendingRootWorkspace = true
        } else {
            onSetWorkspace(normalized)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(Unit) {
        gitViewModel.messages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val effectiveGitDir = activeWorkspacePath.ifBlank { uiState.currentPath }
    LaunchedEffect(selectedSubTab, effectiveGitDir, uiState.isConnected) {
        if (uiState.isConnected && selectedSubTab == FilesSubTab.GIT && effectiveGitDir.isNotBlank()) {
            gitViewModel.setTargetDirectory(effectiveGitDir, autoRefresh = true)
        }
    }

    val uploadPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.uploadFiles(uris)
        }
    }

    val downloadCreator = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val entry = pendingDownloadEntry
        pendingDownloadEntry = null
        if (uri != null && entry != null) {
            viewModel.downloadFileToUri(entry, uri)
        }
    }

    val localFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri != null) {
            viewModel.setLocalSafFolder(treeUri)
        }
    }

    if (!uiState.isConnected && !uiState.isReconnecting) {
        DisconnectedFilesPane(
            onOpenServers = onOpenServers,
            modifier = modifier,
        )
        return
    }

    Box(modifier = modifier.fillMaxSize().imePadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (uiState.isReconnecting) {
                Surface(
                    color = LocalStatusColors.current.warning.copy(alpha = 0.18f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.reconnect_banner_files),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalStatusColors.current.warning,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("files_git_segmented_row"),
                    ) {
                        SegmentedButton(
                            selected = selectedSubTab == FilesSubTab.FILES,
                            onClick = { onSelectSubTab(FilesSubTab.FILES) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            modifier = Modifier.testTag("files_segment_files"),
                        ) {
                            Text(stringResource(R.string.files_segment_files))
                        }
                        val dirtyCount = gitUiState.status.totalChangesCount
                        val gitLabel = if (gitUiState.status.summary.isGitRepo && dirtyCount > 0) {
                            "${stringResource(R.string.files_segment_git)} • $dirtyCount"
                        } else {
                            stringResource(R.string.files_segment_git)
                        }
                        SegmentedButton(
                            selected = selectedSubTab == FilesSubTab.GIT,
                            onClick = {
                                onSelectSubTab(FilesSubTab.GIT)
                                if (effectiveGitDir.isNotBlank()) {
                                    gitViewModel.setTargetDirectory(effectiveGitDir, autoRefresh = true)
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            modifier = Modifier.testTag("files_segment_git"),
                        ) {
                            Text(gitLabel)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            if (selectedSubTab == FilesSubTab.GIT) {
                GitPanelView(
                    uiState = gitUiState,
                    onRefresh = gitViewModel::refresh,
                    onInitRepo = gitViewModel::initRepository,
                    onFetch = gitViewModel::fetch,
                    onPull = gitViewModel::pull,
                    onPush = gitViewModel::push,
                    onStageFile = gitViewModel::stageFile,
                    onUnstageFile = gitViewModel::unstageFile,
                    onStageAll = gitViewModel::stageAll,
                    onUnstageAll = gitViewModel::unstageAll,
                    onDiscardFile = gitViewModel::discardFile,
                    onOpenFileDiff = gitViewModel::openFileDiff,
                    onCloseDiff = gitViewModel::closeDiff,
                    onCommitMessageChange = gitViewModel::updateCommitMessage,
                    onPrependCommitPrefix = gitViewModel::prependCommitPrefix,
                    onToggleAmend = gitViewModel::toggleAmend,
                    onCommit = gitViewModel::commit,
                    onLoadBranchesAndLog = gitViewModel::loadBranchesAndLog,
                    onSwitchBranch = gitViewModel::switchBranch,
                    onCreateBranch = gitViewModel::createBranch,
                    onOpenCommitDiff = gitViewModel::openCommitDiff,
                    onOpenCodeEditor = onOpenCodeEditor,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else {
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val isDualPane = maxWidth >= 840.dp

            Row(modifier = Modifier.fillMaxSize()) {
                // Server SFTP Pane
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    if (uiState.isSelectionMode) {
                        SelectionActionBar(
                            count = uiState.selectedPaths.size,
                            onSelectAll = viewModel::selectAll,
                            onCopy = { viewModel.copyToClipboard(uiState.selectedEntries) },
                            onCut = { viewModel.cutToClipboard(uiState.selectedEntries) },
                            onMove = { moveTargets = uiState.selectedEntries },
                            onDelete = { deleteTargets = uiState.selectedEntries },
                            onClose = viewModel::clearSelection,
                        )
                    } else {
                        FileManagerToolbar(
                            sortBy = uiState.sortBy,
                            showHidden = uiState.showHidden,
                            activeTransfers = uiState.activeTransferCount,
                            onOpenTree = { showFolderTree = true },
                            onToggleSearch = viewModel::toggleSearch,
                            onSelectSort = viewModel::setSort,
                            onToggleHidden = viewModel::toggleShowHidden,
                            onUpload = { uploadPicker.launch(arrayOf("*/*")) },
                            onOpenTransfers = { showTransferQueue = true },
                            onNewFile = { showCreateFileDialog = true },
                            onNewFolder = { showCreateFolderDialog = true },
                            onRefresh = viewModel::refresh,
                        )
                    }

                    if (uiState.isMutating) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    AnimatedVisibility(visible = uiState.searchActive) {
                        FileSearchBar(
                            query = uiState.searchQuery,
                            mode = uiState.searchMode,
                            isSearching = uiState.isSearching,
                            onQueryChange = viewModel::onSearchQueryChange,
                            onModeChange = viewModel::onSearchModeChange,
                            onRunSearch = viewModel::runSearch,
                            onClose = viewModel::toggleSearch,
                        )
                    }

                    BreadcrumbsBar(
                        currentPath = uiState.currentPath,
                        onNavigate = viewModel::openDirectory,
                        onNavigateUp = { viewModel.navigateUp() },
                        onSetCurrentAsWorkspace = { requestSetWorkspace(uiState.currentPath) },
                    )

                    uiState.clipboard?.let { clip ->
                        ClipboardPasteBanner(
                            operation = clip.operation,
                            count = clip.entries.size,
                            onPaste = viewModel::pasteClipboard,
                            onCancel = viewModel::clearClipboard,
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    if (uiState.searchActive && (uiState.searchPerformed || uiState.isSearching)) {
                        SearchResultsList(
                            results = uiState.searchResults,
                            isSearching = uiState.isSearching,
                            onSelectMatch = { match ->
                                if (match.isDirectory) {
                                    viewModel.toggleSearch()
                                    viewModel.openDirectory(match.path)
                                } else {
                                    onOpenCodeEditor(match.path, match.lineNumber)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        RemoteFileListContent(
                            uiState = uiState,
                            hasLocalFolder = isDualPane && uiState.localFolderUri != null,
                            onOpenEntry = { entry ->
                                if (uiState.isSelectionMode) {
                                    viewModel.toggleSelection(entry.path)
                                } else if (entry.isDirectory) {
                                    viewModel.openDirectory(entry.path)
                                } else {
                                    onOpenCodeEditor(entry.path, null)
                                }
                            },
                            onLongPressEntry = { entry ->
                                viewModel.toggleSelection(entry.path)
                            },
                            onRename = { renameTarget = it },
                            onCopy = { viewModel.copyToClipboard(listOf(it)) },
                            onCut = { viewModel.cutToClipboard(listOf(it)) },
                            onMove = { moveTargets = listOf(it) },
                            onDownload = { entry ->
                                pendingDownloadEntry = entry
                                downloadCreator.launch(entry.name)
                            },
                            onDownloadToLocalPane = viewModel::downloadEntryToSelectedLocalFolder,
                            onChmod = { chmodTarget = it },
                            onProperties = { propertiesTarget = it },
                            onAttachToChat = onAttachToChat,
                            onSetWorkspace = { entry -> requestSetWorkspace(entry.path) },
                            onDelete = { deleteTargets = listOf(it) },
                            onRetry = viewModel::refresh,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // Wide / Tablet Dual-Pane Local SAF Browser (Phone <-> Server)
                if (isDualPane) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    LocalPhoneSafPane(
                        folderName = uiState.localFolderName,
                        entries = uiState.localEntries,
                        isLoading = uiState.isLocalLoading,
                        onPickFolder = { localFolderPicker.launch(null) },
                        onRefresh = viewModel::refreshLocalSafFolder,
                        onUploadEntry = { localEntry ->
                            if (!localEntry.isDirectory) {
                                viewModel.uploadFiles(listOf(localEntry.uri))
                            }
                        },
                        modifier = Modifier
                            .weight(0.75f)
                            .fillMaxHeight(),
                    )
                }
            }
        }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    // Dialogs & Sheets
    if (showCreateFileDialog) {
        TextInputFileDialog(
            title = stringResource(R.string.files_new_file),
            label = stringResource(R.string.files_create_name_label),
            initialValue = "",
            confirmText = stringResource(R.string.action_confirm),
            onConfirm = { name ->
                showCreateFileDialog = false
                viewModel.createFile(name) { createdPath ->
                    onOpenCodeEditor(createdPath, null)
                }
            },
            onDismiss = { showCreateFileDialog = false },
        )
    }

    if (showCreateFolderDialog) {
        TextInputFileDialog(
            title = stringResource(R.string.files_new_folder),
            label = stringResource(R.string.files_create_name_label),
            initialValue = "",
            confirmText = stringResource(R.string.action_confirm),
            onConfirm = { name ->
                showCreateFolderDialog = false
                viewModel.createFolder(name)
            },
            onDismiss = { showCreateFolderDialog = false },
        )
    }

    renameTarget?.let { entry ->
        TextInputFileDialog(
            title = stringResource(R.string.files_action_rename),
            label = stringResource(R.string.files_create_name_label),
            initialValue = entry.name,
            confirmText = stringResource(R.string.action_confirm),
            onConfirm = { newName ->
                renameTarget = null
                viewModel.renameEntry(entry, newName)
            },
            onDismiss = { renameTarget = null },
        )
    }

    moveTargets?.let { targets ->
        TextInputFileDialog(
            title = stringResource(R.string.files_action_move),
            label = stringResource(R.string.files_move_target_label),
            initialValue = uiState.currentPath,
            confirmText = stringResource(R.string.action_confirm),
            onConfirm = { dest ->
                moveTargets = null
                viewModel.moveEntries(targets, dest)
            },
            onDismiss = { moveTargets = null },
        )
    }

    deleteTargets?.let { targets ->
        DeleteFilesConfirmDialog(
            count = targets.size,
            onConfirm = {
                deleteTargets = null
                viewModel.deleteEntries(targets)
            },
            onDismiss = { deleteTargets = null },
        )
    }

    chmodTarget?.let { entry ->
        ChmodDialog(
            entry = entry,
            onApply = { mask ->
                chmodTarget = null
                viewModel.chmodEntry(entry, mask)
            },
            onDismiss = { chmodTarget = null },
        )
    }

    propertiesTarget?.let { entry ->
        FilePropertiesDialog(
            entry = entry,
            onDismiss = { propertiesTarget = null },
        )
    }

    if (showFolderTree) {
        FolderTreeBottomSheet(
            homePath = uiState.homePath,
            currentPath = uiState.currentPath,
            treeChildren = uiState.treeChildren,
            expandedPaths = uiState.expandedTreePaths,
            loadingPaths = uiState.loadingTreePaths,
            onToggleExpand = viewModel::toggleTreeExpand,
            onLoadNode = viewModel::loadTreeNode,
            onSelectPath = viewModel::openDirectory,
            onDismiss = { showFolderTree = false },
        )
    }

    if (showTransferQueue) {
        TransferQueueBottomSheet(
            tasks = uiState.transfers,
            onCancelTask = viewModel::cancelTransfer,
            onClearFinished = viewModel::clearFinishedTransfers,
            onDismiss = { showTransferQueue = false },
        )
    }

    if (pendingRootWorkspace) {
        RootWorkspaceWarningDialog(
            onConfirm = {
                pendingRootWorkspace = false
                onSetWorkspace("/")
            },
            onDismiss = { pendingRootWorkspace = false },
        )
    }
}

@Composable
private fun DisconnectedFilesPane(
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
                    imageVector = Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(44.dp),
                )
                Text(
                    text = stringResource(R.string.files_not_connected_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.files_not_connected_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onOpenServers,
                    modifier = Modifier.testTag("files_choose_server_button"),
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
private fun FileManagerToolbar(
    sortBy: FileSortBy,
    showHidden: Boolean,
    activeTransfers: Int,
    onOpenTree: () -> Unit,
    onToggleSearch: () -> Unit,
    onSelectSort: (FileSortBy) -> Unit,
    onToggleHidden: () -> Unit,
    onUpload: () -> Unit,
    onOpenTransfers: () -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onRefresh: () -> Unit,
) {
    var showSortMenu by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onOpenTree,
            modifier = Modifier.testTag("files_tree_button"),
        ) {
            Icon(Icons.Rounded.AccountTree, contentDescription = stringResource(R.string.files_tree_title))
        }
        IconButton(
            onClick = onToggleSearch,
            modifier = Modifier.testTag("files_search_button"),
        ) {
            Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.files_search_run))
        }
        Box {
            IconButton(
                onClick = { showSortMenu = true },
                modifier = Modifier.testTag("files_sort_button"),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = stringResource(R.string.files_sort))
            }
            DropdownMenu(
                expanded = showSortMenu,
                onDismissRequest = { showSortMenu = false },
            ) {
                listOf(
                    FileSortBy.NAME to R.string.files_sort_name,
                    FileSortBy.SIZE to R.string.files_sort_size,
                    FileSortBy.MODIFIED to R.string.files_sort_modified,
                    FileSortBy.TYPE to R.string.files_sort_type,
                ).forEach { (option, labelRes) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(labelRes) + if (sortBy == option) " ✓" else "",
                            )
                        },
                        onClick = {
                            onSelectSort(option)
                            showSortMenu = false
                        },
                    )
                }
            }
        }
        IconButton(
            onClick = onToggleHidden,
            modifier = Modifier.testTag("files_hidden_toggle"),
        ) {
            Icon(
                imageVector = if (showHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                contentDescription = stringResource(R.string.files_show_hidden),
                tint = if (showHidden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        IconButton(
            onClick = onUpload,
            modifier = Modifier.testTag("files_upload_button"),
        ) {
            Icon(Icons.Rounded.Upload, contentDescription = stringResource(R.string.files_upload))
        }
        IconButton(
            onClick = onOpenTransfers,
            modifier = Modifier.testTag("files_transfers_button"),
        ) {
            BadgedBox(
                badge = {
                    if (activeTransfers > 0) {
                        Badge { Text("$activeTransfers") }
                    }
                },
            ) {
                Icon(Icons.Rounded.SwapVert, contentDescription = stringResource(R.string.files_transfers_title))
            }
        }
        Box {
            IconButton(
                onClick = { showAddMenu = true },
                modifier = Modifier.testTag("files_add_menu_button"),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.files_new_file))
            }
            DropdownMenu(
                expanded = showAddMenu,
                onDismissRequest = { showAddMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_new_file)) },
                    leadingIcon = { Icon(Icons.Rounded.NoteAdd, contentDescription = null) },
                    onClick = {
                        showAddMenu = false
                        onNewFile()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_new_folder)) },
                    leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, contentDescription = null) },
                    onClick = {
                        showAddMenu = false
                        onNewFolder()
                    },
                )
            }
        }
        IconButton(
            onClick = onRefresh,
            modifier = Modifier.testTag("files_refresh_button"),
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.action_refresh))
        }
    }
}

@Composable
private fun SelectionActionBar(
    count: Int,
    onSelectAll: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close))
        }
        Text(
            text = stringResource(R.string.files_selected_count, count),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSelectAll) {
            Icon(Icons.Rounded.SelectAll, contentDescription = stringResource(R.string.files_select_all))
        }
        IconButton(onClick = onCopy) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.files_action_copy))
        }
        IconButton(onClick = onCut) {
            Icon(Icons.Rounded.ContentCut, contentDescription = stringResource(R.string.files_action_cut))
        }
        IconButton(onClick = onMove) {
            Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = stringResource(R.string.files_action_move))
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = stringResource(R.string.files_action_delete),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun BreadcrumbsBar(
    currentPath: String,
    onNavigate: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onSetCurrentAsWorkspace: () -> Unit,
) {
    val segments = remember(currentPath) {
        val list = mutableListOf("/" to "/")
        if (currentPath != "/") {
            val parts = currentPath.trim('/').split('/').filter { it.isNotEmpty() }
            var acc = ""
            parts.forEach { part ->
                acc += "/$part"
                list.add(part to acc)
            }
        }
        list
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (currentPath != "/") {
            IconButton(
                onClick = onNavigateUp,
                modifier = Modifier
                    .size(36.dp)
                    .testTag("files_up_button"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        LazyRow(
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(horizontal = 4.dp),
            modifier = Modifier.weight(1f),
        ) {
            itemsIndexed(segments) { idx, (label, fullPath) ->
                val isLast = idx == segments.lastIndex
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                    color = if (isLast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onNavigate(fullPath) }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                )
                if (!isLast) {
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        IconButton(
            onClick = onSetCurrentAsWorkspace,
            modifier = Modifier
                .size(36.dp)
                .testTag("files_set_current_workspace_button"),
        ) {
            Icon(
                imageVector = Icons.Rounded.FolderOpen,
                contentDescription = stringResource(R.string.files_action_set_workspace),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ClipboardPasteBanner(
    operation: ClipboardOperation,
    count: Int,
    onPaste: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(
                    if (operation == ClipboardOperation.COPY) R.string.files_clipboard_copy else R.string.files_clipboard_cut,
                    count,
                ),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(
                onClick = onPaste,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.testTag("files_paste_button"),
            ) {
                Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.files_action_paste))
            }
            IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_cancel))
            }
        }
    }
}

@Composable
private fun FileSearchBar(
    query: String,
    mode: FileSearchMode,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onModeChange: (FileSearchMode) -> Unit,
    onRunSearch: () -> Unit,
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = {
                        Text(
                            stringResource(
                                if (mode == FileSearchMode.NAME) {
                                    R.string.files_search_placeholder
                                } else {
                                    R.string.files_search_content_placeholder
                                },
                            ),
                        )
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("files_search_input"),
                )
                FilledTonalButton(
                    onClick = onRunSearch,
                    enabled = query.isNotBlank() && !isSearching,
                    modifier = Modifier.testTag("files_search_run_button"),
                ) {
                    Text(stringResource(R.string.files_search_run))
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == FileSearchMode.NAME,
                    onClick = { onModeChange(FileSearchMode.NAME) },
                    label = { Text(stringResource(R.string.files_search_mode_name)) },
                )
                FilterChip(
                    selected = mode == FileSearchMode.CONTENT,
                    onClick = { onModeChange(FileSearchMode.CONTENT) },
                    label = { Text(stringResource(R.string.files_search_mode_content)) },
                )
            }
        }
    }
}

@Composable
private fun SearchResultsList(
    results: List<FileSearchMatch>,
    isSearching: Boolean,
    onSelectMatch: (FileSearchMatch) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        isSearching -> {
            Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        results.isEmpty() -> {
            Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.files_search_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        else -> {
            LazyColumn(
                modifier = modifier.fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(results) { match ->
                    Surface(
                        onClick = { onSelectMatch(match) },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = if (match.isDirectory) Icons.Rounded.Folder else Icons.Rounded.Code,
                                    contentDescription = null,
                                    tint = if (match.isDirectory) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = match.fileName + (match.lineNumber?.let { ":$it" } ?: ""),
                                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = MonoFontFamily),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = match.path,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            match.previewLine?.let { preview ->
                                Text(
                                    text = preview,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteFileListContent(
    uiState: FileManagerUiState,
    hasLocalFolder: Boolean,
    onOpenEntry: (RemoteFileEntry) -> Unit,
    onLongPressEntry: (RemoteFileEntry) -> Unit,
    onRename: (RemoteFileEntry) -> Unit,
    onCopy: (RemoteFileEntry) -> Unit,
    onCut: (RemoteFileEntry) -> Unit,
    onMove: (RemoteFileEntry) -> Unit,
    onDownload: (RemoteFileEntry) -> Unit,
    onDownloadToLocalPane: (RemoteFileEntry) -> Unit,
    onChmod: (RemoteFileEntry) -> Unit,
    onProperties: (RemoteFileEntry) -> Unit,
    onAttachToChat: (RemoteFileEntry) -> Unit,
    onSetWorkspace: (RemoteFileEntry) -> Unit,
    onDelete: (RemoteFileEntry) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        uiState.isLoading -> {
            Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        uiState.errorMessage != null -> {
            Box(
                modifier = modifier
                    .fillMaxWidth()
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
                    FilledTonalButton(onClick = onRetry) {
                        Text(stringResource(R.string.action_refresh))
                    }
                }
            }
        }

        uiState.entries.isEmpty() -> {
            Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.files_empty_dir),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        else -> {
            LazyColumn(
                modifier = modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(uiState.entries, key = { it.path }) { entry ->
                    RemoteFileRow(
                        entry = entry,
                        selected = entry.path in uiState.selectedPaths,
                        selectionMode = uiState.isSelectionMode,
                        hasLocalFolder = hasLocalFolder,
                        onClick = { onOpenEntry(entry) },
                        onLongClick = { onLongPressEntry(entry) },
                        onRename = { onRename(entry) },
                        onCopy = { onCopy(entry) },
                        onCut = { onCut(entry) },
                        onMove = { onMove(entry) },
                        onDownload = { onDownload(entry) },
                        onDownloadToLocalPane = { onDownloadToLocalPane(entry) },
                        onChmod = { onChmod(entry) },
                        onProperties = { onProperties(entry) },
                        onAttachToChat = { onAttachToChat(entry) },
                        onSetWorkspace = { onSetWorkspace(entry) },
                        onDelete = { onDelete(entry) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RemoteFileRow(
    entry: RemoteFileEntry,
    selected: Boolean,
    selectionMode: Boolean,
    hasLocalFolder: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onMove: () -> Unit,
    onDownload: () -> Unit,
    onDownloadToLocalPane: () -> Unit,
    onChmod: () -> Unit,
    onProperties: () -> Unit,
    onAttachToChat: () -> Unit,
    onSetWorkspace: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    val formattedTime = remember(entry.modifiedAtEpochSec) {
        if (entry.modifiedAtEpochSec <= 0L) ""
        else DateTimeFormatter.ofPattern("dd.MM HH:mm")
            .format(Instant.ofEpochSecond(entry.modifiedAtEpochSec).atZone(ZoneId.systemDefault()))
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Icon(
                    imageVector = if (selected) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
            }

            Icon(
                imageVector = when {
                    entry.isDirectory -> Icons.Rounded.Folder
                    entry.extension in setOf("kt", "py", "js", "ts", "tsx", "json", "yml", "yaml", "sh", "md", "html", "css") ->
                        Icons.Rounded.Code
                    else -> Icons.AutoMirrored.Rounded.InsertDriveFile
                },
                contentDescription = null,
                tint = when {
                    entry.isDirectory -> LocalStatusColors.current.tunnel
                    entry.isHidden -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(24.dp),
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sizeLabel = if (entry.isDirectory) "dir" else formatBytes(entry.sizeBytes)
                Text(
                    text = "${entry.rwxPermissions}  •  $sizeLabel  •  $formattedTime",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (!selectionMode) {
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.files_action_rename)) },
                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.files_action_copy)) },
                            leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onCopy()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.files_action_cut)) },
                            leadingIcon = { Icon(Icons.Rounded.ContentCut, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onCut()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.files_action_move)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onMove()
                            },
                        )
                        if (entry.isDirectory) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.files_action_set_workspace)) },
                                leadingIcon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onSetWorkspace()
                                },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_attach_from_files)) },
                                leadingIcon = { Icon(Icons.Rounded.AlternateEmail, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onAttachToChat()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.files_download)) },
                                leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onDownload()
                                },
                            )
                            if (hasLocalFolder) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.files_local_download_here)) },
                                    leadingIcon = { Icon(Icons.Rounded.PhoneAndroid, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        onDownloadToLocalPane()
                                    },
                                )
                            }
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.files_action_chmod)) },
                            leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onChmod()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.files_action_properties)) },
                            leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onProperties()
                            },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.files_action_delete),
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
}

@Composable
private fun LocalPhoneSafPane(
    folderName: String?,
    entries: List<LocalSafEntry>,
    isLoading: Boolean,
    onPickFolder: () -> Unit,
    onRefresh: () -> Unit,
    onUploadEntry: (LocalSafEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.PhoneAndroid,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.files_local_pane_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (folderName != null) {
                    Text(
                        text = folderName,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(onClick = onPickFolder) {
                Text(stringResource(R.string.files_local_pick_folder))
            }
            if (folderName != null) {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.action_refresh))
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        when {
            folderName == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.files_local_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = onPickFolder) {
                            Text(stringResource(R.string.files_local_pick_folder))
                        }
                    }
                }
            }

            isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(entries, key = { it.uri.toString() }) { item ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = if (item.isDirectory) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile,
                                    contentDescription = null,
                                    tint = if (item.isDirectory) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (!item.isDirectory) {
                                        Text(
                                            text = formatBytes(item.sizeBytes),
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (!item.isDirectory) {
                                    IconButton(
                                        onClick = { onUploadEntry(item) },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Upload,
                                            contentDescription = stringResource(R.string.files_local_upload_one),
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
