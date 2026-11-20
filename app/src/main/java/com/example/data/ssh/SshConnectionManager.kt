package com.example.data.ssh

import android.os.NetworkOnMainThreadException
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
import kotlinx.coroutines.CoroutineExceptionHandler
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
import java.security.GeneralSecurityException
import java.security.NoSuchAlgorithmException
import kotlin.math.min

class SshConnectionManager(
    private val profileRepository: ServerProfileRepository,
    private val knownHostsRepository: KnownHostsRepository,
    private val preferencesRepository: AppPreferencesRepository,
    private val networkMonitor: NetworkMonitor? = null,
) {
    private val _connectionState = MutableStateFlow<SshConnectionState>(SshConnectionState.Disconnected)
    val connectionState: StateFlow<SshConnectionState> = _connectionState.asStateFlow()

    private val _testState = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val testState: StateFlow<ConnectionTestState> = _testState.asStateFlow()

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable is CancellationException) return@CoroutineExceptionHandler
        val activeProfile = extractProfile(_connectionState.value)
        _connectionState.value = SshConnectionState.Error(
            profile = activeProfile,
            message = mapSshError(throwable),
        )
    }

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

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

    fun getActiveProfileId(): Long? = extractProfile(_connectionState.value)?.id

    fun connect(profileId: Long) {
        manualDisconnectRequested = false
        reconnectJob?.cancel()
        activeConnectJob?.cancel()

        activeConnectJob = managerScope.launch(Dispatchers.IO) {
            var loadedProfile: ServerProfile? = null
            try {
                val profile = profileRepository.getProfile(profileId)
                if (profile == null) {
                    _connectionState.value = SshConnectionState.Error(
                        profile = null,
                        message = "Профиль сервера не найден",
                    )
                    return@launch
                }
                loadedProfile = profile
                if (profile.host.isBlank()) {
                    _connectionState.value = SshConnectionState.Error(
                        profile = profile,
                        message = "Не указан адрес сервера (хост/IP).",
                    )
                    return@launch
                }
                if (profile.username.isBlank()) {
                    _connectionState.value = SshConnectionState.Error(
                        profile = profile,
                        message = "Не указано имя пользователя SSH.",
                    )
                    return@launch
                }
                val secrets = profileRepository.getSecrets(profileId)
                performConnect(profile, secrets)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                _connectionState.value = SshConnectionState.Error(
                    profile = loadedProfile,
                    message = mapSshError(t),
                )
            }
        }
    }

    fun reconnectActive() {
        val currentProfile = extractProfile(_connectionState.value) ?: return
        connect(currentProfile.id)
    }

    /**
     * Recreates the local SSH port-forwarding tunnel on the active SSH session (or performs a fresh
     * SSH reconnect if the underlying SSH transport has dropped). Returns the active local tunnel
     * port, or null if reconnection failed.
     */
    suspend fun recreateLocalTunnel(): Int? = withContext(Dispatchers.IO) {
        val currentProfile = extractProfile(_connectionState.value) ?: return@withContext null
        val client = getActiveSshClient()
        if (client != null) {
            val oldTunnel = activeTunnel
            activeTunnel = null
            runCatching { oldTunnel?.close() }
            val recreated = runCatching {
                SshTunnelForwarder.start(client, currentProfile.openCodePort, managerScope)
            }.getOrNull()
            if (recreated != null) {
                activeTunnel = recreated
                val currentState = _connectionState.value
                if (currentState is SshConnectionState.Connected) {
                    _connectionState.value = currentState.copy(localTunnelPort = recreated.localPort)
                }
                return@withContext recreated.localPort
            }
        }

        // Underlying SSH session dropped or tunnel creation failed — attempt one fresh reconnect
        val freshProfile = runCatching { profileRepository.getProfile(currentProfile.id) }.getOrNull()
            ?: currentProfile
        val secrets = runCatching { profileRepository.getSecrets(freshProfile.id) }.getOrDefault(ServerSecrets())
        performConnect(freshProfile, secrets)
        getActiveLocalTunnelPort()
    }

    fun disconnect() {
        manualDisconnectRequested = true
        pendingHostKeyDecision?.complete(false)
        pendingHostKeyDecision = null
        reconnectJob?.cancel()
        reconnectJob = null
        activeConnectJob?.cancel()
        activeConnectJob = null
        closeActiveResourcesAsync()
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
        testJob = managerScope.launch(Dispatchers.IO) {
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
                val banner = runCatching { client.transport.serverVersion }.getOrNull()
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
                closeResourcesSafely(tunnel, client)
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

    private suspend fun performConnect(profile: ServerProfile, secrets: ServerSecrets) = withContext(Dispatchers.IO) {
        closeActiveResourcesBlocking()
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

            runCatching {
                newClient.connection.keepAlive.keepAliveInterval = KEEP_ALIVE_INTERVAL_SEC
            }
            newTunnel = SshTunnelForwarder.start(newClient, profile.openCodePort, managerScope)
            val openCodeReachable = SshTunnelForwarder.probeTunnelEndpoint(newTunnel.localPort)

            attachDisconnectWatcher(newClient, profile)

            activeSshClient = newClient
            activeTunnel = newTunnel

            runCatching { profileRepository.markConnected(profile.id) }
            runCatching { preferencesRepository.setLastActiveProfileId(profile.id) }

            _connectionState.value = SshConnectionState.Connected(
                profile = profile.copy(lastConnectedAt = System.currentTimeMillis()),
                localTunnelPort = newTunnel.localPort,
                connectedAtMillis = System.currentTimeMillis(),
                latencyMs = latencyMs,
                serverVersion = runCatching { newClient.transport.serverVersion }.getOrNull(),
                openCodePortReachable = openCodeReachable,
            )
        } catch (cancelled: CancellationException) {
            closeResourcesSafely(newTunnel, newClient)
            throw cancelled
        } catch (t: Throwable) {
            closeResourcesSafely(newTunnel, newClient)
            _connectionState.value = SshConnectionState.Error(
                profile = profile,
                message = mapSshError(t),
            )
        }
    }

    private fun attachDisconnectWatcher(client: SSHClient, profile: ServerProfile) {
        runCatching {
            client.transport.disconnectListener = DisconnectListener { reason, message ->
                if (manualDisconnectRequested || activeSshClient !== client) return@DisconnectListener
                // Offload cleanup from SSHJ's Reader thread to avoid re-entrant deadlocks or uncaught errors.
                managerScope.launch(Dispatchers.IO) {
                    if (manualDisconnectRequested || activeSshClient !== client) return@launch
                    closeActiveResourcesBlocking()
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
        }
    }

    private fun scheduleReconnect(profile: ServerProfile, lastError: String?) {
        reconnectJob?.cancel()
        reconnectJob = managerScope.launch(Dispatchers.IO) {
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

                val freshProfile = runCatching { profileRepository.getProfile(profile.id) }.getOrNull() ?: break
                val secrets = runCatching { profileRepository.getSecrets(profile.id) }.getOrDefault(ServerSecrets())
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
        val config = SshSecurityProvider.createSshConfig()

        val client = SSHClient(config).apply {
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
            closeResourcesSafely(null, client)
            throw t
        }
    }

    private fun closeActiveResourcesAsync() {
        val tunnel = activeTunnel
        activeTunnel = null
        val client = activeSshClient
        activeSshClient = null
        if (tunnel != null || client != null) {
            managerScope.launch(Dispatchers.IO) {
                closeResourcesSafely(tunnel, client)
            }
        }
    }

    private fun closeActiveResourcesBlocking() {
        val tunnel = activeTunnel
        activeTunnel = null
        val client = activeSshClient
        activeSshClient = null
        closeResourcesSafely(tunnel, client)
    }

    private fun closeResourcesSafely(tunnel: SshTunnelForwarder?, client: SSHClient?) {
        runCatching { tunnel?.close() }
        runCatching { client?.disconnect() }
        runCatching { client?.close() }
    }

    private fun extractProfile(state: SshConnectionState): ServerProfile? = when (state) {
        is SshConnectionState.Connected -> state.profile
        is SshConnectionState.Connecting -> state.profile
        is SshConnectionState.AwaitingHostKeyVerification -> state.profile
        is SshConnectionState.Reconnecting -> state.profile
        is SshConnectionState.Error -> state.profile
        SshConnectionState.Disconnected -> null
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
            root is SecurityException ->
                "Ошибка доступа сети или безопасности: ${root.message ?: root.javaClass.simpleName}"
            root is NetworkOnMainThreadException ->
                "Сетевая ошибка потока выполнения. Попробуйте подключиться снова."
            root is NoSuchAlgorithmException || root is GeneralSecurityException ->
                "Ошибка криптопровайдера SSH: ${root.message ?: root.javaClass.simpleName}"
            root is LinkageError ->
                "Ошибка совместимости SSH-библиотеки: ${root.javaClass.simpleName}: ${root.message.orEmpty()}"
            t is UserAuthException ->
                "Ошибка авторизации SSH: неверный логин, пароль или ключ."
            t is TransportException && t.disconnectReason == DisconnectReason.HOST_KEY_NOT_VERIFIABLE ->
                "Ключ хоста не подтверждён пользователем."
            t is IllegalStateException && !t.message.isNullOrBlank() ->
                t.message!!
            else -> {
                val detail = t.message?.takeIf { it.isNotBlank() }
                    ?: root.message?.takeIf { it.isNotBlank() }
                if (detail != null) {
                    "Ошибка SSH (${root.javaClass.simpleName}): $detail"
                } else {
                    "Не удалось установить SSH-соединение (${root.javaClass.simpleName})"
                }
            }
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
