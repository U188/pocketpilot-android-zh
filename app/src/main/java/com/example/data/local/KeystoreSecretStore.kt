package com.example.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.domain.model.ServerSecrets
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class OpenCodeServerAuth(
    val username: String = "opencode",
    val password: String,
)

/**
 * Stores SSH credentials and OpenCode server Basic Auth passwords encrypted with an AES-256-GCM key
 * backed by Android Keystore. Backed by a dedicated SharedPreferences file excluded from backup
 * and device transfer. Secrets are never logged.
 */
class KeystoreSecretStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_FILE_NAME,
        Context.MODE_PRIVATE,
    )

    private val lock = Any()

    fun saveSecrets(
        profileId: Long,
        password: String?,
        privateKeyPem: String?,
        passphrase: String?,
    ) {
        synchronized(lock) {
            val editor = prefs.edit()
            putOrRemove(editor, passwordKey(profileId), password)
            putOrRemove(editor, privateKeyKey(profileId), privateKeyPem)
            putOrRemove(editor, passphraseKey(profileId), passphrase)
            editor.apply()
        }
    }

    fun getSecrets(profileId: Long): ServerSecrets = synchronized(lock) {
        ServerSecrets(
            password = readDecrypted(passwordKey(profileId)),
            privateKeyPem = readDecrypted(privateKeyKey(profileId)),
            passphrase = readDecrypted(passphraseKey(profileId)),
        )
    }

    fun hasPassword(profileId: Long): Boolean = prefs.contains(passwordKey(profileId))

    fun hasPrivateKey(profileId: Long): Boolean = prefs.contains(privateKeyKey(profileId))

    fun hasPassphrase(profileId: Long): Boolean = prefs.contains(passphraseKey(profileId))

    fun saveOpenCodeServerAuth(
        profileId: Long,
        username: String?,
        password: String?,
    ) {
        synchronized(lock) {
            val editor = prefs.edit()
            if (password.isNullOrEmpty()) {
                editor.remove(openCodeUserKey(profileId))
                editor.remove(openCodePassKey(profileId))
            } else {
                putOrRemove(editor, openCodeUserKey(profileId), username?.ifBlank { "opencode" } ?: "opencode")
                putOrRemove(editor, openCodePassKey(profileId), password)
            }
            editor.apply()
        }
    }

    fun getOpenCodeServerAuth(profileId: Long): OpenCodeServerAuth? = synchronized(lock) {
        val pass = readDecrypted(openCodePassKey(profileId)) ?: return@synchronized null
        if (pass.isEmpty()) return@synchronized null
        val user = readDecrypted(openCodeUserKey(profileId))?.ifBlank { "opencode" } ?: "opencode"
        OpenCodeServerAuth(username = user, password = pass)
    }

    fun clearOpenCodeServerAuth(profileId: Long) {
        synchronized(lock) {
            prefs.edit()
                .remove(openCodeUserKey(profileId))
                .remove(openCodePassKey(profileId))
                .apply()
        }
    }

    fun deleteSecrets(profileId: Long) {
        synchronized(lock) {
            prefs.edit()
                .remove(passwordKey(profileId))
                .remove(privateKeyKey(profileId))
                .remove(passphraseKey(profileId))
                .remove(openCodeUserKey(profileId))
                .remove(openCodePassKey(profileId))
                .apply()
        }
    }

    private fun putOrRemove(
        editor: android.content.SharedPreferences.Editor,
        key: String,
        value: String?,
    ) {
        if (value.isNullOrEmpty()) {
            editor.remove(key)
        } else {
            editor.putString(key, encrypt(value))
        }
    }

    private fun readDecrypted(key: String): String? {
        val encoded = prefs.getString(key, null) ?: return null
        return runCatching { decrypt(encoded) }.getOrNull()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val combined = ByteArray(1 + iv.size + ciphertext.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(ciphertext, 0, combined, 1 + iv.size, ciphertext.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        val ivLength = combined[0].toInt() and 0xFF
        val iv = combined.copyOfRange(1, 1 + ivLength)
        val ciphertext = combined.copyOfRange(1 + ivLength, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val plaintextBytes = cipher.doFinal(ciphertext)
        return String(plaintextBytes, StandardCharsets.UTF_8)
    }

    private fun passwordKey(profileId: Long) = "profile_${profileId}_password"
    private fun privateKeyKey(profileId: Long) = "profile_${profileId}_private_key"
    private fun passphraseKey(profileId: Long) = "profile_${profileId}_passphrase"
    private fun openCodeUserKey(profileId: Long) = "profile_${profileId}_opencode_user"
    private fun openCodePassKey(profileId: Long) = "profile_${profileId}_opencode_pass"

    companion object {
        const val PREFS_FILE_NAME = "opencode_secrets_vault"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "opencode_ssh_vault_aes256"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
