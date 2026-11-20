package com.example.ui.git

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Commit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.GitFileChange
import com.example.domain.model.GitFileStatus
import com.example.domain.model.GitRepositorySummary
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.MonoFontFamily

@Composable
fun GitPanelView(
    uiState: GitUiState,
    onRefresh: () -> Unit,
    onInitRepo: () -> Unit,
    onFetch: () -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit,
    onStageFile: (GitFileChange) -> Unit,
    onUnstageFile: (GitFileChange) -> Unit,
    onStageAll: () -> Unit,
    onUnstageAll: () -> Unit,
    onDiscardFile: (GitFileChange) -> Unit,
    onOpenFileDiff: (GitFileChange) -> Unit,
    onCloseDiff: () -> Unit,
    onCommitMessageChange: (String) -> Unit,
    onPrependCommitPrefix: (String) -> Unit,
    onToggleAmend: () -> Unit,
    onCommit: (andPush: Boolean) -> Unit,
    onLoadBranchesAndLog: () -> Unit,
    onSwitchBranch: (com.example.domain.model.GitBranchEntry) -> Unit,
    onCreateBranch: (String) -> Unit,
    onOpenCommitDiff: (com.example.domain.model.GitCommitEntry) -> Unit,
    onOpenCodeEditor: (remotePath: String, line: Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDiscard by remember { mutableStateOf<GitFileChange?>(null) }
    var showBranchesSheet by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        if (uiState.isLoading || uiState.isBusy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        uiState.activeActionLabel?.let { label ->
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }

        when {
            uiState.isLoading && !uiState.status.summary.isGitRepo && uiState.errorMessage == null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            !uiState.status.summary.isGitRepo -> {
                NotGitRepoEmptyState(
                    directory = uiState.targetDirectory,
                    errorMessage = uiState.errorMessage,
                    isBusy = uiState.isBusy,
                    onInitRepo = onInitRepo,
                    onRefresh = onRefresh,
                )
            }

            else -> {
                val status = uiState.status
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("git_panel_list"),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Repository overview card
                    item(key = "repo_header") {
                        GitRepoOverviewCard(
                            summary = status.summary,
                            isBusy = uiState.isBusy,
                            onOpenBranches = {
                                showBranchesSheet = true
                                onLoadBranchesAndLog()
                            },
                            onFetch = onFetch,
                            onPull = onPull,
                            onPush = onPush,
                            onRefresh = onRefresh,
                        )
                    }

                    // Error banner if any
                    uiState.errorMessage?.let { err ->
                        item(key = "git_error") {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                    }

                    // Commit composer card (when there are changes or amend is active)
                    if (status.totalChangesCount > 0 || uiState.amendLastCommit) {
                        item(key = "commit_composer") {
                            GitCommitComposerCard(
                                message = uiState.commitMessage,
                                amend = uiState.amendLastCommit,
                                stagedCount = status.stagedFiles.size,
                                totalChangesCount = status.totalChangesCount,
                                isBusy = uiState.isBusy,
                                onMessageChange = onCommitMessageChange,
                                onPrependPrefix = onPrependCommitPrefix,
                                onToggleAmend = onToggleAmend,
                                onCommit = { onCommit(false) },
                                onCommitAndPush = { onCommit(true) },
                            )
                        }
                    }

                    if (status.totalChangesCount == 0) {
                        item(key = "clean_state") {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("git_clean_card"),
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = LocalStatusColors.current.connected,
                                        modifier = Modifier.size(34.dp),
                                    )
                                    Text(
                                        text = stringResource(R.string.git_clean_title),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        text = stringResource(R.string.git_clean_desc, status.summary.repoRootPath),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    OutlinedButton(
                                        onClick = {
                                            showBranchesSheet = true
                                            onLoadBranchesAndLog()
                                        },
                                    ) {
                                        Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.git_branches_and_history))
                                    }
                                }
                            }
                        }
                    }

                    // Staged section
                    if (status.stagedFiles.isNotEmpty()) {
                        item(key = "header_staged") {
                            GitChangeSectionHeader(
                                title = stringResource(R.string.git_section_staged, status.stagedFiles.size),
                                accentColor = LocalStatusColors.current.connected,
                                actionLabel = stringResource(R.string.git_unstage_all),
                                enabled = !uiState.isBusy,
                                onAction = onUnstageAll,
                            )
                        }
                        items(status.stagedFiles, key = { it.stableKey }) { change ->
                            GitFileChangeRow(
                                change = change,
                                isBusy = uiState.isBusy,
                                onOpenDiff = { onOpenFileDiff(change) },
                                onToggleStage = { onUnstageFile(change) },
                                onDiscard = { pendingDiscard = change },
                            )
                        }
                    }

                    // Unstaged section
                    if (status.unstagedFiles.isNotEmpty()) {
                        item(key = "header_unstaged") {
                            GitChangeSectionHeader(
                                title = stringResource(R.string.git_section_unstaged, status.unstagedFiles.size),
                                accentColor = LocalStatusColors.current.warning,
                                actionLabel = stringResource(R.string.git_stage_all),
                                enabled = !uiState.isBusy,
                                onAction = onStageAll,
                            )
                        }
                        items(status.unstagedFiles, key = { it.stableKey }) { change ->
                            GitFileChangeRow(
                                change = change,
                                isBusy = uiState.isBusy,
                                onOpenDiff = { onOpenFileDiff(change) },
                                onToggleStage = { onStageFile(change) },
                                onDiscard = { pendingDiscard = change },
                            )
                        }
                    }

                    // Untracked section
                    if (status.untrackedFiles.isNotEmpty()) {
                        item(key = "header_untracked") {
                            GitChangeSectionHeader(
                                title = stringResource(R.string.git_section_untracked, status.untrackedFiles.size),
                                accentColor = LocalStatusColors.current.tunnel,
                                actionLabel = if (status.unstagedFiles.isEmpty()) stringResource(R.string.git_stage_all) else null,
                                enabled = !uiState.isBusy,
                                onAction = onStageAll,
                            )
                        }
                        items(status.untrackedFiles, key = { it.stableKey }) { change ->
                            GitFileChangeRow(
                                change = change,
                                isBusy = uiState.isBusy,
                                onOpenDiff = { onOpenFileDiff(change) },
                                onToggleStage = { onStageFile(change) },
                                onDiscard = { pendingDiscard = change },
                            )
                        }
                    }

                    item(key = "bottom_spacer") { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
    }

    pendingDiscard?.let { target ->
        GitDiscardConfirmDialog(
            change = target,
            onConfirm = {
                pendingDiscard = null
                onDiscardFile(target)
            },
            onDismiss = { pendingDiscard = null },
        )
    }

    if (showBranchesSheet) {
        GitBranchesAndHistorySheet(
            branches = uiState.branches,
            commits = uiState.recentCommits,
            isLoading = uiState.isLoadingMeta,
            isBusy = uiState.isBusy,
            onSwitchBranch = { branch ->
                onSwitchBranch(branch)
            },
            onCreateBranch = onCreateBranch,
            onSelectCommit = { commit ->
                showBranchesSheet = false
                onOpenCommitDiff(commit)
            },
            onDismiss = { showBranchesSheet = false },
        )
    }

    uiState.diffPreview?.let { diffState ->
        GitDiffPreviewSheet(
            state = diffState,
            onOpenInEditor = diffState.absoluteRemotePath?.let { path ->
                {
                    onCloseDiff()
                    onOpenCodeEditor(path, null)
                }
            },
            onDismiss = onCloseDiff,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GitRepoOverviewCard(
    summary: GitRepositorySummary,
    isBusy: Boolean,
    onOpenBranches: () -> Unit,
    onFetch: () -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit,
    onRefresh: () -> Unit,
) {
    val statusColors = LocalStatusColors.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("git_repo_overview_card"),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onOpenBranches)
                        .testTag("git_branch_selector_pill"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.CallSplit,
                            contentDescription = stringResource(R.string.git_branch_label),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = summary.branchName.ifBlank { "HEAD" },
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontFamily = MonoFontFamily,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (summary.aheadCount > 0 || summary.behindCount > 0) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (summary.aheadCount > 0) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = statusColors.connected.copy(alpha = 0.16f),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Rounded.ArrowUpward,
                                        contentDescription = null,
                                        tint = statusColors.connected,
                                        modifier = Modifier.size(13.dp),
                                    )
                                    Text(
                                        text = "${summary.aheadCount}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                        color = statusColors.connected,
                                    )
                                }
                            }
                        }
                        if (summary.behindCount > 0) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = statusColors.warning.copy(alpha = 0.16f),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Rounded.ArrowDownward,
                                        contentDescription = null,
                                        tint = statusColors.warning,
                                        modifier = Modifier.size(13.dp),
                                    )
                                    Text(
                                        text = "${summary.behindCount}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                        color = statusColors.warning,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                IconButton(
                    onClick = onOpenBranches,
                    modifier = Modifier.testTag("git_history_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.History,
                        contentDescription = stringResource(R.string.git_branches_and_history),
                    )
                }

                IconButton(
                    onClick = onRefresh,
                    enabled = !isBusy,
                    modifier = Modifier.testTag("git_refresh_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.action_refresh),
                    )
                }
            }

            Text(
                text = buildString {
                    append(summary.repoRootPath)
                    summary.upstreamName?.let { append("  •  $it") }
                },
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onFetch,
                    enabled = !isBusy,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("git_fetch_button"),
                ) {
                    Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.git_action_fetch), style = MaterialTheme.typography.labelMedium)
                }
                FilledTonalButton(
                    onClick = onPull,
                    enabled = !isBusy,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("git_pull_button"),
                ) {
                    Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.git_action_pull), style = MaterialTheme.typography.labelMedium)
                }
                Button(
                    onClick = onPush,
                    enabled = !isBusy,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("git_push_button"),
                ) {
                    Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.git_action_push), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun GitCommitComposerCard(
    message: String,
    amend: Boolean,
    stagedCount: Int,
    totalChangesCount: Int,
    isBusy: Boolean,
    onMessageChange: (String) -> Unit,
    onPrependPrefix: (String) -> Unit,
    onToggleAmend: () -> Unit,
    onCommit: () -> Unit,
    onCommitAndPush: () -> Unit,
) {
    val prefixes = remember { listOf("feat:", "fix:", "refactor:", "chore:") }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("git_commit_card"),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(prefixes) { prefix ->
                    AssistChip(
                        onClick = { onPrependPrefix(prefix) },
                        label = {
                            Text(
                                text = prefix,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                            )
                        },
                    )
                }
                item {
                    FilterChip(
                        selected = amend,
                        onClick = onToggleAmend,
                        label = {
                            Text(
                                text = stringResource(R.string.git_commit_amend),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
            }

            OutlinedTextField(
                value = message,
                onValueChange = onMessageChange,
                placeholder = { Text(stringResource(R.string.git_commit_placeholder)) },
                maxLines = 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("git_commit_message_input"),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val canCommit = !isBusy && message.isNotBlank() && (stagedCount > 0 || totalChangesCount > 0 || amend)
                FilledTonalButton(
                    onClick = onCommit,
                    enabled = canCommit,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("git_commit_button"),
                ) {
                    Icon(Icons.Rounded.Commit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (stagedCount > 0) {
                            "${stringResource(R.string.git_commit_button)} ($stagedCount)"
                        } else {
                            stringResource(R.string.git_commit_button)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(
                    onClick = onCommitAndPush,
                    enabled = canCommit,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("git_commit_push_button"),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.git_commit_and_push_button),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun GitChangeSectionHeader(
    title: String,
    accentColor: Color,
    actionLabel: String?,
    enabled: Boolean,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(accentColor),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null) {
            TextButton(
                onClick = onAction,
                enabled = enabled,
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun GitFileChangeRow(
    change: GitFileChange,
    isBusy: Boolean,
    onOpenDiff: () -> Unit,
    onToggleStage: () -> Unit,
    onDiscard: () -> Unit,
) {
    val statusColors = LocalStatusColors.current
    val badgeColor = when (change.status) {
        GitFileStatus.ADDED -> statusColors.connected
        GitFileStatus.DELETED -> MaterialTheme.colorScheme.error
        GitFileStatus.MODIFIED -> statusColors.warning
        GitFileStatus.RENAMED, GitFileStatus.COPIED -> statusColors.tunnel
        GitFileStatus.UNMERGED -> MaterialTheme.colorScheme.error
        GitFileStatus.UNTRACKED -> statusColors.tunnel
    }

    val fileName = change.path.substringAfterLast('/')
    val parentDir = change.path.substringBeforeLast('/', "").takeIf { it.isNotBlank() }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpenDiff),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = badgeColor.copy(alpha = 0.16f),
            ) {
                Text(
                    text = change.status.shortCode,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = MonoFontFamily,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = badgeColor,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (parentDir != null) {
                    Text(
                        text = parentDir,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            IconButton(
                onClick = onDiscard,
                enabled = !isBusy,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Restore,
                    contentDescription = stringResource(R.string.git_discard_cd),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp),
                )
            }

            IconButton(
                onClick = onToggleStage,
                enabled = !isBusy,
            ) {
                Icon(
                    imageVector = if (change.isStaged) Icons.Rounded.Remove else Icons.Rounded.Add,
                    contentDescription = stringResource(
                        if (change.isStaged) R.string.git_unstage_cd else R.string.git_stage_cd,
                    ),
                    tint = if (change.isStaged) statusColors.warning else statusColors.connected,
                )
            }
        }
    }
}

@Composable
private fun NotGitRepoEmptyState(
    directory: String,
    errorMessage: String?,
    isBusy: Boolean,
    onInitRepo: () -> Unit,
    onRefresh: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("git_not_repo_card"),
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.CallSplit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
                Text(
                    text = stringResource(R.string.git_not_repo_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = errorMessage ?: stringResource(R.string.git_not_repo_desc, directory.ifBlank { "/" }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onInitRepo,
                        enabled = !isBusy && directory.isNotBlank(),
                        modifier = Modifier.testTag("git_init_button"),
                    ) {
                        Text(stringResource(R.string.git_init_button))
                    }
                    OutlinedButton(onClick = onRefresh, enabled = !isBusy) {
                        Text(stringResource(R.string.action_refresh))
                    }
                }
            }
        }
    }
}
