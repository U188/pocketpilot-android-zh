package com.example.data.repository

import com.example.data.local.KeystoreSecretStore
import com.example.data.local.ServerProfileDao
import com.example.data.local.ServerProfileEntity
import com.example.domain.model.ServerProfile
import com.example.domain.model.ServerSecrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class ServerProfileRepository(
    private val dao: ServerProfileDao,
    private val secretStore: KeystoreSecretStore,
) {
    val profiles: Flow<List<ServerProfile>> = dao.observeAll().map { entities ->
        entities.map { it.toDomain() }
    }

    suspend fun getProfile(id: Long): ServerProfile? = withContext(Dispatchers.IO) {
        dao.getById(id)?.toDomain()
    }

    suspend fun getSecrets(profileId: Long): ServerSecrets = withContext(Dispatchers.IO) {
        secretStore.getSecrets(profileId)
    }

    suspend fun hasStoredPassword(profileId: Long): Boolean = withContext(Dispatchers.IO) {
        secretStore.hasPassword(profileId)
    }

    suspend fun hasStoredPrivateKey(profileId: Long): Boolean = withContext(Dispatchers.IO) {
        secretStore.hasPrivateKey(profileId)
    }

    suspend fun hasStoredPassphrase(profileId: Long): Boolean = withContext(Dispatchers.IO) {
        secretStore.hasPassphrase(profileId)
    }

    suspend fun saveProfile(
        profile: ServerProfile,
        newPassword: String?,
        newPrivateKeyPem: String?,
        newPassphrase: String?,
        keepExistingSecrets: Boolean,
    ): Long = withContext(Dispatchers.IO) {
        val existingSecrets = if (keepExistingSecrets && profile.id > 0L) {
            secretStore.getSecrets(profile.id)
        } else {
            ServerSecrets()
        }

        val resolvedPassword = when {
            !newPassword.isNullOrEmpty() -> newPassword
            keepExistingSecrets -> existingSecrets.password
            else -> null
        }
        val resolvedPrivateKey = when {
            !newPrivateKeyPem.isNullOrEmpty() -> newPrivateKeyPem
            keepExistingSecrets -> existingSecrets.privateKeyPem
            else -> null
        }
        val resolvedPassphrase = when {
            !newPassphrase.isNullOrEmpty() -> newPassphrase
            keepExistingSecrets -> existingSecrets.passphrase
            else -> null
        }

        val updatedProfile = profile.copy(
            hasPassphrase = !resolvedPassphrase.isNullOrEmpty(),
        )

        val savedId = if (updatedProfile.id == 0L) {
            dao.insert(ServerProfileEntity.fromDomain(updatedProfile))
        } else {
            dao.update(ServerProfileEntity.fromDomain(updatedProfile))
            updatedProfile.id
        }

        secretStore.saveSecrets(
            profileId = savedId,
            password = resolvedPassword,
            privateKeyPem = resolvedPrivateKey,
            passphrase = resolvedPassphrase,
        )

        savedId
    }

    suspend fun markConnected(profileId: Long, timestamp: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            dao.updateLastConnectedAt(profileId, timestamp)
        }

    suspend fun deleteProfile(profileId: Long) = withContext(Dispatchers.IO) {
        dao.deleteById(profileId)
        secretStore.deleteSecrets(profileId)
    }
}
