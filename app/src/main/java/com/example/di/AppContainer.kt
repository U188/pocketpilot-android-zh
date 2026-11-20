package com.example.di

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.local.AppPreferencesRepository
import com.example.data.local.KeystoreSecretStore
import com.example.data.local.LocalSafBrowser
import com.example.data.network.NetworkMonitor
import com.example.data.opencode.OpenCodeApiClient
import com.example.data.repository.KnownHostsRepository
import com.example.data.repository.ProfileBackupManager
import com.example.data.repository.ServerProfileRepository
import com.example.data.repository.TerminalSnippetRepository
import com.example.data.repository.UsageRepository
import com.example.data.sftp.SftpRepository
import com.example.data.sftp.TransferQueueManager
import com.example.data.ssh.SshConnectionManager
import com.example.data.ssh.SshKeyGenerator
import com.example.data.update.AppUpdateManager
import com.example.service.AgentNotificationHelper
import com.example.service.SshForegroundServiceController

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: AppDatabase by lazy { AppDatabase.build(appContext) }
    val secretStore: KeystoreSecretStore by lazy { KeystoreSecretStore(appContext) }
    val preferencesRepository: AppPreferencesRepository by lazy { AppPreferencesRepository(appContext) }
    val localSafBrowser: LocalSafBrowser by lazy { LocalSafBrowser(appContext) }
    val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(appContext) }

    val serverProfileRepository: ServerProfileRepository by lazy {
        ServerProfileRepository(
            dao = database.serverProfileDao(),
            secretStore = secretStore,
        )
    }

    val knownHostsRepository: KnownHostsRepository by lazy {
        KnownHostsRepository(dao = database.knownHostDao())
    }

    val terminalSnippetRepository: TerminalSnippetRepository by lazy {
        TerminalSnippetRepository(dao = database.terminalSnippetDao())
    }

    val sshKeyGenerator: SshKeyGenerator by lazy { SshKeyGenerator() }

    val sshConnectionManager: SshConnectionManager by lazy {
        SshConnectionManager(
            profileRepository = serverProfileRepository,
            knownHostsRepository = knownHostsRepository,
            preferencesRepository = preferencesRepository,
            networkMonitor = networkMonitor,
        )
    }

    val foregroundServiceController: SshForegroundServiceController by lazy {
        SshForegroundServiceController(
            context = appContext,
            connectionManager = sshConnectionManager,
            preferencesRepository = preferencesRepository,
        )
    }

    val sftpRepository: SftpRepository by lazy {
        SftpRepository(connectionManager = sshConnectionManager)
    }

    val transferQueueManager: TransferQueueManager by lazy {
        TransferQueueManager(
            context = appContext,
            sftpRepository = sftpRepository,
        )
    }

    val openCodeApiClient: OpenCodeApiClient by lazy {
        OpenCodeApiClient(
            connectionManager = sshConnectionManager,
            secretStore = secretStore,
        )
    }

    val openCodeServerInstaller: com.example.data.opencode.OpenCodeServerInstaller by lazy {
        com.example.data.opencode.OpenCodeServerInstaller(
            connectionManager = sshConnectionManager,
            sftpRepository = sftpRepository,
            secretStore = secretStore,
        )
    }

    val appUpdateManager: AppUpdateManager by lazy {
        AppUpdateManager(
            context = appContext,
            preferencesRepository = preferencesRepository,
        )
    }

    val agentNotificationHelper: AgentNotificationHelper by lazy {
        AgentNotificationHelper(context = appContext)
    }

    val profileBackupManager: ProfileBackupManager by lazy {
        ProfileBackupManager(
            profileRepository = serverProfileRepository,
            secretStore = secretStore,
        )
    }

    val usageRepository: UsageRepository by lazy {
        UsageRepository(
            usageDao = database.usageDao(),
            pricingDao = database.modelPricingDao(),
            manualBalanceDao = database.manualBalanceDao(),
            apiClient = openCodeApiClient,
        )
    }
}
