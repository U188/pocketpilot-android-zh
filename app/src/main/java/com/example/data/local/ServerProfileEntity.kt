package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.domain.model.AuthType
import com.example.domain.model.ServerProfile

@Entity(tableName = "server_profiles")
data class ServerProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val host: String,
    val sshPort: Int,
    val openCodePort: Int,
    val username: String,
    val authType: String,
    val hasPassphrase: Boolean,
    val autoReconnect: Boolean,
    val createdAt: Long,
    val lastConnectedAt: Long?,
) {
    fun toDomain(): ServerProfile = ServerProfile(
        id = id,
        name = name,
        host = host,
        sshPort = sshPort,
        openCodePort = openCodePort,
        username = username,
        authType = runCatching { AuthType.valueOf(authType) }.getOrDefault(AuthType.PRIVATE_KEY),
        hasPassphrase = hasPassphrase,
        autoReconnect = autoReconnect,
        createdAt = createdAt,
        lastConnectedAt = lastConnectedAt,
    )

    companion object {
        fun fromDomain(profile: ServerProfile): ServerProfileEntity = ServerProfileEntity(
            id = profile.id,
            name = profile.name,
            host = profile.host,
            sshPort = profile.sshPort,
            openCodePort = profile.openCodePort,
            username = profile.username,
            authType = profile.authType.name,
            hasPassphrase = profile.hasPassphrase,
            autoReconnect = profile.autoReconnect,
            createdAt = profile.createdAt,
            lastConnectedAt = profile.lastConnectedAt,
        )
    }
}
