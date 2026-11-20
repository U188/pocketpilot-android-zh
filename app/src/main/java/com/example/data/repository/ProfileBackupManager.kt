package com.example.data.repository

import com.example.data.local.KeystoreSecretStore
import com.example.domain.model.AuthType
import com.example.domain.model.ServerProfile
import com.example.security.ProfileBackupCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ProfileImportResult(
    val importedCount: Int,
    val updatedCount: Int,
) {
    val totalCount: Int
        get() = importedCount + updatedCount
}

/**
 * Exports and imports server profiles (including SSH passwords, private keys, passphrases, and
 * OpenCode server Basic Auth credentials) inside an AES-256-GCM password-protected envelope.
 * Plaintext secrets are never persisted outside KeystoreSecretStore.
 */
class ProfileBackupManager(
    private val profileRepository: ServerProfileRepository,
    private val secretStore: KeystoreSecretStore,
) {

    suspend fun exportEncryptedBackup(password: String): String = withContext(Dispatchers.IO) {
        val profiles = profileRepository.profiles.first()
        require(profiles.isNotEmpty()) { "Нет профилей серверов для экспорта" }

        val profilesArray = JSONArray()
        for (profile in profiles) {
            val secrets = secretStore.getSecrets(profile.id)
            val openCodeAuth = secretStore.getOpenCodeServerAuth(profile.id)
            val obj = JSONObject().apply {
                put("name", profile.name)
                put("host", profile.host)
                put("sshPort", profile.sshPort)
                put("openCodePort", profile.openCodePort)
                put("username", profile.username)
                put("authType", profile.authType.name)
                put("autoReconnect", profile.autoReconnect)
                if (!secrets.password.isNullOrEmpty()) put("password", secrets.password)
                if (!secrets.privateKeyPem.isNullOrEmpty()) put("privateKeyPem", secrets.privateKeyPem)
                if (!secrets.passphrase.isNullOrEmpty()) put("passphrase", secrets.passphrase)
                if (openCodeAuth != null && openCodeAuth.password.isNotEmpty()) {
                    put("openCodeAuthUser", openCodeAuth.username)
                    put("openCodeAuthPassword", openCodeAuth.password)
                }
            }
            profilesArray.put(obj)
        }

        val payload = JSONObject().apply {
            put("exportedAtMillis", System.currentTimeMillis())
            put("profiles", profilesArray)
        }.toString()

        ProfileBackupCrypto.encrypt(payload, password)
    }

    suspend fun importEncryptedBackup(
        encryptedContent: String,
        password: String,
    ): ProfileImportResult = withContext(Dispatchers.IO) {
        val decryptedJson = ProfileBackupCrypto.decrypt(encryptedContent, password)
        val root = runCatching { JSONObject(decryptedJson) }.getOrElse {
            throw IllegalArgumentException("Расшифрованный файл повреждён")
        }
        val profilesArr = root.optJSONArray("profiles")
            ?: throw IllegalArgumentException("В файле резервной копии не найден список профилей")

        val existing = profileRepository.profiles.first()
        var imported = 0
        var updated = 0

        for (i in 0 until profilesArr.length()) {
            val item = profilesArr.optJSONObject(i) ?: continue
            val name = item.optString("name").trim()
            val host = item.optString("host").trim()
            val username = item.optString("username").trim()
            if (name.isEmpty() || host.isEmpty() || username.isEmpty()) continue

            val sshPort = item.optInt("sshPort", 22).coerceIn(1, 65535)
            val openCodePort = item.optInt("openCodePort", 4096).coerceIn(1, 65535)
            val authType = runCatching {
                AuthType.valueOf(item.optString("authType", AuthType.PRIVATE_KEY.name))
            }.getOrDefault(AuthType.PRIVATE_KEY)
            val autoReconnect = item.optBoolean("autoReconnect", true)

            val passwordSecret = item.optString("password").takeIf { it.isNotEmpty() }
            val privateKeyPem = item.optString("privateKeyPem").takeIf { it.isNotEmpty() }
            val passphrase = item.optString("passphrase").takeIf { it.isNotEmpty() }
            val openCodeAuthUser = item.optString("openCodeAuthUser").takeIf { it.isNotBlank() }
            val openCodeAuthPass = item.optString("openCodeAuthPassword").takeIf { it.isNotEmpty() }

            val matched = existing.firstOrNull {
                it.name.equals(name, ignoreCase = true) &&
                    it.host.equals(host, ignoreCase = true) &&
                    it.sshPort == sshPort &&
                    it.username == username
            }

            val targetProfile = ServerProfile(
                id = matched?.id ?: 0L,
                name = name,
                host = host,
                sshPort = sshPort,
                openCodePort = openCodePort,
                username = username,
                authType = authType,
                hasPassphrase = !passphrase.isNullOrEmpty(),
                autoReconnect = autoReconnect,
                createdAt = matched?.createdAt ?: System.currentTimeMillis(),
                lastConnectedAt = matched?.lastConnectedAt,
            )

            val savedId = profileRepository.saveProfile(
                profile = targetProfile,
                newPassword = passwordSecret,
                newPrivateKeyPem = privateKeyPem,
                newPassphrase = passphrase,
                keepExistingSecrets = matched != null &&
                    passwordSecret == null &&
                    privateKeyPem == null &&
                    passphrase == null,
            )

            if (openCodeAuthPass != null) {
                secretStore.saveOpenCodeServerAuth(
                    profileId = savedId,
                    username = openCodeAuthUser ?: "opencode",
                    password = openCodeAuthPass,
                )
            }

            if (matched != null) updated++ else imported++
        }

        if (imported == 0 && updated == 0) {
            throw IllegalArgumentException("В файле не найдено корректных профилей серверов")
        }

        ProfileImportResult(importedCount = imported, updatedCount = updated)
    }
}
