package com.example.ui.chat

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Difference
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.example.domain.model.OpenCodeFileDiff
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.MonoFontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDiffSheet(
    diffs: List<OpenCodeFileDiff>,
    isLoading: Boolean,
    focusedFilePath: String?,
    onRefresh: () -> Unit,
    onOpenInEditor: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val totalAdditions = diffs.sumOf { it.additions }
    val totalDeletions = diffs.sumOf { it.deletions }
    val statusColors = LocalStatusColors.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("session_diff_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 260.dp, max = 640.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Difference,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.diff_sheet_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (diffs.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.diff_files_changed_count, diffs.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "+$totalAdditions",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = MonoFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = statusColors.connected,
                            )
                            Text(
                                text = "-$totalDeletions",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = MonoFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.testTag("diff_sheet_refresh_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.diff_sheet_refresh),
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            when {
                isLoading && diffs.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = stringResource(R.string.diff_sheet_loading),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                diffs.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.diff_sheet_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    val orderedDiffs = if (focusedFilePath != null) {
                        diffs.sortedByDescending { it.file == focusedFilePath || it.file.endsWith(focusedFilePath) }
                    } else {
                        diffs
                    }
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 8.dp),
                    ) {
                        items(orderedDiffs, key = { it.file }) { diff ->
                            val shouldStartExpanded = focusedFilePath != null &&
                                (diff.file == focusedFilePath || diff.file.endsWith(focusedFilePath)) ||
                                orderedDiffs.size == 1
                            FileDiffCard(
                                diff = diff,
                                initialExpanded = shouldStartExpanded,
                                onOpenInEditor = { onOpenInEditor(diff.file) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileDiffCard(
    diff: OpenCodeFileDiff,
    initialExpanded: Boolean,
    onOpenInEditor: () -> Unit,
) {
    var expanded by rememberSaveable(diff.file) { mutableStateOf(initialExpanded) }
    val statusColors = LocalStatusColors.current

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = diff.file.substringAfterLast('/'),
                        style = MaterialTheme.typography.titleSmall.copy(fontFamily = MonoFontFamily),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (diff.file.contains('/')) {
                        Text(
                            text = diff.file,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (diff.additions > 0) {
                    Text(
                        text = "+${diff.additions}",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = MonoFontFamily,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = statusColors.connected,
                    )
                }
                if (diff.deletions > 0) {
                    Text(
                        text = "-${diff.deletions}",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = MonoFontFamily,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    if (diff.lines.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 6.dp),
                        ) {
                            Column {
                                diff.lines.take(400).forEach { line ->
                                    DiffLineRow(line = line)
                                }
                            }
                        }
                    }

                    if (diff.file.startsWith("/")) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = onOpenInEditor) {
                                Icon(
                                    imageVector = Icons.Rounded.Code,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.chat_tool_open_file))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffLineRow(line: DiffLine) {
    val statusColors = LocalStatusColors.current
    val bgColor = when (line.type) {
        DiffLineType.ADDED -> statusColors.connected.copy(alpha = 0.16f)
        DiffLineType.REMOVED -> MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
        DiffLineType.HUNK_HEADER -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        DiffLineType.CONTEXT -> Color.Transparent
    }
    val textColor = when (line.type) {
        DiffLineType.ADDED -> statusColors.connected
        DiffLineType.REMOVED -> MaterialTheme.colorScheme.error
        DiffLineType.HUNK_HEADER -> MaterialTheme.colorScheme.primary
        DiffLineType.CONTEXT -> MaterialTheme.colorScheme.onSurface
    }
    val prefix = when (line.type) {
        DiffLineType.ADDED -> "+"
        DiffLineType.REMOVED -> "-"
        DiffLineType.HUNK_HEADER -> ""
        DiffLineType.CONTEXT -> " "
    }

    Row(
        modifier = Modifier
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (line.type != DiffLineType.HUNK_HEADER) {
            Text(
                text = (line.oldLineNumber?.toString() ?: "").padStart(4, ' '),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = MonoFontFamily,
                    fontSize = 11.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = (line.newLineNumber?.toString() ?: "").padStart(4, ' '),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = MonoFontFamily,
                    fontSize = 11.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = "$prefix${line.content}",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = MonoFontFamily,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
            ),
            color = textColor,
            softWrap = false,
        )
    }
}
