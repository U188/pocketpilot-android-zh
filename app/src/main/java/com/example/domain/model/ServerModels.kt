package com.example.domain.model

enum class AuthType {
    PASSWORD,
    PRIVATE_KEY,
}

enum class SshKeyAlgorithm(val displayName: String) {
    ED25519("Ed25519"),
    RSA_4096("RSA 4096"),
}

data class ServerProfile(
    val id: Long = 0L,
    val name: String,
    val host: String,
    val sshPort: Int = 22,
    val openCodePort: Int = 4096,
    val username: String,
    val authType: AuthType = AuthType.PRIVATE_KEY,
    val hasPassphrase: Boolean = false,
    val autoReconnect: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastConnectedAt: Long? = null,
) {
    val hostPortKey: String
        get() = "$host:$sshPort"
}

data class ServerSecrets(
    val password: String? = null,
    val privateKeyPem: String? = null,
    val passphrase: String? = null,
)

data class KnownHost(
    val hostPortKey: String,
    val algorithm: String,
    val fingerprintSha256: String,
    val publicKeyBase64: String,
    val firstSeenAt: Long,
    val lastVerifiedAt: Long,
)

data class GeneratedSshKey(
    val algorithm: SshKeyAlgorithm,
    val privateKeyPem: String,
    val publicKeyOpenSsh: String,
    val fingerprintSha256: String,
)
