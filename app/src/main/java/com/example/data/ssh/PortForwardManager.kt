package com.example.data.ssh

import com.example.domain.model.DiscoveredRemotePort
import com.example.domain.model.PortForwardRule
import com.example.domain.model.SshConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Manages user-created SSH local port forwards (127.0.0.1:<local> -> <remoteHost>:<remotePort>)
 * over the active SSH session, plus auto-discovery of listening TCP ports on the VPS.
 */
class PortForwardManager(
    private val connectionManager: SshConnectionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val activeForwarders = LinkedHashMap<String, SshTunnelForwarder>()
    private val _rules = MutableStateFlow<List<PortForwardRule>>(emptyList())
    val rules: StateFlow<List<PortForwardRule>> = _rules.asStateFlow()

    private var boundProfileId: Long? = null
    private var boundConnectedAt: Long? = null

    init {
        scope.launch {
            connectionManager.connectionState.collect { state ->
                handleConnectionStateChanged(state)
            }
        }
    }

    private suspend fun handleConnectionStateChanged(state: SshConnectionState) {
        mutex.withLock {
            when (state) {
                is SshConnectionState.Connected -> {
                    val newProfileId = state.profile.id
                    val isDifferentServer = boundProfileId != null && boundProfileId != newProfileId
                    val isNewSession = boundConnectedAt != state.connectedAtMillis

                    if (isDifferentServer) {
                        closeAllForwardersLocked()
                        _rules.value = emptyList()
                    } else if (isNewSession && _rules.value.isNotEmpty()) {
                        // Re-establish existing port forwards automatically after SSH reconnect
                        closeAllForwardersLocked()
                        val client = connectionManager.getActiveSshClient()
                        if (client != null) {
                            val restored = _rules.value.map { rule ->
                                runCatching {
                                    val forwarder = SshTunnelForwarder.start(
                                        sshClient = client,
                                        remotePort = rule.remotePort,
                                        scope = scope,
                                        remoteHost = rule.remoteHost,
                                        preferredLocalPort = rule.preferredLocalPort,
                                    )
                                    activeForwarders[rule.id] = forwarder
                                    rule.copy(
                                        localPort = forwarder.localPort,
                                        isActive = true,
                                        errorMessage = null,
                                    )
                                }.getOrElse { err ->
                                    rule.copy(
                                        isActive = false,
                                        errorMessage = err.message ?: "无法恢复隧道",
                                    )
                                }
                            }
                            _rules.value = restored
                        }
                    }
                    boundProfileId = newProfileId
                    boundConnectedAt = state.connectedAtMillis
                }

                is SshConnectionState.Reconnecting -> {
                    closeAllForwardersLocked()
                    _rules.value = _rules.value.map { it.copy(isActive = false) }
                }

                is SshConnectionState.Disconnected,
                is SshConnectionState.Error,
                -> {
                    closeAllForwardersLocked()
                    boundConnectedAt = null
                    if (state is SshConnectionState.Disconnected) {
                        boundProfileId = null
                        _rules.value = emptyList()
                    } else {
                        _rules.value = _rules.value.map { it.copy(isActive = false) }
                    }
                }

                else -> Unit
            }
        }
    }

    suspend fun startForward(
        remotePort: Int,
        remoteHost: String = "127.0.0.1",
        preferredLocalPort: Int = 0,
        label: String = "",
    ): Result<PortForwardRule> = withContext(Dispatchers.IO) {
        if (remotePort !in 1..65535) {
            return@withContext Result.failure(IllegalArgumentException("端口必须在 1–65535 范围内"))
        }
        val safeHost = PortForwardParser.validateRemoteHost(remoteHost)
            ?: return@withContext Result.failure(IllegalArgumentException("远程主机地址无效"))

        mutex.withLock {
            val client = connectionManager.getActiveSshClient()
                ?: return@withContext Result.failure(IllegalStateException("没有活动的 SSH 连接"))

            // Reuse if an identical active forward already exists
            val existing = _rules.value.firstOrNull {
                it.isActive && it.remoteHost == safeHost && it.remotePort == remotePort
            }
            if (existing != null) {
                return@withContext Result.success(existing)
            }

            runCatching {
                val forwarder = try {
                    SshTunnelForwarder.start(
                        sshClient = client,
                        remotePort = remotePort,
                        scope = scope,
                        remoteHost = safeHost,
                        preferredLocalPort = preferredLocalPort,
                    )
                } catch (e: IOException) {
                    if (preferredLocalPort in 1..65535) {
                        throw IOException("本地端口 $preferredLocalPort 已被占用。留空可自动选择端口。", e)
                    }
                    throw e
                }

                val id = UUID.randomUUID().toString()
                activeForwarders[id] = forwarder
                val rule = PortForwardRule(
                    id = id,
                    label = label.trim(),
                    remoteHost = safeHost,
                    remotePort = remotePort,
                    localPort = forwarder.localPort,
                    preferredLocalPort = preferredLocalPort.takeIf { it in 1..65535 } ?: 0,
                    isActive = true,
                )
                // Remove any stopped duplicate rule for the same target
                val filtered = _rules.value.filterNot {
                    !it.isActive && it.remoteHost == safeHost && it.remotePort == remotePort
                }
                _rules.value = listOf(rule) + filtered
                rule
            }
        }
    }

    suspend fun stopForward(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            activeForwarders.remove(id)?.let { runCatching { it.close() } }
            _rules.value = _rules.value.filterNot { it.id == id }
        }
    }

    suspend fun stopAllForwards() = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeAllForwardersLocked()
            _rules.value = emptyList()
        }
    }

    suspend fun discoverListeningPorts(): Result<List<DiscoveredRemotePort>> = withContext(Dispatchers.IO) {
        val client = connectionManager.getActiveSshClient()
            ?: return@withContext Result.failure(IllegalStateException("没有活动的 SSH 连接"))
        val openCodePort = (connectionManager.connectionState.value as? SshConnectionState.Connected)
            ?.profile?.openCodePort

        runCatching {
            val command = "ss -tlnp 2>/dev/null || netstat -tlnp 2>/dev/null || ss -tln 2>/dev/null"
            val output = client.startSession().use { session ->
                val cmd = session.exec(command)
                val stdout = cmd.inputStream.bufferedReader().use { it.readText() }
                cmd.join(8, TimeUnit.SECONDS)
                stdout
            }
            PortForwardParser.parseListeningPorts(output = output, openCodePort = openCodePort)
        }
    }

    private fun closeAllForwardersLocked() {
        activeForwarders.values.forEach { runCatching { it.close() } }
        activeForwarders.clear()
    }
}
