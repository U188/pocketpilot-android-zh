package com.example.domain.model

data class PortForwardRule(
    val id: String,
    val label: String,
    val remoteHost: String = "127.0.0.1",
    val remotePort: Int,
    val localPort: Int,
    val preferredLocalPort: Int = 0,
    val isActive: Boolean = true,
    val errorMessage: String? = null,
) {
    val localUrl: String
        get() = "http://127.0.0.1:$localPort"

    val displayTitle: String
        get() = label.takeIf { it.isNotBlank() } ?: "Порт $remotePort"
}

data class DiscoveredRemotePort(
    val port: Int,
    val processName: String?,
    val bindAddress: String,
    val pid: Int? = null,
    val isOpenCodePort: Boolean = false,
) {
    val displayProcess: String
        get() = when {
            isOpenCodePort -> "OpenCode"
            !processName.isNullOrBlank() -> processName
            else -> "TCP :$port"
        }
}
