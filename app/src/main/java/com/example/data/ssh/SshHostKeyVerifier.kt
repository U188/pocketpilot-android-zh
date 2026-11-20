package com.example.data.ssh

import com.example.data.repository.KnownHostsRepository
import com.example.domain.model.HostKeyPrompt
import com.example.domain.model.KnownHost
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

class SshHostKeyVerifier(
    private val knownHostsRepository: KnownHostsRepository,
    private val onRequestUserApproval: suspend (HostKeyPrompt) -> Boolean,
) : HostKeyVerifier {

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        return runCatching {
            val hostPortKey = "$hostname:$port"
            val keyType = KeyType.fromKey(key)
            val algorithm = keyType.toString()
            val wireBytes = Buffer.PlainBuffer().putPublicKey(key).compactData
            val sha256Digest = MessageDigest.getInstance("SHA-256").digest(wireBytes)
            val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(sha256Digest)
            val publicKeyBase64 = Base64.getEncoder().encodeToString(wireBytes)

            runBlocking {
                val existing = knownHostsRepository.find(hostPortKey)
                if (existing != null && existing.fingerprintSha256 == fingerprint) {
                    knownHostsRepository.touch(hostPortKey)
                    return@runBlocking true
                }

                val prompt = HostKeyPrompt(
                    host = hostname,
                    port = port,
                    algorithm = algorithm,
                    sha256Fingerprint = fingerprint,
                    existingFingerprint = existing?.fingerprintSha256,
                )

                val approved = onRequestUserApproval(prompt)
                if (approved) {
                    val now = System.currentTimeMillis()
                    knownHostsRepository.trust(
                        KnownHost(
                            hostPortKey = hostPortKey,
                            algorithm = algorithm,
                            fingerprintSha256 = fingerprint,
                            publicKeyBase64 = publicKeyBase64,
                            firstSeenAt = existing?.firstSeenAt ?: now,
                            lastVerifiedAt = now,
                        ),
                    )
                }
                approved
            }
        }.getOrDefault(false)
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> {
        return runCatching {
            val hostPortKey = "$hostname:$port"
            val existing = runBlocking { knownHostsRepository.find(hostPortKey) }
            if (existing != null) listOf(existing.algorithm) else emptyList()
        }.getOrDefault(emptyList())
    }
}
