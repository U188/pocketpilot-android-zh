package com.example.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.di.AppContainer
import com.example.domain.model.SshConnectionState
import com.example.ui.chat.ChatViewModel
import com.example.ui.components.HostKeyVerificationDialog
import com.example.ui.editor.CodeEditorScreen
import com.example.ui.editor.CodeEditorViewModel
import com.example.ui.files.FileManagerViewModel
import com.example.ui.servers.ServerEditorScreen
import com.example.ui.servers.ServerEditorViewModel
import com.example.ui.servers.ServersScreen
import com.example.ui.servers.ServersViewModel
import com.example.ui.settings.SettingsViewModel
import com.example.ui.terminal.TerminalViewModel
import com.example.ui.usage.UsageScreen
import com.example.ui.usage.UsageViewModel
import com.example.ui.workspace.WorkspaceScreen

object OpenCodeRoutes {
    const val SERVERS = "servers"
    const val WORKSPACE = "workspace"
    const val USAGE = "usage"
    const val SERVER_EDITOR = "server_editor/{profileId}"
    const val CODE_EDITOR = "code_editor?path={path}&line={line}"

    fun serverEditor(profileId: Long = -1L): String = "server_editor/$profileId"

    fun codeEditor(remotePath: String, line: Int? = null): String {
        val encodedPath = Uri.encode(remotePath)
        val lineVal = line ?: -1
        return "code_editor?path=$encodedPath&line=$lineVal"
    }
}

@Composable
fun OpenCodeNavHost(
    container: AppContainer,
) {
    val navController = rememberNavController()
    val connectionState by container.sshConnectionManager.connectionState.collectAsStateWithLifecycle()
    val isOnline by container.networkMonitor.isOnline.collectAsStateWithLifecycle()

    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(
            preferencesRepository = container.preferencesRepository,
            knownHostsRepository = container.knownHostsRepository,
            terminalSnippetRepository = container.terminalSnippetRepository,
            connectionManager = container.sshConnectionManager,
            serverInstaller = container.openCodeServerInstaller,
            appUpdateManager = container.appUpdateManager,
            profileBackupManager = container.profileBackupManager,
        ),
    )

    val fileManagerViewModel: FileManagerViewModel = viewModel(
        factory = FileManagerViewModel.Factory(
            connectionManager = container.sshConnectionManager,
            sftpRepository = container.sftpRepository,
            transferQueueManager = container.transferQueueManager,
            preferencesRepository = container.preferencesRepository,
            localSafBrowser = container.localSafBrowser,
        ),
    )

    val chatViewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.Factory(
            connectionManager = container.sshConnectionManager,
            apiClient = container.openCodeApiClient,
            sftpRepository = container.sftpRepository,
            preferencesRepository = container.preferencesRepository,
            serverInstaller = container.openCodeServerInstaller,
            usageRepository = container.usageRepository,
            agentNotificationHelper = container.agentNotificationHelper,
        ),
    )

    val terminalViewModel: TerminalViewModel = viewModel(
        factory = TerminalViewModel.Factory(
            connectionManager = container.sshConnectionManager,
            snippetRepository = container.terminalSnippetRepository,
        ),
    )

    NavHost(
        navController = navController,
        startDestination = OpenCodeRoutes.SERVERS,
    ) {
        composable(route = OpenCodeRoutes.SERVERS) {
            val serversViewModel: ServersViewModel = viewModel(
                factory = ServersViewModel.Factory(
                    profileRepository = container.serverProfileRepository,
                    connectionManager = container.sshConnectionManager,
                    profileBackupManager = container.profileBackupManager,
                ),
            )
            ServersScreen(
                viewModel = serversViewModel,
                onAddServer = {
                    navController.navigate(OpenCodeRoutes.serverEditor(-1L))
                },
                onEditServer = { profileId ->
                    navController.navigate(OpenCodeRoutes.serverEditor(profileId))
                },
                onOpenWorkspace = {
                    navController.navigate(OpenCodeRoutes.WORKSPACE) {
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(
            route = OpenCodeRoutes.SERVER_EDITOR,
            arguments = listOf(
                navArgument("profileId") {
                    type = NavType.LongType
                    defaultValue = -1L
                },
            ),
        ) { backStackEntry ->
            val profileId = backStackEntry.arguments?.getLong("profileId") ?: -1L
            val editorViewModel: ServerEditorViewModel = viewModel(
                key = "server_editor_$profileId",
                factory = ServerEditorViewModel.Factory(
                    profileId = profileId,
                    profileRepository = container.serverProfileRepository,
                    keyGenerator = container.sshKeyGenerator,
                    connectionManager = container.sshConnectionManager,
                ),
            )
            ServerEditorScreen(
                viewModel = editorViewModel,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(route = OpenCodeRoutes.WORKSPACE) {
            WorkspaceScreen(
                connectionState = connectionState,
                isOnline = isOnline,
                chatViewModel = chatViewModel,
                fileManagerViewModel = fileManagerViewModel,
                terminalViewModel = terminalViewModel,
                settingsViewModel = settingsViewModel,
                onOpenServers = {
                    if (!navController.popBackStack(OpenCodeRoutes.SERVERS, inclusive = false)) {
                        navController.navigate(OpenCodeRoutes.SERVERS) {
                            launchSingleTop = true
                        }
                    }
                },
                onOpenCodeEditor = { remotePath, line ->
                    navController.navigate(OpenCodeRoutes.codeEditor(remotePath, line))
                },
                onOpenUsage = {
                    navController.navigate(OpenCodeRoutes.USAGE) {
                        launchSingleTop = true
                    }
                },
                onDisconnect = container.sshConnectionManager::disconnect,
                onReconnect = container.sshConnectionManager::reconnectActive,
            )
        }

        composable(route = OpenCodeRoutes.USAGE) {
            val usageViewModel: UsageViewModel = viewModel(
                factory = UsageViewModel.Factory(
                    usageRepository = container.usageRepository,
                    connectionManager = container.sshConnectionManager,
                    preferencesRepository = container.preferencesRepository,
                ),
            )
            UsageScreen(
                viewModel = usageViewModel,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(
            route = OpenCodeRoutes.CODE_EDITOR,
            arguments = listOf(
                navArgument("path") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("line") {
                    type = NavType.IntType
                    defaultValue = -1
                },
            ),
        ) { backStackEntry ->
            val encodedPath = backStackEntry.arguments?.getString("path").orEmpty()
            val remotePath = Uri.decode(encodedPath)
            val lineArg = backStackEntry.arguments?.getInt("line")?.takeIf { it > 0 }
            val editorViewModel: CodeEditorViewModel = viewModel(
                key = "code_editor_$remotePath",
                factory = CodeEditorViewModel.Factory(
                    remotePath = remotePath,
                    initialLine = lineArg,
                    sftpRepository = container.sftpRepository,
                    preferencesRepository = container.preferencesRepository,
                ),
            )
            CodeEditorScreen(
                viewModel = editorViewModel,
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }

    val awaitingHostKey = connectionState as? SshConnectionState.AwaitingHostKeyVerification
    if (awaitingHostKey != null) {
        HostKeyVerificationDialog(
            prompt = awaitingHostKey.prompt,
            onTrust = { container.sshConnectionManager.resolveHostKeyDecision(true) },
            onReject = { container.sshConnectionManager.resolveHostKeyDecision(false) },
        )
    }
}
