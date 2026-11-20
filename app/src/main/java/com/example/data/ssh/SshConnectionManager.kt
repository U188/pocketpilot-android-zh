package com.example.data.ssh

import com.example.data.local.AppPreferencesRepository
import com.example.data.network.NetworkMonitor
import com.example.data.repository.KnownHostsRepository
import com.example.data.repository.ServerProfileRepository
import com.example.domain.model.AuthType
import com.example.domain.model.ConnectionTestState
import com.example.domain.model.HostKeyPrompt
import com.example.domain.model.ServerProfile
import com.example.domain.model.ServerSecrets
import com.example.domain.model.SshConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.transport.DisconnectListener
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.math.min

class SshConnectionManager(
    private val profileRepository: ServerProfileRepository,
    private val knownHostsRepository: KnownHostsRepository,
    private val preferencesRepository: AppPreferencesRepository,
    private val networkMonitor: NetworkMonitor? = null,
) {
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        networkMonitor?.let { monitor ->
            managerScope.launch {
                var wasOnline = monitor.isOnline.value
                monitor.isOnline.collect { online ->
                    if (!wasOnline && online && !manualDisconnectRequested) {
                        val state = _connectionState.value
                        when (state) {
                            is SshConnectionState.Reconnecting -> {
                                connect(state.profile.id)
                            }
                            is SshConnectionState.Error -> {
                                val prof = state.profile
                                if (prof != null && prof.autoReconnect) {
                                    connect(prof.id)
                                }
                            }
                            else -> Unit
                        }
                    }
                    wasOnline = online
                }
            }
        }
    }

    private val _connectionState = MutableStateFlow<SshConnectionState>(SshConnectionState.Disconnected)
    val connectionState: StateFlow<SshConnectionState> = _connectionState.asStateFlow()

    private val _testState = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val testState: StateFlow<ConnectionTestState> = _testState.asStateFlow()

    @Volatile
    private var activeSshClient: SSHClient? = null

    @Volatile
    private var activeTunnel: SshTunnelForwarder? = null

    private var activeConnectJob: Job? = null
    private var reconnectJob: Job? = null
    private var testJob: Job? = null

    @Volatile
    private var pendingHostKeyDecision: CompletableDeferred<Boolean>? = null

    @Volatile
    private var pendingTestHostKeyDecision: CompletableDeferred<Boolean>? = null

    @Volatile
    private var manualDisconnectRequested = false

    fun getActiveSshClient(): SSHClient? = activeSshClient?.takeIf { it.isConnected && it.isAuthenticated }

    fun getActiveLocalTunnelPort(): Int? = activeTunnel?.localPort

    fun connect(profileId: Long) {
        manualDisconnectRequested = false
        reconnectJob?.cancel()
        activeConnectJob?.cancel()

        activeConnectJob = managerScope.launch {
            val profile = profileRepository.getProfile(profileId)
            if (profile == null) {
                _connectionState.value = SshConnectionState.Error(
                    profile = null,
                    message = "Профиль сервера не найден",
                )
                return@launch
            }
            val secrets = profileRepository.getSecrets(profileId)
            performConnect(profile, secrets)
        }
    }

    fun reconnectActive() {
        val currentProfile = when (val state = _connectionState.value) {
            is SshConnectionState.Connected -> state.profile
            is SshConnectionState.Reconnecting -> state.profile
            is SshConnectionState.Error -> state.profile
            is SshConnectionState.Connecting -> state.profile
            is SshConnectionState.AwaitingHostKeyVerification -> state.profile
            SshConnectionState.Disconnected -> null
        } ?: return
        connect(currentProfile.id)
    }

    fun disconnect() {
        manualDisconnectRequested = true
        pendingHostKeyDecision?.complete(false)
        pendingHostKeyDecision = null
        reconnectJob?.cancel()
        reconnectJob = null
        activeConnectJob?.cancel()
        activeConnectJob = null
        closeActiveResources()
        _connectionState.value = SshConnectionState.Disconnected
    }

    fun resolveHostKeyDecision(approved: Boolean) {
        pendingHostKeyDecision?.complete(approved)
        pendingHostKeyDecision = null
    }

    fun resolveTestHostKeyDecision(approved: Boolean) {
        pendingTestHostKeyDecision?.complete(approved)
        pendingTestHostKeyDecision = null
    }

    fun testConnection(profile: ServerProfile, secrets: ServerSecrets) {
        cancelConnectionTest()
        testJob = managerScope.launch {
            _testState.value = ConnectionTestState.Testing
            var client: SSHClient? = null
            var tunnel: SshTunnelForwarder? = null
            try {
                val startedAt = System.currentTimeMillis()
                client = openAuthenticatedClient(
                    profile = profile,
                    secrets = secrets,
                    onPromptHostKey = { prompt ->
                        val deferred = CompletableDeferred<Boolean>()
                        pendingTestHostKeyDecision = deferred
                        _testState.value = ConnectionTestState.AwaitingHostKey(prompt)
                        val approved = deferred.await()
                        if (approved) {
                            _testState.value = ConnectionTestState.Testing
                        }
                        approved
                    },
                )
                val latencyMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
                val banner = client.transport.serverVersion
                tunnel = SshTunnelForwarder.start(client, profile.openCodePort, this)
                val openCodeReachable = SshTunnelForwarder.probeTunnelEndpoint(tunnel.localPort)
                _testState.value = ConnectionTestState.Success(
                    latencyMs = latencyMs,
                    serverBanner = banner,
                    openCodeReachable = openCodeReachable,
                    openCodePort = profile.openCodePort,
                )
            } catch (cancelled: CancellationException) {
                _testState.value = ConnectionTestState.Idle
                throw cancelled
            } catch (t: Throwable) {
                _testState.value = ConnectionTestState.Failure(mapSshError(t))
            } finally {
                pendingTestHostKeyDecision?.complete(false)
                pendingTestHostKeyDecision = null
                runCatching { tunnel?.close() }
                runCatching { client?.disconnect() }
                runCatching { client?.close() }
            }
        }
    }

    fun cancelConnectionTest() {
        pendingTestHostKeyDecision?.complete(false)
        pendingTestHostKeyDecision = null
        testJob?.cancel()
        testJob = null
        _testState.value = ConnectionTestState.Idle
    }

    fun resetTestState() {
        if (_testState.value !is ConnectionTestState.Testing &&
            _testState.value !is ConnectionTestState.AwaitingHostKey
        ) {
            _testState.value = ConnectionTestState.Idle
        }
    }

    private suspend fun performConnect(profile: ServerProfile, secrets: ServerSecrets) {
        closeActiveResources()
        _connectionState.value = SshConnectionState.Connecting(profile)

        var newClient: SSHClient? = null
        var newTunnel: SshTunnelForwarder? = null
        try {
            val startedAt = System.currentTimeMillis()
            newClient = openAuthenticatedClient(
                profile = profile,
                secrets = secrets,
                onPromptHostKey = { prompt ->
                    val deferred = CompletableDeferred<Boolean>()
                    pendingHostKeyDecision = deferred
                    _connectionState.value = SshConnectionState.AwaitingHostKeyVerification(
                        profile = profile,
                        prompt = prompt,
                    )
                    val approved = deferred.await()
                    if (approved) {
                        _connectionState.value = SshConnectionState.Connecting(profile)
                    }
                    approved
                },
            )
            val latencyMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)

            newClient.connection.keepAlive.keepAliveInterval = KEEP_ALIVE_INTERVAL_SEC
            newTunnel = SshTunnelForwarder.start(newClient, profile.openCodePort, managerScope)
            val openCodeReachable = SshTunnelForwarder.probeTunnelEndpoint(newTunnel.localPort)

            attachDisconnectWatcher(newClient, profile)

            activeSshClient = newClient
            activeTunnel = newTunnel

            profileRepository.markConnected(profile.id)
            preferencesRepository.setLastActiveProfileId(profile.id)

            _connectionState.value = SshConnectionState.Connected(
                profile = profile.copy(lastConnectedAt = System.currentTimeMillis()),
                localTunnelPort = newTunnel.localPort,
                connectedAtMillis = System.currentTimeMillis(),
                latencyMs = latencyMs,
                serverVersion = newClient.transport.serverVersion,
                openCodePortReachable = openCodeReachable,
            )
        } catch (cancelled: CancellationException) {
            runCatching { newTunnel?.close() }
            runCatching { newClient?.disconnect() }
            runCatching { newClient?.close() }
            throw cancelled
        } catch (t: Throwable) {
            runCatching { newTunnel?.close() }
            runCatching { newClient?.disconnect() }
            runCatching { newClient?.close() }
            _connectionState.value = SshConnectionState.Error(
                profile = profile,
                message = mapSshError(t),
            )
        }
    }

    private fun attachDisconnectWatcher(client: SSHClient, profile: ServerProfile) {
        client.transport.disconnectListener = DisconnectListener { reason, message ->
            if (manualDisconnectRequested || activeSshClient !== client) return@DisconnectListener
            closeActiveResources()
            if (profile.autoReconnect && reason != DisconnectReason.BY_APPLICATION) {
                scheduleReconnect(profile, message)
            } else {
                _connectionState.value = SshConnectionState.Error(
                    profile = profile,
                    message = message?.takeIf { it.isNotBlank() } ?: "Соединение разорвано сервером",
                )
            }
        }
    }

    private fun scheduleReconnect(profile: ServerProfile, lastError: String?) {
        reconnectJob?.cancel()
        reconnectJob = managerScope.launch {
            var attempt = 1
            while (isActive && !manualDisconnectRequested) {
                _connectionState.value = SshConnectionState.Reconnecting(
                    profile = profile,
                    attempt = attempt,
                    lastError = lastError,
                )
                val backoffMs = min(INITIAL_BACKOFF_MS shl (attempt - 1).coerceAtMost(4), MAX_BACKOFF_MS)
                delay(backoffMs)
                if (manualDisconnectRequested) break

                val freshProfile = profileRepository.getProfile(profile.id) ?: break
                val secrets = profileRepository.getSecrets(profile.id)
                performConnect(freshProfile, secrets)
                if (_connectionState.value is SshConnectionState.Connected) {
                    break
                }
                attempt++
            }
        }
    }

    private suspend fun openAuthenticatedClient(
        profile: ServerProfile,
        secrets: ServerSecrets,
        onPromptHostKey: suspend (HostKeyPrompt) -> Boolean,
    ): SSHClient = withContext(Dispatchers.IO) {
        SshSecurityProvider.ensureInitialized()

        val client = SSHClient(DefaultConfig()).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            timeout = SOCKET_TIMEOUT_MS
            addHostKeyVerifier(
                SshHostKeyVerifier(
                    knownHostsRepository = knownHostsRepository,
                    onRequestUserApproval = onPromptHostKey,
                ),
            )
        }

        try {
            client.connect(profile.host.trim(), profile.sshPort)

            when (profile.authType) {
                AuthType.PASSWORD -> {
                    val password = secrets.password
                        ?: throw IllegalStateException("Пароль не задан для профиля")
                    client.authPassword(profile.username.trim(), password)
                }

                AuthType.PRIVATE_KEY -> {
                    val privateKeyPem = secrets.privateKeyPem
                        ?: throw IllegalStateException("Приватный ключ не задан для профиля")
                    val passwordFinder = secrets.passphrase
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { PasswordUtils.createOneOff(it.toCharArray()) }
                    val keyProvider = client.loadKeys(privateKeyPem, null, passwordFinder)
                    client.authPublickey(profile.username.trim(), keyProvider)
                }
            }
            client
        } catch (t: Throwable) {
            runCatching { client.disconnect() }
            runCatching { client.close() }
            throw t
        }
    }

    private fun closeActiveResources() {
        val tunnel = activeTunnel
        activeTunnel = null
        runCatching { tunnel?.close() }

        val client = activeSshClient
        activeSshClient = null
        runCatching { client?.disconnect() }
        runCatching { client?.close() }
    }

    private fun mapSshError(t: Throwable): String {
        val root = generateSequence(t) { it.cause }.lastOrNull() ?: t
        return when {
            root is UnknownHostException ->
                "Не удалось разрешить хост. Проверьте адрес сервера и интернет."
            root is ConnectException ->
                "Сервер отклонил подключение. Проверьте IP, порт SSH и firewall."
            root is NoRouteToHostException ->
                "Нет маршрута к серверу. Проверьте сеть или VPN."
            root is SocketTimeoutException ->
                "Превышено время ожидания ответа от сервера."
            t is UserAuthException ->
                "Ошибка авторизации SSH: неверный логин, пароль или ключ."
            t is TransportException && t.disconnectReason == DisconnectReason.HOST_KEY_NOT_VERIFIABLE ->
                "Ключ хоста не подтверждён пользователем."
            t is IllegalStateException && !t.message.isNullOrBlank() ->
                t.message!!
            else ->
                t.message?.takeIf { it.isNotBlank() } ?: "Не удалось установить SSH-соединение"
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val KEEP_ALIVE_INTERVAL_SEC = 15
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 30_000L
    }
}
