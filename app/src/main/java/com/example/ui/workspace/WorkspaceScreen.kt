package com.example.ui.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.SshConnectionState
import com.example.ui.chat.ChatScreen
import com.example.ui.chat.ChatViewModel
import com.example.ui.chat.PermissionRequestDialog
import com.example.ui.components.ConnectionStatusDot
import com.example.ui.files.FileManagerScreen
import com.example.ui.files.FileManagerViewModel
import com.example.ui.settings.SettingsScreen
import com.example.ui.settings.SettingsViewModel
import com.example.ui.terminal.TerminalScreen
import com.example.ui.terminal.TerminalViewModel
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.MonoFontFamily

enum class WorkspaceTab(
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String,
) {
    CHAT(
        labelRes = R.string.nav_chat,
        selectedIcon = Icons.Rounded.ChatBubble,
        unselectedIcon = Icons.Outlined.ChatBubbleOutline,
        testTag = "nav_tab_chat",
    ),
    FILES(
        labelRes = R.string.nav_files,
        selectedIcon = Icons.Rounded.Folder,
        unselectedIcon = Icons.Outlined.Folder,
        testTag = "nav_tab_files",
    ),
    TERMINAL(
        labelRes = R.string.nav_terminal,
        selectedIcon = Icons.Rounded.Terminal,
        unselectedIcon = Icons.Outlined.Terminal,
        testTag = "nav_tab_terminal",
    ),
    SETTINGS(
        labelRes = R.string.nav_settings,
        selectedIcon = Icons.Rounded.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        testTag = "nav_tab_settings",
    ),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    connectionState: SshConnectionState,
    isOnline: Boolean,
    chatViewModel: ChatViewModel,
    fileManagerViewModel: FileManagerViewModel,
    terminalViewModel: TerminalViewModel,
    settingsViewModel: SettingsViewModel,
    onOpenServers: () -> Unit,
    onOpenCodeEditor: (remotePath: String, line: Int?) -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(WorkspaceTab.CHAT) }
    val chatUiState by chatViewModel.uiState.collectAsStateWithLifecycle()
    var permissionDialogSuppressedId by remember { mutableStateOf<String?>(null) }

    val activePermission = chatUiState.currentPermissionPrompt

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = {
                        ActiveServerHeaderPill(
                            connectionState = connectionState,
                            onClick = onOpenServers,
                        )
                    },
                    actions = {
                        when (connectionState) {
                            is SshConnectionState.Connected -> {
                                IconButton(
                                    onClick = onDisconnect,
                                    modifier = Modifier.testTag("workspace_quick_disconnect"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.LinkOff,
                                        contentDescription = stringResource(R.string.servers_disconnect),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            is SshConnectionState.Error -> {
                                IconButton(
                                    onClick = onReconnect,
                                    modifier = Modifier.testTag("workspace_quick_reconnect"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = stringResource(R.string.servers_reconnect),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }

                            else -> Unit
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
            bottomBar = {
                if (!useNavigationRail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.testTag("workspace_bottom_nav"),
                    ) {
                        WorkspaceTab.entries.forEach { tab ->
                            val selected = selectedTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { selectedTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.labelRes),
                                    )
                                },
                                label = { Text(text = stringResource(tab.labelRes)) },
                                modifier = Modifier.testTag(tab.testTag),
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (useNavigationRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxHeight()
                            .testTag("workspace_nav_rail"),
                    ) {
                        WorkspaceTab.entries.forEach { tab ->
                            val selected = selectedTab == tab
                            NavigationRailItem(
                                selected = selected,
                                onClick = { selectedTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.labelRes),
                                    )
                                },
                                label = { Text(text = stringResource(tab.labelRes)) },
                                modifier = Modifier.testTag(tab.testTag),
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    if (!isOnline) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("workspace_offline_banner"),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CloudOff,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = stringResource(R.string.workspace_offline_banner),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }

                    if (chatUiState.pendingPermissions.isNotEmpty()) {
                        Surface(
                            color = LocalStatusColors.current.warning.copy(alpha = 0.16f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("workspace_permission_banner"),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Shield,
                                    contentDescription = null,
                                    tint = LocalStatusColors.current.warning,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = stringResource(
                                        R.string.permission_pending_banner,
                                        chatUiState.pendingPermissions.size,
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = { permissionDialogSuppressedId = null },
                                ) {
                                    Text(stringResource(R.string.permission_open_button))
                                }
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (selectedTab) {
                            WorkspaceTab.CHAT -> ChatScreen(
                                viewModel = chatViewModel,
                                onOpenServers = onOpenServers,
                                onOpenCodeEditor = onOpenCodeEditor,
                            )

                            WorkspaceTab.FILES -> FileManagerScreen(
                                viewModel = fileManagerViewModel,
                                onOpenServers = onOpenServers,
                                onOpenCodeEditor = onOpenCodeEditor,
                                onAttachToChat = { entry ->
                                    chatViewModel.attachFile(entry.path, entry.name)
                                    selectedTab = WorkspaceTab.CHAT
                                },
                            )

                            WorkspaceTab.TERMINAL -> TerminalScreen(
                                viewModel = terminalViewModel,
                                onOpenServers = onOpenServers,
                            )

                            WorkspaceTab.SETTINGS -> SettingsScreen(
                                viewModel = settingsViewModel,
                                onManageServers = onOpenServers,
                            )
                        }
                    }
                }
            }
        }
    }

    if (activePermission != null && permissionDialogSuppressedId != activePermission.id) {
        PermissionRequestDialog(
            request = activePermission,
            isSubmitting = chatUiState.isSubmittingPermission,
            onReply = { option ->
                permissionDialogSuppressedId = null
                chatViewModel.replyPermission(activePermission, option)
            },
            onDismiss = {
                permissionDialogSuppressedId = activePermission.id
            },
        )
    }
}

@Composable
private fun ActiveServerHeaderPill(
    connectionState: SshConnectionState,
    onClick: () -> Unit,
) {
    val activeProfile = when (connectionState) {
        is SshConnectionState.Connected -> connectionState.profile
        is SshConnectionState.Connecting -> connectionState.profile
        is SshConnectionState.AwaitingHostKeyVerification -> connectionState.profile
        is SshConnectionState.Reconnecting -> connectionState.profile
        is SshConnectionState.Error -> connectionState.profile
        SshConnectionState.Disconnected -> null
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("workspace_server_pill"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ConnectionStatusDot(state = connectionState)
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = activeProfile?.name ?: stringResource(R.string.status_no_server_selected),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = when (connectionState) {
                    is SshConnectionState.Connected ->
                        "127.0.0.1:${connectionState.localTunnelPort} → :${connectionState.profile.openCodePort}"
                    is SshConnectionState.Connecting ->
                        stringResource(R.string.status_connecting)
                    is SshConnectionState.AwaitingHostKeyVerification ->
                        stringResource(R.string.status_verifying_host)
                    is SshConnectionState.Reconnecting ->
                        stringResource(R.string.status_reconnecting, connectionState.attempt)
                    is SshConnectionState.Error ->
                        stringResource(R.string.status_error)
                    SshConnectionState.Disconnected ->
                        stringResource(R.string.workspace_choose_server)
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Rounded.Dns,
                contentDescription = stringResource(R.string.nav_servers),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
