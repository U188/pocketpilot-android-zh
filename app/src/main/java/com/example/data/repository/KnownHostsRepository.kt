package com.example.data.repository

import com.example.data.local.KnownHostDao
import com.example.data.local.KnownHostEntity
import com.example.domain.model.KnownHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class KnownHostsRepository(
    private val dao: KnownHostDao,
) {
    val knownHosts: Flow<List<KnownHost>> = dao.observeAll().map { entities ->
        entities.map { it.toDomain() }
    }

    suspend fun find(hostPortKey: String): KnownHost? = withContext(Dispatchers.IO) {
        dao.getByHostPort(hostPortKey)?.toDomain()
    }

    suspend fun trust(host: KnownHost) = withContext(Dispatchers.IO) {
        dao.upsert(KnownHostEntity.fromDomain(host))
    }

    suspend fun touch(hostPortKey: String, timestamp: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            dao.updateLastVerified(hostPortKey, timestamp)
        }

    suspend fun remove(hostPortKey: String) = withContext(Dispatchers.IO) {
        dao.delete(hostPortKey)
    }
}
