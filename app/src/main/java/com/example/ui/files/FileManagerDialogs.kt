package com.example.ui.files

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.RemoteFileEntry
import com.example.domain.model.TransferDirection
import com.example.domain.model.TransferStatus
import com.example.domain.model.TransferTask
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.MonoFontFamily
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TextInputFileDialog(
    title: String,
    label: String,
    initialValue: String,
    confirmText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("file_dialog_input"),
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(value.trim()) },
                enabled = value.trim().isNotEmpty(),
                modifier = Modifier.testTag("file_dialog_confirm"),
            ) {
                Text(confirmText)
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
fun DeleteFilesConfirmDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_delete_title, count)) },
        text = { Text(stringResource(R.string.files_delete_message)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.testTag("confirm_delete_files_button"),
            ) {
                Text(stringResource(R.string.files_action_delete))
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
fun ChmodDialog(
    entry: RemoteFileEntry,
    onApply: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var mask by remember(entry.path) { mutableIntStateOf(entry.permissionsMask and 0xFFF) }
    var octalText by remember(entry.path) { mutableStateOf("%04o".format(entry.permissionsMask and 0xFFF)) }

    fun toggleBit(bit: Int) {
        mask = mask xor bit
        octalText = "%04o".format(mask)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${stringResource(R.string.files_action_chmod)}: ${entry.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = octalText,
                    onValueChange = { raw ->
                        val filtered = raw.filter { it in '0'..'7' }.take(4)
                        octalText = filtered
                        filtered.toIntOrNull(8)?.let { mask = it and 0xFFF }
                    },
                    label = { Text(stringResource(R.string.files_chmod_octal)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = MonoFontFamily),
                    modifier = Modifier.fillMaxWidth(),
                )

                PermissionRow(
                    label = stringResource(R.string.files_chmod_owner),
                    r = (mask and 0x100) != 0,
                    w = (mask and 0x080) != 0,
                    x = (mask and 0x040) != 0,
                    onToggleR = { toggleBit(0x100) },
                    onToggleW = { toggleBit(0x080) },
                    onToggleX = { toggleBit(0x040) },
                )
                PermissionRow(
                    label = stringResource(R.string.files_chmod_group),
                    r = (mask and 0x020) != 0,
                    w = (mask and 0x010) != 0,
                    x = (mask and 0x008) != 0,
                    onToggleR = { toggleBit(0x020) },
                    onToggleW = { toggleBit(0x010) },
                    onToggleX = { toggleBit(0x008) },
                )
                PermissionRow(
                    label = stringResource(R.string.files_chmod_other),
                    r = (mask and 0x004) != 0,
                    w = (mask and 0x002) != 0,
                    x = (mask and 0x001) != 0,
                    onToggleR = { toggleBit(0x004) },
                    onToggleW = { toggleBit(0x002) },
                    onToggleX = { toggleBit(0x001) },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onApply(mask) },
                modifier = Modifier.testTag("chmod_apply_button"),
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
private fun PermissionRow(
    label: String,
    r: Boolean,
    w: Boolean,
    x: Boolean,
    onToggleR: () -> Unit,
    onToggleW: () -> Unit,
    onToggleX: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(86.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = r, onCheckedChange = { onToggleR() })
            Text("r", style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily))
            Checkbox(checked = w, onCheckedChange = { onToggleW() })
            Text("w", style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily))
            Checkbox(checked = x, onCheckedChange = { onToggleX() })
            Text("x", style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily))
        }
    }
}

@Composable
fun FilePropertiesDialog(
    entry: RemoteFileEntry,
    onDismiss: () -> Unit,
) {
    val formattedDate = remember(entry.modifiedAtEpochSec) {
        if (entry.modifiedAtEpochSec <= 0L) "—"
        else DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
            .format(Instant.ofEpochSecond(entry.modifiedAtEpochSec).atZone(ZoneId.systemDefault()))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PropertyLine(stringResource(R.string.files_properties_path), entry.path)
                PropertyLine(stringResource(R.string.files_properties_size), formatBytes(entry.sizeBytes))
                PropertyLine(stringResource(R.string.files_properties_modified), formattedDate)
                PropertyLine(
                    stringResource(R.string.files_properties_perms),
                    "${entry.rwxPermissions} (${entry.octalPermissions})",
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

@Composable
private fun PropertyLine(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderTreeBottomSheet(
    homePath: String,
    currentPath: String,
    treeChildren: Map<String, List<RemoteFileEntry>>,
    expandedPaths: Set<String>,
    loadingPaths: Set<String>,
    onToggleExpand: (String) -> Unit,
    onLoadNode: (String) -> Unit,
    onSelectPath: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(homePath) {
        if ("/" !in treeChildren) onLoadNode("/")
        if (homePath != "/" && homePath !in treeChildren) onLoadNode(homePath)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.files_tree_title),
                style = MaterialTheme.typography.titleLarge,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = {
                        onSelectPath(homePath)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Home, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.files_tree_home))
                }
                FilledTonalButton(
                    onClick = {
                        onSelectPath("/")
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.files_tree_root))
                }
            }

            HorizontalDivider()

            val flattened = remember(treeChildren, expandedPaths, homePath) {
                buildList {
                    val startRoot = if (homePath != "/") homePath else "/"
                    fun visit(path: String, label: String, depth: Int) {
                        add(Triple(path, label, depth))
                        if (path in expandedPaths && depth < 5) {
                            treeChildren[path].orEmpty().forEach { child ->
                                visit(child.path, child.name, depth + 1)
                            }
                        }
                    }
                    visit(startRoot, startRoot, 0)
                    if (startRoot != "/") {
                        visit("/", "/", 0)
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
            ) {
                items(flattened, key = { "${it.first}_${it.third}" }) { (path, label, depth) ->
                    val expanded = path in expandedPaths
                    val loading = path in loadingPaths
                    val isCurrent = path == currentPath

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectPath(path)
                                onDismiss()
                            }
                            .padding(start = (depth * 18).dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { onToggleExpand(path) },
                            modifier = Modifier.size(36.dp),
                        ) {
                            if (loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.Rounded.Folder,
                            contentDescription = null,
                            tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferQueueBottomSheet(
    tasks: List<TransferTask>,
    onCancelTask: (String) -> Unit,
    onClearFinished: () -> Unit,
    onDismiss: () -> Unit,
) {
    val statusColors = LocalStatusColors.current

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.files_transfers_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (tasks.isNotEmpty()) {
                    TextButton(onClick = onClearFinished) {
                        Text(stringResource(R.string.files_transfers_clear))
                    }
                }
            }

            if (tasks.isEmpty()) {
                Text(
                    text = stringResource(R.string.files_transfers_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(tasks, key = { it.id }) { task ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = if (task.direction == TransferDirection.UPLOAD) {
                                            Icons.Rounded.Upload
                                        } else {
                                            Icons.Rounded.Download
                                        },
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = task.fileName,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = task.remotePath,
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    when (task.status) {
                                        TransferStatus.QUEUED,
                                        TransferStatus.IN_PROGRESS -> {
                                            IconButton(
                                                onClick = { onCancelTask(task.id) },
                                                modifier = Modifier.size(36.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Cancel,
                                                    contentDescription = stringResource(R.string.action_cancel),
                                                    tint = MaterialTheme.colorScheme.error,
                                                )
                                            }
                                        }

                                        TransferStatus.COMPLETED -> Icon(
                                            imageVector = Icons.Rounded.CheckCircle,
                                            contentDescription = null,
                                            tint = statusColors.connected,
                                            modifier = Modifier.size(20.dp),
                                        )

                                        TransferStatus.FAILED -> Icon(
                                            imageVector = Icons.Rounded.ErrorOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp),
                                        )

                                        TransferStatus.CANCELLED -> Text(
                                            text = stringResource(R.string.files_transfer_cancelled),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }

                                if (task.status == TransferStatus.IN_PROGRESS) {
                                    val fraction = task.progressFraction
                                    if (fraction != null) {
                                        LinearProgressIndicator(
                                            progress = { fraction },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    } else {
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    }
                                    Text(
                                        text = if (task.totalBytes > 0L) {
                                            "${formatBytes(task.bytesTransferred)} / ${formatBytes(task.totalBytes)}"
                                        } else {
                                            formatBytes(task.bytesTransferred)
                                        },
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else if (task.status == TransferStatus.FAILED && !task.errorMessage.isNullOrBlank()) {
                                    Text(
                                        text = task.errorMessage,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
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

fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes Б"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "%.1f КБ".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return "%.1f МБ".format(mb)
    val gb = mb / 1024.0
    return "%.2f ГБ".format(gb)
}
