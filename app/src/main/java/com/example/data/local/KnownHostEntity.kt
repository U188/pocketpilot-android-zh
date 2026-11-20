package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.domain.model.KnownHost

@Entity(tableName = "known_hosts")
data class KnownHostEntity(
    @PrimaryKey val hostPortKey: String,
    val algorithm: String,
    val fingerprintSha256: String,
    val publicKeyBase64: String,
    val firstSeenAt: Long,
    val lastVerifiedAt: Long,
) {
    fun toDomain(): KnownHost = KnownHost(
        hostPortKey = hostPortKey,
        algorithm = algorithm,
        fingerprintSha256 = fingerprintSha256,
        publicKeyBase64 = publicKeyBase64,
        firstSeenAt = firstSeenAt,
        lastVerifiedAt = lastVerifiedAt,
    )

    companion object {
        fun fromDomain(host: KnownHost): KnownHostEntity = KnownHostEntity(
            hostPortKey = host.hostPortKey,
            algorithm = host.algorithm,
            fingerprintSha256 = host.fingerprintSha256,
            publicKeyBase64 = host.publicKeyBase64,
            firstSeenAt = host.firstSeenAt,
            lastVerifiedAt = host.lastVerifiedAt,
        )
    }
}
