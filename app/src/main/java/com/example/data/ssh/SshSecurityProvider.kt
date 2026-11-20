package com.example.data.ssh

import net.i2p.crypto.eddsa.EdDSASecurityProvider
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

object SshSecurityProvider {
    @Volatile
    private var initialized = false

    fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            // Replace Android's stripped-down built-in BC provider with full BouncyCastle
            // appended at the end so AndroidKeyStore and Conscrypt keep priority.
            val existingBc = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
            if (existingBc == null || existingBc.javaClass != BouncyCastleProvider::class.java) {
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                Security.addProvider(BouncyCastleProvider())
            }
            if (Security.getProvider(EdDSASecurityProvider.PROVIDER_NAME) == null) {
                Security.addProvider(EdDSASecurityProvider())
            }
            initialized = true
        }
    }
}
