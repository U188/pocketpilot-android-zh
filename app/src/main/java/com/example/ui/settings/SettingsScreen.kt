package com.example.ui.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.ScreenshotMonitor
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.SystemdServiceState
import com.example.domain.model.TerminalSnippet
import com.example.domain.model.ThemeMode
import com.example.security.BiometricAuthHelper
import com.example.ui.components.StatusChip
import com.example.ui.terminal.SnippetEditorDialog
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.MonoFontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onManageServers: () -> Unit,
    onOpenConnectModel: () -> Unit = {},
    onRepairService: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val biometricAvailable = remember(context) { BiometricAuthHelper.canAuthenticate(context) }

    LaunchedEffect(uiState.isSshConnected) {
        if (uiState.isSshConnected) {
            viewModel.refreshServerStatus()
        }
    }

    var hasNotifPermission by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasNotifPermission = granted },
    )

    var creatingSnippet by remember { mutableStateOf(false) }
    var editingSnippet by remember { mutableStateOf<TerminalSnippet?>(null) }
    var confirmRemoveService by remember { mutableStateOf(false) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "servers_section") {
                SettingsCard(title = stringResource(R.string.settings_section_servers)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onManageServers)
                            .padding(vertical = 6.dp)
                            .testTag("settings_manage_servers_row"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Dns,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.settings_manage_servers),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item(key = "opencode_server_admin_section") {
                SettingsCard(title = stringResource(R.string.settings_section_opencode_server)) {
                    if (!uiState.isSshConnected) {
                        Text(
                            text = stringResource(R.string.settings_opencode_disconnected),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val admin = uiState.serverAdmin
                        val statusColors = LocalStatusColors.current
                        val (stateLabel, stateAccent) = when (admin.serviceState) {
                            SystemdServiceState.ACTIVE ->
                                stringResource(R.string.settings_opencode_status_active) to statusColors.connected
                            SystemdServiceState.INACTIVE ->
                                stringResource(R.string.settings_opencode_status_inactive) to statusColors.warning
                            SystemdServiceState.FAILED ->
                                stringResource(R.string.settings_opencode_status_failed) to MaterialTheme.colorScheme.error
                            SystemdServiceState.NOT_FOUND ->
                                stringResource(R.string.settings_opencode_status_missing) to statusColors.disconnected
                            SystemdServiceState.UNKNOWN ->
                                stringResource(R.string.settings_opencode_status_unknown) to MaterialTheme.colorScheme.onSurfaceVariant
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (uiState.activeServerName.isNotBlank()) {
                                    Text(
                                        text = uiState.activeServerName,
                                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    StatusChip(label = stateLabel, accentColor = stateAccent)
                                    if (admin.hasPasswordProtection) {
                                        StatusChip(
                                            label = stringResource(R.string.settings_opencode_auth_badge),
                                            accentColor = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                Text(
                                    text = "${stringResource(R.string.settings_opencode_version_label)}: ${
                                        admin.opencodeVersion ?: stringResource(R.string.settings_opencode_version_unknown)
                                    }",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = viewModel::refreshServerStatus,
                                enabled = !admin.isRefreshingStatus && !admin.isRunningAction,
                                modifier = Modifier.testTag("settings_opencode_refresh_status"),
                            ) {
                                if (admin.isRefreshingStatus) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = stringResource(R.string.action_refresh),
                                    )
                                }
                            }
                        }

                        admin.statusMessage?.let { msg ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (admin.isRunningAction) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (admin.isError) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        statusColors.connected
                                    },
                                )
                            }
                        }

                        // Row 1: Restart & Stop
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalButton(
                                onClick = viewModel::restartOpenCodeOnServer,
                                enabled = !admin.isRunningAction,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_opencode_restart_button"),
                            ) {
                                Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.settings_opencode_restart),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            OutlinedButton(
                                onClick = viewModel::stopOpenCodeOnServer,
                                enabled = !admin.isRunningAction,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_opencode_stop_button"),
                            ) {
                                Icon(Icons.Rounded.StopCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.settings_opencode_stop),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        // Row 2: Update & Logs
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalButton(
                                onClick = viewModel::updateOpenCodeOnServer,
                                enabled = !admin.isRunningAction,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_opencode_update_button"),
                            ) {
                                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.settings_opencode_update),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            OutlinedButton(
                                onClick = viewModel::openServerLogs,
                                enabled = !admin.isRunningAction,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_opencode_logs_button"),
                            ) {
                                Icon(Icons.Rounded.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.settings_opencode_logs),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        // Row 3: Repair & Remove service
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = onRepairService,
                                enabled = !admin.isRunningAction,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_opencode_repair_button"),
                            ) {
                                Icon(Icons.Rounded.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.settings_opencode_repair),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            OutlinedButton(
                                onClick = { confirmRemoveService = true },
                                enabled = !admin.isRunningAction,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_opencode_remove_button"),
                            ) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.settings_opencode_remove),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = onOpenConnectModel,
                            enabled = !admin.isRunningAction,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_opencode_connect_model_button"),
                        ) {
                            Icon(Icons.Rounded.SmartToy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = stringResource(R.string.settings_opencode_connect_model))
                        }
                    }
                }
            }

            item(key = "appearance_section") {
                SettingsCard(title = stringResource(R.string.settings_section_appearance)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Palette,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.settings_theme_label),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }

                    val modes = listOf(
                        ThemeMode.SYSTEM to R.string.settings_theme_system,
                        ThemeMode.DARK to R.string.settings_theme_dark,
                        ThemeMode.LIGHT to R.string.settings_theme_light,
                    )

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        modes.forEachIndexed { index, (mode, labelRes) ->
                            SegmentedButton(
                                selected = uiState.settings.themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                                modifier = Modifier.testTag("theme_mode_${mode.name.lowercase()}"),
                            ) {
                                Text(
                                    text = stringResource(labelRes),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.settings_dynamic_color_title),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = stringResource(R.string.settings_dynamic_color_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = uiState.settings.dynamicColor,
                                onCheckedChange = viewModel::setDynamicColor,
                                modifier = Modifier.testTag("dynamic_color_switch"),
                            )
                        }
                    }
                }
            }

            item(key = "editor_section") {
                SettingsCard(title = stringResource(R.string.settings_section_editor)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Code,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(
                                R.string.settings_editor_font_size,
                                uiState.settings.editorFontSizeSp.toInt(),
                            ),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    Slider(
                        value = uiState.settings.editorFontSizeSp,
                        onValueChange = { viewModel.setEditorFontSize(it) },
                        valueRange = 10f..24f,
                        steps = 13,
                        modifier = Modifier.testTag("editor_font_size_slider"),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.editor_code_word_wrap),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Switch(
                            checked = uiState.settings.editorWordWrap,
                            onCheckedChange = viewModel::setEditorWordWrap,
                            modifier = Modifier.testTag("editor_word_wrap_switch"),
                        )
                    }
                }
            }

            item(key = "opencode_section") {
                SettingsCard(title = stringResource(R.string.settings_section_opencode)) {
                    Text(
                        text = stringResource(R.string.settings_default_agent),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val defaultAgents = listOf("build", "plan")
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        defaultAgents.forEachIndexed { idx, agent ->
                            SegmentedButton(
                                selected = uiState.settings.defaultAgentName.equals(agent, ignoreCase = true),
                                onClick = { viewModel.setDefaultAgent(agent) },
                                shape = SegmentedButtonDefaults.itemShape(idx, defaultAgents.size),
                                modifier = Modifier.testTag("default_agent_$agent"),
                            ) {
                                Text(text = agent)
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.settings_default_model),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        val modelLabel = if (uiState.settings.defaultModelId.isNotBlank()) {
                            "${uiState.settings.defaultProviderId}/${uiState.settings.defaultModelId}"
                        } else {
                            stringResource(R.string.settings_model_not_selected)
                        }
                        Text(
                            text = modelLabel,
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item(key = "snippets_section") {
                SettingsCard(title = stringResource(R.string.settings_section_snippets)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Bolt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = stringResource(R.string.settings_snippets_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = { creatingSnippet = true },
                            modifier = Modifier.testTag("settings_add_snippet_button"),
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.action_create))
                        }
                    }

                    if (uiState.snippets.isEmpty()) {
                        Text(
                            text = stringResource(R.string.terminal_snippets_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            uiState.snippets.forEach { snippet ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = snippet.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = snippet.command,
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    IconButton(onClick = { editingSnippet = snippet }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Edit,
                                            contentDescription = stringResource(R.string.action_edit),
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    IconButton(onClick = { viewModel.deleteSnippet(snippet.id) }) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = stringResource(R.string.terminal_snippet_delete),
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item(key = "background_section") {
                SettingsCard(title = stringResource(R.string.settings_section_background)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.NotificationsActive,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_keep_alive_title),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = stringResource(R.string.settings_keep_alive_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = uiState.settings.keepAliveInBackground,
                            onCheckedChange = { enabled ->
                                viewModel.setKeepAliveInBackground(enabled)
                                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifPermission) {
                                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                            modifier = Modifier.testTag("keep_alive_switch"),
                        )
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        uiState.settings.keepAliveInBackground &&
                        !hasNotifPermission
                    ) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.settings_notifications_denied),
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalStatusColors.current.warning,
                            )
                            FilledTonalButton(
                                onClick = {
                                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                },
                                modifier = Modifier.testTag("grant_notifications_button"),
                            ) {
                                Text(stringResource(R.string.settings_notifications_grant))
                            }
                        }
                    }
                }
            }

            item(key = "security_section") {
                SettingsCard(title = stringResource(R.string.settings_section_security)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Fingerprint,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_biometric_title),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = if (biometricAvailable) {
                                    stringResource(R.string.settings_biometric_subtitle)
                                } else {
                                    stringResource(R.string.settings_biometric_unavailable)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = uiState.settings.biometricLockEnabled,
                            enabled = biometricAvailable || uiState.settings.biometricLockEnabled,
                            onCheckedChange = { enable ->
                                if (!enable) {
                                    viewModel.setBiometricLockEnabled(false)
                                } else if (activity != null && biometricAvailable) {
                                    BiometricAuthHelper.authenticate(
                                        activity = activity,
                                        title = context.getString(R.string.biometric_prompt_title),
                                        subtitle = context.getString(R.string.biometric_enable_prompt_subtitle),
                                        onSuccess = { viewModel.setBiometricLockEnabled(true) },
                                        onError = { /* keep disabled on cancel/failure */ },
                                    )
                                }
                            },
                            modifier = Modifier.testTag("biometric_lock_switch"),
                        )
                    }

                    if (uiState.settings.biometricLockEnabled) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = stringResource(R.string.settings_biometric_timeout_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val timeouts = listOf(
                                0 to R.string.settings_biometric_timeout_immediate,
                                60 to R.string.settings_biometric_timeout_1m,
                                300 to R.string.settings_biometric_timeout_5m,
                            )
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                timeouts.forEachIndexed { idx, (seconds, labelRes) ->
                                    SegmentedButton(
                                        selected = uiState.settings.biometricLockTimeoutSec == seconds,
                                        onClick = { viewModel.setBiometricLockTimeoutSec(seconds) },
                                        shape = SegmentedButtonDefaults.itemShape(idx, timeouts.size),
                                    ) {
                                        Text(
                                            text = stringResource(labelRes),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ScreenshotMonitor,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_secure_screen_title),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = stringResource(R.string.settings_secure_screen_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = uiState.settings.secureScreenEnabled,
                            onCheckedChange = viewModel::setSecureScreenEnabled,
                            modifier = Modifier.testTag("secure_screen_switch"),
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_keystore_status_title),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = stringResource(R.string.settings_keystore_status_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.VerifiedUser,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(
                                R.string.settings_known_hosts_title,
                                uiState.knownHosts.size,
                            ),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }

                    if (uiState.knownHosts.isEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_known_hosts_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            uiState.knownHosts.forEach { host ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${host.hostPortKey} (${host.algorithm})",
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontFamily = MonoFontFamily,
                                            ),
                                        )
                                        Text(
                                            text = host.fingerprintSha256,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = MonoFontFamily,
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    IconButton(
                                        onClick = { viewModel.removeKnownHost(host.hostPortKey) },
                                        modifier = Modifier.testTag("remove_known_host_${host.hostPortKey}"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = stringResource(R.string.settings_known_host_delete),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item(key = "privacy_footer") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PrivacyTip,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(R.string.settings_privacy_badge_title),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                text = stringResource(R.string.settings_privacy_badge_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (creatingSnippet) {
        SnippetEditorDialog(
            initial = null,
            onSave = {
                creatingSnippet = false
                viewModel.saveSnippet(it)
            },
            onDismiss = { creatingSnippet = false },
        )
    }

    editingSnippet?.let { target ->
        SnippetEditorDialog(
            initial = target,
            onSave = {
                editingSnippet = null
                viewModel.saveSnippet(it)
            },
            onDismiss = { editingSnippet = null },
        )
    }

    if (uiState.serverAdmin.showLogsDialog) {
        ServerLogsDialog(
            logs = uiState.serverAdmin.serverLogs,
            isLoading = uiState.serverAdmin.isLoadingLogs,
            onRefresh = viewModel::openServerLogs,
            onCopy = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("OpenCode Server Logs", uiState.serverAdmin.serverLogs))
            },
            onDismiss = viewModel::dismissServerLogs,
        )
    }

    if (confirmRemoveService) {
        AlertDialog(
            onDismissRequest = { confirmRemoveService = false },
            title = { Text(text = stringResource(R.string.settings_opencode_remove_confirm_title)) },
            text = { Text(text = stringResource(R.string.settings_opencode_remove_confirm_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemoveService = false
                        viewModel.removeOpenCodeService()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.settings_opencode_remove),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveService = false }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ServerLogsDialog(
    logs: String,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_opencode_logs_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRefresh, enabled = !isLoading) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.action_refresh),
                    )
                }
                IconButton(onClick = onCopy, enabled = logs.isNotBlank()) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(R.string.setup_copy_log),
                    )
                }
            }
        },
        text = {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp, max = 360.dp),
            ) {
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp),
                    ) {
                        SelectionContainer {
                            Text(
                                text = logs.ifBlank { stringResource(R.string.settings_opencode_logs_empty) },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = MonoFontFamily,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                ),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_close))
            }
        },
    )
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            content()
        }
    }
}
