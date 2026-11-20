package com.example.data.ssh

import com.example.domain.model.GeneratedSshKey
import com.example.domain.model.SshKeyAlgorithm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class SshKeyGenerator {

    suspend fun generate(
        algorithm: SshKeyAlgorithm,
        comment: String = "opencode-android",
    ): GeneratedSshKey = withContext(Dispatchers.Default) {
        SshSecurityProvider.ensureInitialized()
        val random = SecureRandom()

        val (privateParam: AsymmetricKeyParameter, publicParam: AsymmetricKeyParameter, keyType: String) =
            when (algorithm) {
                SshKeyAlgorithm.ED25519 -> {
                    val generator = Ed25519KeyPairGenerator()
                    generator.init(Ed25519KeyGenerationParameters(random))
                    val pair = generator.generateKeyPair()
                    Triple(pair.private, pair.public, "ssh-ed25519")
                }

                SshKeyAlgorithm.RSA_4096 -> {
                    val generator = RSAKeyPairGenerator()
                    generator.init(
                        RSAKeyGenerationParameters(
                            BigInteger.valueOf(0x10001),
                            random,
                            4096,
                            80,
                        ),
                    )
                    val pair = generator.generateKeyPair()
                    Triple(pair.private, pair.public, "ssh-rsa")
                }
            }

        val privateWireBytes = OpenSSHPrivateKeyUtil.encodePrivateKey(privateParam)
        val publicWireBytes = OpenSSHPublicKeyUtil.encodePublicKey(publicParam)

        val privateKeyPem = formatOpenSshPrivateKeyPem(privateWireBytes)
        val publicBase64 = Base64.getEncoder().encodeToString(publicWireBytes)
        val sanitizedComment = comment.trim().ifEmpty { "opencode-android" }
        val publicKeyOpenSsh = "$keyType $publicBase64 $sanitizedComment"

        val digest = MessageDigest.getInstance("SHA-256").digest(publicWireBytes)
        val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)

        GeneratedSshKey(
            algorithm = algorithm,
            privateKeyPem = privateKeyPem,
            publicKeyOpenSsh = publicKeyOpenSsh,
            fingerprintSha256 = fingerprint,
        )
    }

    private fun formatOpenSshPrivateKeyPem(encodedBytes: ByteArray): String {
        val base64 = Base64.getEncoder().encodeToString(encodedBytes)
        val body = base64.chunked(70).joinToString("\n")
        return buildString {
            appendLine("-----BEGIN OPENSSH PRIVATE KEY-----")
            appendLine(body)
            append("-----END OPENSSH PRIVATE KEY-----")
        }
    }
}
