package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.domain.model.ModelPricing
import com.example.domain.model.UsageRecord

@Entity(
    tableName = "usage_messages",
    primaryKeys = ["serverProfileId", "sessionId", "messageId"],
    indices = [
        Index(value = ["serverProfileId", "dayKey"]),
        Index(value = ["serverProfileId", "workspacePath"]),
    ],
)
data class UsageMessageEntity(
    val serverProfileId: Long,
    val workspacePath: String,
    val sessionId: String,
    val messageId: String,
    val providerId: String,
    val modelId: String,
    val dayKey: String,
    val timestampMillis: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val reasoningTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val serverCostUsd: Double,
) {
    fun toDomain(): UsageRecord = UsageRecord(
        serverProfileId = serverProfileId,
        workspacePath = workspacePath,
        sessionId = sessionId,
        messageId = messageId,
        providerId = providerId,
        modelId = modelId,
        dayKey = dayKey,
        timestampMillis = timestampMillis,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        reasoningTokens = reasoningTokens,
        cacheReadTokens = cacheReadTokens,
        cacheWriteTokens = cacheWriteTokens,
        serverCostUsd = serverCostUsd,
    )
}

@Entity(
    tableName = "model_pricing",
    primaryKeys = ["providerId", "modelId"],
)
data class ModelPricingEntity(
    val providerId: String,
    val modelId: String,
    val multiplier: Double = 1.0,
    val cacheMultiplier: Double = 1.0,
    val inputPricePer1M: Double? = null,
    val outputPricePer1M: Double? = null,
    val updatedAtMillis: Long = System.currentTimeMillis(),
) {
    fun toDomain(): ModelPricing = ModelPricing(
        providerId = providerId,
        modelId = modelId,
        multiplier = multiplier,
        cacheMultiplier = cacheMultiplier,
        inputPricePer1M = inputPricePer1M,
        outputPricePer1M = outputPricePer1M,
    )

    companion object {
        fun fromDomain(pricing: ModelPricing): ModelPricingEntity = ModelPricingEntity(
            providerId = pricing.providerId.trim(),
            modelId = pricing.modelId.trim(),
            multiplier = pricing.multiplier.coerceAtLeast(0.0),
            cacheMultiplier = pricing.cacheMultiplier.coerceAtLeast(0.0),
            inputPricePer1M = pricing.inputPricePer1M?.takeIf { it >= 0.0 },
            outputPricePer1M = pricing.outputPricePer1M?.takeIf { it >= 0.0 },
            updatedAtMillis = System.currentTimeMillis(),
        )
    }
}

@Entity(tableName = "manual_balance")
data class ManualBalanceEntity(
    @PrimaryKey val scopeKey: String,
    val initialBalanceUnits: Double,
    val enteredAtMillis: Long,
)
