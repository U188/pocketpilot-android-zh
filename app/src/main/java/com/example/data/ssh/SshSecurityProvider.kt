package com.example.data.ssh

import net.i2p.crypto.eddsa.EdDSASecurityProvider
import net.schmizz.sshj.Config
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.random.JCERandom
import net.schmizz.sshj.transport.random.SingletonRandomFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

object SshSecurityProvider {
    @Volatile
    private var initialized = false

    fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            // Prevent SSHJ from attempting its own dynamic BC registration against Android's runtime.
            runCatching { SecurityUtils.setRegisterBouncyCastle(false) }

            // Replace Android's stripped-down built-in BC provider with full BouncyCastle
            // appended at the end so AndroidKeyStore and Conscrypt keep top priority
            // (essential for KeystoreSecretStore AES-GCM and OkHttp TLS).
            val existingBc = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
            val bcReady = runCatching {
                if (existingBc == null || existingBc.javaClass != BouncyCastleProvider::class.java) {
                    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                    Security.addProvider(BouncyCastleProvider())
                }
                true
            }.getOrDefault(false)

            if (bcReady) {
                // Explicitly direct SSHJ JCE lookups to our full "BC" provider so modern
                // SSH algorithms (X25519, ECDSA, ChaCha20, AES-GCM, OpenSSH PEM) resolve reliably.
                runCatching { SecurityUtils.setSecurityProvider(BouncyCastleProvider.PROVIDER_NAME) }
            }

            runCatching {
                if (Security.getProvider(EdDSASecurityProvider.PROVIDER_NAME) == null) {
                    Security.addProvider(EdDSASecurityProvider())
                }
            }

            initialized = true
        }
    }

    /**
     * Builds an Android-safe SSHJ Config using full BouncyCastle + EdDSA providers
     * and Android's JCE SecureRandom so SSHClient never trips over desktop-only defaults.
     */
    fun createSshConfig(): Config {
        ensureInitialized()
        return object : DefaultConfig() {
            override fun initRandomFactory() {
                randomFactory = SingletonRandomFactory(JCERandom.Factory())
            }
        }
    }
}
