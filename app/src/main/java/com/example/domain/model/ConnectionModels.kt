package com.example.domain.model

data class HostKeyPrompt(
    val host: String,
    val port: Int,
    val algorithm: String,
    val sha256Fingerprint: String,
    val existingFingerprint: String? = null,
) {
    val isKeyChanged: Boolean
        get() = existingFingerprint != null && existingFingerprint != sha256Fingerprint

    val hostPortKey: String
        get() = "$host:$port"
}

sealed interface SshConnectionState {
    data object Disconnected : SshConnectionState

    data class Connecting(
        val profile: ServerProfile,
    ) : SshConnectionState

    data class AwaitingHostKeyVerification(
        val profile: ServerProfile,
        val prompt: HostKeyPrompt,
    ) : SshConnectionState

    data class Connected(
        val profile: ServerProfile,
        val localTunnelPort: Int,
        val connectedAtMillis: Long,
        val latencyMs: Long,
        val serverVersion: String?,
        val openCodePortReachable: Boolean,
    ) : SshConnectionState

    data class Reconnecting(
        val profile: ServerProfile,
        val attempt: Int,
        val lastError: String?,
    ) : SshConnectionState

    data class Error(
        val profile: ServerProfile?,
        val message: String,
    ) : SshConnectionState
}

sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState

    data object Testing : ConnectionTestState

    data class AwaitingHostKey(
        val prompt: HostKeyPrompt,
    ) : ConnectionTestState

    data class Success(
        val latencyMs: Long,
        val serverBanner: String?,
        val openCodeReachable: Boolean,
        val openCodePort: Int,
    ) : ConnectionTestState

    data class Failure(
        val message: String,
    ) : ConnectionTestState
}
