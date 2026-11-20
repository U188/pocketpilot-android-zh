package com.example.data.repository

import com.example.data.local.ManualBalanceDao
import com.example.data.local.ManualBalanceEntity
import com.example.data.local.ModelPricingDao
import com.example.data.local.ModelPricingEntity
import com.example.data.local.UsageDao
import com.example.data.local.UsageMessageEntity
import com.example.data.opencode.OpenCodeApiClient
import com.example.domain.model.ModelPricing
import com.example.domain.model.OpenCodeMessage
import com.example.domain.model.UsagePeriod
import com.example.domain.model.UsageRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class UsageSyncResult(
    val sessionsScanned: Int,
    val messagesCached: Int,
)

class UsageRepository(
    private val usageDao: UsageDao,
    private val pricingDao: ModelPricingDao,
    private val manualBalanceDao: ManualBalanceDao,
    private val apiClient: OpenCodeApiClient,
) {
    val allPricingMap: Flow<Map<String, ModelPricing>> = pricingDao.observeAll().map { entities ->
        entities.associate { entity ->
            val domain = entity.toDomain()
            domain.key to domain
        }
    }

    fun observeRecords(
        serverProfileId: Long,
        workspacePath: String,
        period: UsagePeriod,
    ): Flow<List<UsageRecord>> {
        val sinceMillis = computePeriodStartMillis(period)
        return usageDao.observeRecords(
            serverProfileId = serverProfileId,
            workspacePath = workspacePath,
            sinceMillis = sinceMillis,
        ).map { list -> list.map { it.toDomain() } }
    }

    suspend fun getAllRecordsOnce(
        serverProfileId: Long,
        workspacePath: String,
    ): List<UsageRecord> = withContext(Dispatchers.IO) {
        usageDao.getRecordsOnce(
            serverProfileId = serverProfileId,
            workspacePath = workspacePath,
        ).map { it.toDomain() }
    }

    suspend fun recordMessages(
        serverProfileId: Long,
        workspacePath: String,
        messages: List<OpenCodeMessage>,
    ) = withContext(Dispatchers.IO) {
        if (serverProfileId <= 0L || messages.isEmpty()) return@withContext
        val entities = messages.mapNotNull { msg ->
            toUsageEntityOrNull(serverProfileId, workspacePath, msg)
        }
        if (entities.isNotEmpty()) {
            usageDao.upsertMessages(entities)
        }
    }

    suspend fun recordMessage(
        serverProfileId: Long,
        workspacePath: String,
        message: OpenCodeMessage,
    ) = withContext(Dispatchers.IO) {
        if (serverProfileId <= 0L) return@withContext
        val entity = toUsageEntityOrNull(serverProfileId, workspacePath, message) ?: return@withContext
        usageDao.upsertMessage(entity)
    }

    /**
     * Pulls all sessions for the chosen workspace or entire server from OpenCode REST API
     * and caches their assistant message token/cost records in Room so the Usage screen stays
     * fast and accurate across sessions.
     */
    suspend fun syncSessionsFromServer(
        serverProfileId: Long,
        workspacePath: String?,
        includeAllServerSessions: Boolean,
    ): UsageSyncResult = withContext(Dispatchers.IO) {
        if (serverProfileId <= 0L) return@withContext UsageSyncResult(0, 0)
        val sessions = if (includeAllServerSessions) {
            apiClient.listSessions(includeDirectory = false)
        } else {
            apiClient.listSessions(
                includeDirectory = !workspacePath.isNullOrBlank(),
                directoryOverride = workspacePath,
            )
        }

        var totalCached = 0
        for (session in sessions) {
            val dir = session.directory.trim().ifBlank { workspacePath.orEmpty().trim() }
            val messages = try {
                apiClient.getMessages(
                    sessionId = session.id,
                    includeDirectory = dir.isNotBlank(),
                    directoryOverride = dir.takeIf { it.isNotBlank() },
                )
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                continue
            }
            val entities = messages.mapNotNull { msg ->
                toUsageEntityOrNull(serverProfileId, dir, msg)
            }
            if (entities.isNotEmpty()) {
                usageDao.upsertMessages(entities)
                totalCached += entities.size
            }
        }
        UsageSyncResult(sessionsScanned = sessions.size, messagesCached = totalCached)
    }

    suspend fun getPricingForProvider(providerId: String): Map<String, ModelPricing> = withContext(Dispatchers.IO) {
        pricingDao.getForProvider(providerId).associate { it.modelId to it.toDomain() }
    }

    suspend fun saveProviderModelPricing(
        providerId: String,
        models: List<ModelPricing>,
    ) = withContext(Dispatchers.IO) {
        val cleanProvider = providerId.trim()
        if (cleanProvider.isEmpty()) return@withContext
        if (models.isNotEmpty()) {
            pricingDao.upsertAll(
                models.map { ModelPricingEntity.fromDomain(it.copy(providerId = cleanProvider)) },
            )
        }
    }

    suspend fun deletePricingForProvider(providerId: String) = withContext(Dispatchers.IO) {
        pricingDao.deleteForProvider(providerId.trim())
    }

    fun observeManualBalance(serverProfileId: Long): Flow<ManualBalanceEntity?> {
        return manualBalanceDao.observeByScope(balanceScopeKey(serverProfileId))
    }

    suspend fun saveManualBalance(
        serverProfileId: Long,
        balanceUnits: Double,
        enteredAtMillis: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        manualBalanceDao.upsert(
            ManualBalanceEntity(
                scopeKey = balanceScopeKey(serverProfileId),
                initialBalanceUnits = balanceUnits.coerceAtLeast(0.0),
                enteredAtMillis = enteredAtMillis,
            ),
        )
    }

    suspend fun clearManualBalance(serverProfileId: Long) = withContext(Dispatchers.IO) {
        manualBalanceDao.deleteByScope(balanceScopeKey(serverProfileId))
    }

    private fun balanceScopeKey(serverProfileId: Long): String {
        return if (serverProfileId > 0L) "server_$serverProfileId" else "global"
    }

    private fun toUsageEntityOrNull(
        serverProfileId: Long,
        workspacePath: String,
        msg: OpenCodeMessage,
    ): UsageMessageEntity? {
        if (msg.isUser) return null
        val t = msg.tokens ?: return null
        val cost = msg.costUsd?.takeIf { it > 0.0 } ?: 0.0
        if (!t.hasAny && cost <= 0.0) return null
        val ts = (msg.completedAtMillis ?: msg.createdAtMillis).takeIf { it > 0L }
            ?: System.currentTimeMillis()
        val dayKey = DAY_FORMATTER.format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()))
        return UsageMessageEntity(
            serverProfileId = serverProfileId,
            workspacePath = workspacePath.trim(),
            sessionId = msg.sessionId,
            messageId = msg.id,
            providerId = msg.providerId.orEmpty().trim(),
            modelId = msg.modelId.orEmpty().trim(),
            dayKey = dayKey,
            timestampMillis = ts,
            inputTokens = t.input,
            outputTokens = t.output,
            reasoningTokens = t.reasoning,
            cacheReadTokens = t.cacheRead,
            cacheWriteTokens = t.cacheWrite,
            serverCostUsd = cost,
        )
    }

    companion object {
        private val DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        fun computePeriodStartMillis(period: UsagePeriod, zoneId: ZoneId = ZoneId.systemDefault()): Long {
            val daysBack = period.daysBack ?: return 0L
            val startDate = LocalDate.now(zoneId).minusDays(daysBack.toLong())
            return startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        }
    }
}
