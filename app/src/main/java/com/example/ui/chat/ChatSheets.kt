package com.example.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.sftp.SftpRepository
import com.example.domain.model.OpenCodeAgent
import com.example.domain.model.OpenCodeModel
import com.example.domain.model.OpenCodeProvider
import com.example.domain.model.RemoteFileEntry
import com.example.ui.theme.MonoFontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerBottomSheet(
    providers: List<OpenCodeProvider>,
    selectedProviderId: String,
    selectedModelId: String,
    onSelect: (OpenCodeModel) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_select_model),
                style = MaterialTheme.typography.titleLarge,
            )

            if (providers.isEmpty()) {
                Text(
                    text = stringResource(R.string.chat_models_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    providers.forEach { provider ->
                        item(key = "provider_${provider.id}") {
                            Text(
                                text = provider.name,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        items(provider.models, key = { "${it.providerId}_${it.modelId}" }) { model ->
                            val isSelected = model.providerId == selectedProviderId &&
                                model.modelId == selectedModelId
                            Surface(
                                onClick = {
                                    onSelect(model)
                                    onDismiss()
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainer
                                },
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = model.displayName,
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        Text(
                                            text = "${model.providerId}/${model.modelId}",
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentPickerBottomSheet(
    agents: List<OpenCodeAgent>,
    selectedAgentName: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val list = agents.ifEmpty {
        listOf(
            OpenCodeAgent("build", "Полный доступ к правке файлов и командам"),
            OpenCodeAgent("plan", "Анализ и планирование без изменения файлов"),
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_select_agent),
                style = MaterialTheme.typography.titleLarge,
            )

            list.forEach { agent ->
                val isSelected = agent.name.equals(selectedAgentName, ignoreCase = true)
                Surface(
                    onClick = {
                        onSelect(agent.name)
                        onDismiss()
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SmartToy,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = agent.name,
                                style = MaterialTheme.typography.titleSmall.copy(fontFamily = MonoFontFamily),
                            )
                            if (agent.description.isNotBlank()) {
                                Text(
                                    text = agent.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerFilePickerBottomSheet(
    currentPath: String,
    entries: List<RemoteFileEntry>,
    isLoading: Boolean,
    onNavigate: (String) -> Unit,
    onAttachFile: (RemoteFileEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_file_picker_title),
                style = MaterialTheme.typography.titleLarge,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (currentPath != "/") {
                    IconButton(
                        onClick = { onNavigate(SftpRepository.parentRemotePath(currentPath)) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                }
                Text(
                    text = currentPath,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider()

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                ) {
                    items(entries, key = { it.path }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (entry.isDirectory) {
                                        onNavigate(entry.path)
                                    } else {
                                        onAttachFile(entry)
                                        onDismiss()
                                    }
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (entry.isDirectory) {
                                    Icons.Rounded.Folder
                                } else {
                                    Icons.AutoMirrored.Rounded.InsertDriveFile
                                },
                                contentDescription = null,
                                tint = if (entry.isDirectory) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = entry.name,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun RenameSessionDialog(
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(initialTitle) { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_session_rename)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.chat_session_rename_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(title.trim()) },
                enabled = title.trim().isNotEmpty(),
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
fun DeleteSessionDialog(
    sessionTitle: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_session_delete_title)) },
        text = { Text(stringResource(R.string.chat_session_delete_message, sessionTitle)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.chat_session_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
fun RevertSessionDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_revert_title)) },
        text = { Text(stringResource(R.string.chat_revert_message)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
