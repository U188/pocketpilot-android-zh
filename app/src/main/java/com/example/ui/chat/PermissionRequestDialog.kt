package com.example.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.domain.model.OpenCodePermissionRequest
import com.example.domain.model.PermissionReplyOption
import com.example.ui.components.StatusChip
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.MonoFontFamily

@Composable
fun PermissionRequestDialog(
    request: OpenCodePermissionRequest,
    isSubmitting: Boolean,
    onReply: (PermissionReplyOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val statusColors = LocalStatusColors.current
    val accentColor = if (request.isHighRisk) MaterialTheme.colorScheme.error else statusColors.warning

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        icon = {
            Icon(
                imageVector = if (request.isHighRisk) Icons.Rounded.GppMaybe else Icons.Rounded.Shield,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(28.dp),
            )
        },
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.permission_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (request.isHighRisk) {
                    StatusChip(
                        label = stringResource(R.string.permission_dialog_dangerous_badge),
                        accentColor = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.permission_dialog_type_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = request.permission,
                            style = MaterialTheme.typography.titleSmall.copy(fontFamily = MonoFontFamily),
                            color = accentColor,
                        )
                        if (request.title.isNotBlank() && request.title != request.permission) {
                            Text(
                                text = request.title,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                request.command?.takeIf { it.isNotBlank() }?.let { cmd ->
                    LabeledMonoBlock(
                        label = stringResource(R.string.permission_dialog_command_label),
                        content = cmd,
                    )
                }

                val pathText = request.filePath
                    ?: request.patterns.takeIf { it.isNotEmpty() }?.joinToString("\n")
                if (!pathText.isNullOrBlank()) {
                    LabeledMonoBlock(
                        label = stringResource(R.string.permission_dialog_path_label),
                        content = pathText,
                    )
                }

                request.details?.takeIf { it.isNotBlank() && request.command == null }?.let { details ->
                    LabeledMonoBlock(
                        label = stringResource(R.string.permission_dialog_details_label),
                        content = details,
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { onReply(PermissionReplyOption.ONCE) },
                        enabled = !isSubmitting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("permission_allow_once_button"),
                    ) {
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.permission_allow_once))
                    }

                    FilledTonalButton(
                        onClick = { onReply(PermissionReplyOption.ALWAYS) },
                        enabled = !isSubmitting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("permission_allow_always_button"),
                    ) {
                        Icon(Icons.Rounded.DoneAll, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.permission_allow_always))
                    }

                    OutlinedButton(
                        onClick = { onReply(PermissionReplyOption.REJECT) },
                        enabled = !isSubmitting,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("permission_reject_button"),
                    ) {
                        Icon(Icons.Rounded.Block, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.permission_reject))
                    }
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun LabeledMonoBlock(
    label: String,
    content: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = content,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = MonoFontFamily,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .horizontalScroll(rememberScrollState())
                .padding(10.dp),
        )
    }
}
