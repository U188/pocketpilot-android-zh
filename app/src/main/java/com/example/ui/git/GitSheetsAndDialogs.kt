package com.example.ui.git

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Commit
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.domain.model.DiffLine
import com.example.domain.model.DiffLineType
import com.example.domain.model.GitBranchEntry
import com.example.domain.model.GitCommitEntry
import com.example.domain.model.GitFileChange
import com.example.domain.model.GitFileStatus
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.MonoFontFamily

@Composable
fun GitDiscardConfirmDialog(
    change: GitFileChange,
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
        title = { Text(stringResource(R.string.git_discard_dialog_title)) },
        text = {
            Text(
                text = if (change.status == GitFileStatus.UNTRACKED) {
                    stringResource(R.string.git_discard_untracked_desc, change.path)
                } else {
                    stringResource(R.string.git_discard_tracked_desc, change.path)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.testTag("git_discard_confirm_button"),
            ) {
                Text(stringResource(R.string.git_discard_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitDiffPreviewSheet(
    state: GitDiffPreviewState,
    onOpenInEditor: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontFamily = MonoFontFamily),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onOpenInEditor != null) {
                    FilledTonalButton(
                        onClick = onOpenInEditor,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("git_diff_open_editor_button"),
                    ) {
                        Icon(Icons.Rounded.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.git_diff_open_editor),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                state.error != null -> {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                state.lines.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.git_diff_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    val hScroll = rememberScrollState()
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .horizontalScroll(hScroll),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(state.lines) { line ->
                            GitDiffCodeRow(line = line)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun GitDiffCodeRow(line: DiffLine) {
    val statusColors = LocalStatusColors.current
    val (bg, fg, prefix) = when (line.type) {
        DiffLineType.ADDED -> Triple(
            statusColors.connected.copy(alpha = 0.16f),
            statusColors.connected,
            "+",
        )
        DiffLineType.REMOVED -> Triple(
            MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
            MaterialTheme.colorScheme.error,
            "-",
        )
        DiffLineType.HUNK_HEADER -> Triple(
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
            MaterialTheme.colorScheme.secondary,
            "",
        )
        DiffLineType.CONTEXT -> Triple(
            Color.Transparent,
            MaterialTheme.colorScheme.onSurface,
            " ",
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 1.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val oldNum = line.oldLineNumber?.toString()?.padStart(4) ?: "    "
        val newNum = line.newLineNumber?.toString()?.padStart(4) ?: "    "
        if (line.type != DiffLineType.HUNK_HEADER) {
            Text(
                text = "$oldNum $newNum ",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = MonoFontFamily,
                    fontSize = 11.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            )
        }
        Text(
            text = "$prefix${line.content}",
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = MonoFontFamily,
                fontSize = 12.sp,
                fontWeight = if (line.type == DiffLineType.HUNK_HEADER) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = fg,
            softWrap = false,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitBranchesAndHistorySheet(
    branches: List<GitBranchEntry>,
    commits: List<GitCommitEntry>,
    isLoading: Boolean,
    isBusy: Boolean,
    onSwitchBranch: (GitBranchEntry) -> Unit,
    onCreateBranch: (String) -> Unit,
    onSelectCommit: (GitCommitEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by remember { mutableIntStateOf(0) }
    var newBranchName by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.git_branches_and_history),
                style = MaterialTheme.typography.titleMedium,
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) {
                    Text("${stringResource(R.string.git_tab_branches)} (${branches.size})")
                }
                SegmentedButton(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) {
                    Text("${stringResource(R.string.git_tab_history)} (${commits.size})")
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (selectedTab == 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = newBranchName,
                        onValueChange = { newBranchName = it },
                        placeholder = { Text(stringResource(R.string.git_new_branch_placeholder)) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("git_new_branch_input"),
                    )
                    Button(
                        onClick = {
                            val name = newBranchName.trim()
                            if (name.isNotEmpty()) {
                                onCreateBranch(name)
                                newBranchName = ""
                            }
                        },
                        enabled = !isBusy && newBranchName.isNotBlank(),
                        modifier = Modifier.testTag("git_create_branch_button"),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.git_create_branch_button))
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(branches, key = { "${if (it.isRemote) "R" else "L"}:${it.name}" }) { branch ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (branch.isCurrent) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            border = BorderStroke(
                                1.dp,
                                if (branch.isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = !isBusy && !branch.isCurrent) {
                                    onSwitchBranch(branch)
                                },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = if (branch.isRemote) Icons.Rounded.Cloud else Icons.AutoMirrored.Rounded.CallSplit,
                                    contentDescription = null,
                                    tint = if (branch.isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = branch.name,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = MonoFontFamily,
                                            fontWeight = if (branch.isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val sub = when {
                                        branch.isCurrent && branch.upstream != null ->
                                            "${stringResource(R.string.git_branch_current)} • ${branch.upstream}"
                                        branch.isCurrent -> stringResource(R.string.git_branch_current)
                                        branch.isRemote -> stringResource(R.string.git_branch_remote)
                                        branch.upstream != null -> branch.upstream
                                        else -> null
                                    }
                                    if (sub != null) {
                                        Text(
                                            text = sub,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                if (branch.isCurrent) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                if (commits.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.git_history_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(commits, key = { it.fullHash }) { commit ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onSelectCommit(commit) },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Commit,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .padding(top = 2.dp)
                                            .size(18.dp),
                                    )
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Text(
                                            text = commit.subject,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = commit.shortHash,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontFamily = MonoFontFamily,
                                                    fontWeight = FontWeight.SemiBold,
                                                ),
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                            Text(
                                                text = "${commit.author} • ${commit.relativeTime}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
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
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
