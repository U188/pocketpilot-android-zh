package com.example.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.domain.model.OpenCodePart
import com.example.domain.model.ToolCallStatus
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.MonoFontFamily

@Composable
fun ToolCallCard(
    part: OpenCodePart.Tool,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(part.id) { mutableStateOf(false) }
    val statusColors = LocalStatusColors.current

    val (statusLabel, statusColor) = when (part.status) {
        ToolCallStatus.RUNNING -> stringResource(R.string.chat_tool_running) to statusColors.warning
        ToolCallStatus.COMPLETED -> stringResource(R.string.chat_tool_completed) to statusColors.connected
        ToolCallStatus.ERROR -> stringResource(R.string.chat_tool_error) to MaterialTheme.colorScheme.error
        ToolCallStatus.PENDING -> stringResource(R.string.chat_tool_pending) to MaterialTheme.colorScheme.onSurfaceVariant
    }

    val toolIcon = when (part.toolName.lowercase()) {
        "bash", "shell", "command" -> Icons.Rounded.Terminal
        "read", "edit", "write", "patch" -> Icons.Rounded.Code
        else -> Icons.Rounded.Build
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f)),
        modifier = modifier.fillMaxWidth(),
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
                Icon(
                    imageVector = toolIcon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(18.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = part.toolName,
                        style = MaterialTheme.typography.labelLarge.copy(fontFamily = MonoFontFamily),
                        maxLines = 1,
                    )
                    part.inputSummary?.let { summary ->
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                when (part.status) {
                    ToolCallStatus.RUNNING -> CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = statusColor,
                    )

                    ToolCallStatus.COMPLETED -> Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = statusLabel,
                        tint = statusColor,
                        modifier = Modifier.size(16.dp),
                    )

                    ToolCallStatus.ERROR -> Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = statusLabel,
                        tint = statusColor,
                        modifier = Modifier.size(16.dp),
                    )

                    ToolCallStatus.PENDING -> Icon(
                        imageVector = Icons.Rounded.Schedule,
                        contentDescription = statusLabel,
                        tint = statusColor,
                        modifier = Modifier.size(16.dp),
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    part.rawInput?.takeIf { it.isNotBlank() }?.let { raw ->
                        Text(
                            text = stringResource(R.string.chat_tool_input),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        MonoCodeBox(text = raw)
                    }

                    part.output?.takeIf { it.isNotBlank() }?.let { output ->
                        Text(
                            text = stringResource(R.string.chat_tool_output),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        MonoCodeBox(text = output.take(3_000))
                    }

                    part.error?.takeIf { it.isNotBlank() }?.let { err ->
                        Text(
                            text = err,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    part.filePath?.takeIf { it.startsWith("/") }?.let { path ->
                        FilledTonalButton(
                            onClick = { onOpenFile(path) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
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

@Composable
fun ReasoningCard(
    part: OpenCodePart.Reasoning,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(part.id) { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.chat_reasoning_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Text(
                    text = part.text.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun MonoCodeBox(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = MonoFontFamily,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
        ),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .horizontalScroll(rememberScrollState())
            .padding(8.dp),
    )
}
