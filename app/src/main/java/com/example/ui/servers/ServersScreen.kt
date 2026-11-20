package com.example.ui.servers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.AuthType
import com.example.domain.model.ServerProfile
import com.example.domain.model.SshConnectionState
import com.example.ui.components.ConnectionStatusBadge
import com.example.ui.components.ConnectionStatusDot
import com.example.ui.components.StatusChip
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.MonoFontFamily
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    viewModel: ServersViewModel,
    onAddServer: () -> Unit,
    onEditServer: (Long) -> Unit,
    onOpenWorkspace: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var profileToDelete by remember { mutableStateOf<ServerProfile?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.servers_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = stringResource(R.string.servers_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = onOpenWorkspace,
                        modifier = Modifier.testTag("open_workspace_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Terminal,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = stringResource(R.string.servers_open_workspace))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddServer,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.servers_add_button)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("add_server_fab"),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 720.dp),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "active_banner") {
                    AnimatedVisibility(visible = uiState.connectionState !is SshConnectionState.Disconnected) {
                        ActiveConnectionBanner(
                            state = uiState.connectionState,
                            onDisconnect = viewModel::disconnect,
                            onReconnect = viewModel::reconnect,
                            onOpenWorkspace = onOpenWorkspace,
                        )
                    }
                }

                if (uiState.profiles.isEmpty()) {
                    item(key = "empty_state") {
                        EmptyServersCard(onAddServer = onAddServer)
                    }
                } else {
                    items(
                        items = uiState.profiles,
                        key = { it.id },
                    ) { profile ->
                        ServerProfileCard(
                            profile = profile,
                            connectionState = uiState.connectionState,
                            onConnect = {
                                viewModel.connect(profile.id)
                            },
                            onDisconnect = viewModel::disconnect,
                            onOpenWorkspace = onOpenWorkspace,
                            onEdit = { onEditServer(profile.id) },
                            onDelete = { profileToDelete = profile },
                        )
                    }
                }
            }
        }
    }

    profileToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { profileToDelete = null },
            title = { Text(text = stringResource(R.string.servers_delete_title)) },
            text = {
                Text(text = stringResource(R.string.servers_delete_message, target.name))
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteProfile(target.id)
                        profileToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.testTag("confirm_delete_server_button"),
                ) {
                    Text(text = stringResource(R.string.servers_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { profileToDelete = null },
                    modifier = Modifier.testTag("cancel_delete_server_button"),
                ) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ActiveConnectionBanner(
    state: SshConnectionState,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onOpenWorkspace: () -> Unit,
) {
    val statusColors = LocalStatusColors.current
    val accent = when (state) {
        is SshConnectionState.Connected -> statusColors.connected
        is SshConnectionState.Connecting,
        is SshConnectionState.AwaitingHostKeyVerification,
        is SshConnectionState.Reconnecting -> statusColors.warning
        is SshConnectionState.Error -> MaterialTheme.colorScheme.error
        SshConnectionState.Disconnected -> MaterialTheme.colorScheme.outline
    }

    val profile = when (state) {
        is SshConnectionState.Connected -> state.profile
        is SshConnectionState.Connecting -> state.profile
        is SshConnectionState.AwaitingHostKeyVerification -> state.profile
        is SshConnectionState.Reconnecting -> state.profile
        is SshConnectionState.Error -> state.profile
        SshConnectionState.Disconnected -> null
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("active_connection_banner"),
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            accent.copy(alpha = 0.14f),
                            MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    ),
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile?.name ?: stringResource(R.string.status_no_server_selected),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (profile != null) {
                        Text(
                            text = "${profile.username}@${profile.host}:${profile.sshPort}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                ConnectionStatusBadge(state = state)
            }

            when (state) {
                is SshConnectionState.Connected -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusChip(
                            label = stringResource(
                                R.string.servers_tunnel_active,
                                state.localTunnelPort,
                                state.profile.openCodePort,
                            ),
                            accentColor = statusColors.tunnel,
                        )
                        Text(
                            text = "${state.latencyMs} мс",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is SshConnectionState.Error -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                is SshConnectionState.Reconnecting -> {
                    state.lastError?.takeIf { it.isNotBlank() }?.let { err ->
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> Unit
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state is SshConnectionState.Connected) {
                    Button(
                        onClick = onOpenWorkspace,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("banner_open_workspace_button"),
                    ) {
                        Text(text = stringResource(R.string.servers_open))
                    }
                } else if (state is SshConnectionState.Error && profile != null) {
                    FilledTonalButton(
                        onClick = onReconnect,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("banner_reconnect_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = stringResource(R.string.servers_reconnect))
                    }
                }

                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.testTag("banner_disconnect_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LinkOff,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.servers_disconnect))
                }
            }
        }
    }
}

@Composable
private fun ServerProfileCard(
    profile: ServerProfile,
    connectionState: SshConnectionState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val isThisConnected = connectionState is SshConnectionState.Connected &&
        connectionState.profile.id == profile.id
    val isThisConnecting = (connectionState is SshConnectionState.Connecting &&
        connectionState.profile.id == profile.id) ||
        (connectionState is SshConnectionState.AwaitingHostKeyVerification &&
            connectionState.profile.id == profile.id) ||
        (connectionState is SshConnectionState.Reconnecting &&
            connectionState.profile.id == profile.id)

    val borderColor = if (isThisConnected) {
        LocalStatusColors.current.connected.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("server_card_${profile.id}"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isThisConnected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Dns,
                        contentDescription = null,
                        tint = if (isThisConnected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = profile.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (isThisConnected || isThisConnecting) {
                            ConnectionStatusDot(state = connectionState)
                        }
                    }
                    Text(
                        text = "${profile.username}@${profile.host}:${profile.sshPort}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.testTag("edit_server_${profile.id}"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = stringResource(R.string.servers_edit),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_server_${profile.id}"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = stringResource(R.string.servers_delete),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MetadataTag(
                    icon = if (profile.authType == AuthType.PRIVATE_KEY) {
                        Icons.Rounded.Key
                    } else {
                        Icons.Rounded.Password
                    },
                    text = stringResource(
                        if (profile.authType == AuthType.PRIVATE_KEY) {
                            R.string.servers_auth_key
                        } else {
                            R.string.servers_auth_password
                        },
                    ),
                )
                MetadataTag(
                    icon = Icons.Rounded.Lan,
                    text = stringResource(R.string.servers_opencode_port_badge, profile.openCodePort),
                )
            }

            Text(
                text = formatLastConnected(profile.lastConnectedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    isThisConnected -> {
                        Button(
                            onClick = onOpenWorkspace,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("open_server_${profile.id}"),
                        ) {
                            Text(text = stringResource(R.string.servers_open))
                        }
                        OutlinedButton(
                            onClick = onDisconnect,
                            modifier = Modifier.testTag("disconnect_server_${profile.id}"),
                        ) {
                            Text(text = stringResource(R.string.servers_disconnect))
                        }
                    }

                    isThisConnecting -> {
                        OutlinedButton(
                            onClick = onDisconnect,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("cancel_connect_${profile.id}"),
                        ) {
                            Text(text = stringResource(R.string.action_cancel))
                        }
                    }

                    else -> {
                        FilledTonalButton(
                            onClick = onConnect,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("connect_server_${profile.id}"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Link,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = stringResource(R.string.servers_connect))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataTag(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyServersCard(
    onAddServer: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f),
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
            }

            Text(
                text = stringResource(R.string.servers_empty_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.servers_empty_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = onAddServer,
                modifier = Modifier.testTag("empty_add_server_button"),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.servers_add_button))
            }
        }
    }
}

@Composable
private fun formatLastConnected(timestamp: Long?): String {
    if (timestamp == null) return stringResource(R.string.servers_never_connected)
    val formatted = remember(timestamp) {
        val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
        Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .format(formatter)
    }
    return stringResource(R.string.servers_last_connected, formatted)
}
