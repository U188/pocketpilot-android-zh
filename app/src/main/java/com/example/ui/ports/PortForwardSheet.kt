package com.example.ui.ports

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.DiscoveredRemotePort
import com.example.domain.model.PortForwardRule
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.MonoFontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortForwardSheet(
    viewModel: PortForwardViewModel,
    onDismiss: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) {
        viewModel.onSheetOpened()
    }

    LaunchedEffect(Unit) {
        viewModel.toastMessages.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("port_forward_sheet"),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header
            item(key = "header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.SwapHoriz,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                        Column {
                            Text(
                                text = stringResource(R.string.ports_sheet_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(R.string.ports_sheet_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    IconButton(
                        onClick = viewModel::refreshDiscoveredPorts,
                        enabled = !uiState.isDiscovering,
                        modifier = Modifier.testTag("ports_refresh_button"),
                    ) {
                        if (uiState.isDiscovering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.action_refresh),
                            )
                        }
                    }
                }
            }

            // Active Tunnels Section
            item(key = "active_header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.ports_active_section, uiState.activeCount),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (uiState.rules.isNotEmpty()) {
                        TextButton(
                            onClick = viewModel::stopAllForwards,
                            modifier = Modifier.testTag("ports_stop_all_button"),
                        ) {
                            Text(
                                text = stringResource(R.string.ports_stop_all),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            if (uiState.rules.isEmpty()) {
                item(key = "active_empty") {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.ports_active_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            } else {
                items(uiState.rules, key = { "rule_${it.id}" }) { rule ->
                    ActiveTunnelCard(
                        rule = rule,
                        onOpenBrowser = { openUrlInBrowser(context, rule.localUrl) },
                        onCopyUrl = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("port_forward_url", rule.localUrl))
                            Toast.makeText(
                                context,
                                context.getString(R.string.ports_url_copied, rule.localUrl),
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                        onStop = { viewModel.stopForward(rule.id) },
                    )
                }
            }

            // Manual Add Forward Form
            item(key = "manual_form") {
                ManualPortForwardCard(
                    uiState = uiState,
                    onRemotePortChange = viewModel::onFormRemotePortChange,
                    onLabelChange = viewModel::onFormLabelChange,
                    onRemoteHostChange = viewModel::onFormRemoteHostChange,
                    onLocalPortChange = viewModel::onFormLocalPortChange,
                    onToggleAdvanced = viewModel::toggleAdvancedHostFields,
                    onSubmit = viewModel::addManualForward,
                )
            }

            // Discovered Listening Ports on VPS
            item(key = "discovered_header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lan,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.ports_discovered_section),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            when {
                uiState.isDiscovering && uiState.discoveredPorts.isEmpty() -> {
                    item(key = "discovering_spinner") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.ports_scanning_server),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                uiState.discoveryError != null -> {
                    item(key = "discovery_error") {
                        Text(
                            text = uiState.discoveryError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                uiState.discoveredPorts.isEmpty() -> {
                    item(key = "discovered_empty") {
                        Text(
                            text = stringResource(R.string.ports_discovered_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    items(uiState.discoveredPorts, key = { "discovered_${it.port}" }) { discovered ->
                        val activeRule = uiState.rules.firstOrNull {
                            it.isActive && it.remotePort == discovered.port
                        }
                        DiscoveredPortRow(
                            port = discovered,
                            activeRule = activeRule,
                            isBusy = uiState.isSubmitting,
                            onForward = { viewModel.forwardDiscoveredPort(discovered) },
                            onOpenBrowser = {
                                activeRule?.let { openUrlInBrowser(context, it.localUrl) }
                            },
                        )
                    }
                }
            }

            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun ActiveTunnelCard(
    rule: PortForwardRule,
    onOpenBrowser: () -> Unit,
    onCopyUrl: () -> Unit,
    onStop: () -> Unit,
) {
    val statusColors = LocalStatusColors.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = 1.dp,
            color = if (rule.isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("port_rule_card_${rule.remotePort}"),
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
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(
                            if (rule.isActive) statusColors.connected else statusColors.warning,
                        ),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rule.displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "127.0.0.1:${rule.localPort} → ${rule.remoteHost}:${rule.remotePort}",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onStop,
                    modifier = Modifier.testTag("port_stop_button_${rule.remotePort}"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.StopCircle,
                        contentDescription = stringResource(R.string.ports_action_stop),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (!rule.errorMessage.isNullOrBlank()) {
                Text(
                    text = rule.errorMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = onOpenBrowser,
                    enabled = rule.isActive,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("port_open_browser_${rule.remotePort}"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.ports_action_open_browser))
                }
                OutlinedButton(
                    onClick = onCopyUrl,
                    enabled = rule.isActive,
                    modifier = Modifier.testTag("port_copy_url_${rule.remotePort}"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.action_copy))
                }
            }
        }
    }
}

@Composable
private fun ManualPortForwardCard(
    uiState: PortForwardUiState,
    onRemotePortChange: (String) -> Unit,
    onLabelChange: (String) -> Unit,
    onRemoteHostChange: (String) -> Unit,
    onLocalPortChange: (String) -> Unit,
    onToggleAdvanced: () -> Unit,
    onSubmit: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.ports_manual_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = uiState.formRemotePort,
                    onValueChange = onRemotePortChange,
                    label = { Text(stringResource(R.string.ports_remote_port_label)) },
                    placeholder = { Text("3000") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
                    modifier = Modifier
                        .weight(0.45f)
                        .testTag("ports_remote_port_input"),
                )
                OutlinedTextField(
                    value = uiState.formLabel,
                    onValueChange = onLabelChange,
                    label = { Text(stringResource(R.string.ports_label_optional)) },
                    placeholder = { Text("Next.js / API") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                    modifier = Modifier
                        .weight(0.55f)
                        .testTag("ports_label_input"),
                )
            }

            TextButton(
                onClick = onToggleAdvanced,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            ) {
                Text(
                    text = stringResource(R.string.ports_advanced_options),
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = if (uiState.showAdvancedHostFields) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }

            AnimatedVisibility(visible = uiState.showAdvancedHostFields) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = uiState.formRemoteHost,
                        onValueChange = onRemoteHostChange,
                        label = { Text(stringResource(R.string.ports_remote_host_label)) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                        modifier = Modifier.weight(0.55f),
                    )
                    OutlinedTextField(
                        value = uiState.formLocalPort,
                        onValueChange = onLocalPortChange,
                        label = { Text(stringResource(R.string.ports_local_port_optional)) },
                        placeholder = { Text(stringResource(R.string.ports_auto_placeholder)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                        modifier = Modifier.weight(0.45f),
                    )
                }
            }

            if (uiState.formError != null) {
                Text(
                    text = uiState.formError,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = onSubmit,
                enabled = !uiState.isSubmitting && uiState.formRemotePort.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ports_create_forward_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.ports_create_forward_button))
            }
        }
    }
}

@Composable
private fun DiscoveredPortRow(
    port: DiscoveredRemotePort,
    activeRule: PortForwardRule?,
    isBusy: Boolean,
    onForward: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ports_discovered_row_${port.port}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = ":${port.port}",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = MonoFontFamily,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = port.displayProcess,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(port.bindAddress)
                        if (port.pid != null) append(" • PID ${port.pid}")
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (activeRule != null) {
                FilledTonalButton(
                    onClick = onOpenBrowser,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = LocalStatusColors.current.connected,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.ports_action_open_short))
                }
            } else {
                OutlinedButton(
                    onClick = onForward,
                    enabled = !isBusy,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("ports_forward_discovered_${port.port}"),
                ) {
                    Text(stringResource(R.string.ports_action_forward))
                }
            }
        }
    }
}

private fun openUrlInBrowser(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(
            context,
            context.getString(R.string.ports_browser_open_error),
            Toast.LENGTH_SHORT,
        ).show()
    }
}
