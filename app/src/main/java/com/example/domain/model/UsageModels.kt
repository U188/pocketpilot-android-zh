package com.example.domain.model

import java.util.Locale

data class TokenUsage(
    val input: Long = 0L,
    val output: Long = 0L,
    val reasoning: Long = 0L,
    val cacheRead: Long = 0L,
    val cacheWrite: Long = 0L,
) {
    val totalIoTokens: Long
        get() = input + output

    val totalCacheTokens: Long
        get() = cacheRead + cacheWrite

    val totalAllTokens: Long
        get() = input + output + reasoning + cacheRead + cacheWrite

    /**
     * Approximate context window fill for an assistant turn: prompt input + cached prompt + generated output.
     */
    val contextFootprintTokens: Long
        get() = input + cacheRead + cacheWrite + output

    val hasAny: Boolean
        get() = totalAllTokens > 0L
}

data class ModelPricing(
    val providerId: String,
    val modelId: String,
    val multiplier: Double = 1.0,
    val cacheMultiplier: Double = 1.0,
    val inputPricePer1M: Double? = null,
    val outputPricePer1M: Double? = null,
) {
    val key: String
        get() = keyOf(providerId, modelId)

    val hasCustomPrice: Boolean
        get() = (inputPricePer1M != null && inputPricePer1M > 0.0) ||
            (outputPricePer1M != null && outputPricePer1M > 0.0)

    val hasCustomMultiplier: Boolean
        get() = multiplier != 1.0 || cacheMultiplier != 1.0

    companion object {
        fun keyOf(providerId: String?, modelId: String?): String {
            return "${providerId.orEmpty().trim()}/${modelId.orEmpty().trim()}"
        }
    }
}

enum class UsagePeriod(val daysBack: Int?) {
    TODAY(0),
    DAYS_7(7),
    DAYS_30(30),
    ALL(null),
}

enum class UsageScope {
    WORKSPACE,
    SERVER,
}

enum class UsageBreakdownTab {
    BY_DAY,
    BY_MODEL,
    BY_PROVIDER,
}

data class ComputedUsageCost(
    val currencyCostUsd: Double?,
    val providerUnits: Double,
    val isEstimatedByApp: Boolean,
) {
    val hasCurrencyCost: Boolean
        get() = currencyCostUsd != null && currencyCostUsd > 0.0
}

data class SessionUsageTotals(
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val reasoningTokens: Long = 0L,
    val cacheReadTokens: Long = 0L,
    val cacheWriteTokens: Long = 0L,
    val totalCurrencyCostUsd: Double = 0.0,
    val hasAnyCurrencyCost: Boolean = false,
    val totalProviderUnits: Double = 0.0,
    val hasAnyCustomPricing: Boolean = false,
    val assistantMessageCount: Int = 0,
) {
    val totalTokens: Long
        get() = inputTokens + outputTokens + reasoningTokens + cacheReadTokens + cacheWriteTokens

    val totalIoTokens: Long
        get() = inputTokens + outputTokens

    val hasAnyTokens: Boolean
        get() = totalTokens > 0L
}

data class SessionContextUsage(
    val usedTokens: Long,
    val limitTokens: Long,
    val modelDisplay: String,
) {
    val fraction: Float
        get() = if (limitTokens > 0L) {
            (usedTokens.toDouble() / limitTokens.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }

    val percentInt: Int
        get() = if (limitTokens > 0L) {
            ((usedTokens.toDouble() / limitTokens.toDouble()) * 100.0).toInt().coerceAtLeast(0)
        } else {
            0
        }

    val isWarning: Boolean
        get() = limitTokens > 0L && usedTokens.toDouble() / limitTokens.toDouble() > 0.80
}

data class UsageRecord(
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
    fun toTokenUsage(): TokenUsage = TokenUsage(
        input = inputTokens,
        output = outputTokens,
        reasoning = reasoningTokens,
        cacheRead = cacheReadTokens,
        cacheWrite = cacheWriteTokens,
    )
}

data class UsageAggregateRow(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val requestCount: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val reasoningTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val totalProviderUnits: Double,
    val totalCurrencyCostUsd: Double?,
) {
    val totalTokens: Long
        get() = inputTokens + outputTokens + reasoningTokens + cacheReadTokens + cacheWriteTokens
}

data class ManualBalanceSummary(
    val initialBalanceUnits: Double,
    val enteredAtMillis: Long,
    val spentSinceEnteredUnits: Double,
    val remainingUnits: Double,
    val averageUnitsPerRequest: Double?,
    val estimatedRemainingRequests: Long?,
)

object UsageCalculator {

    /**
     * Computes both provider units and currency cost according to PocketPilot rules:
     * - Provider units: (input + output) * multiplier + (cacheRead + cacheWrite) * cacheMultiplier
     * - Currency cost:
     *   1) If custom per-1M prices are set: ((input * price_in + output * price_out) / 1_000_000) * multiplier
     *   2) Otherwise if serverCostUsd > 0: serverCostUsd
     *   3) Otherwise null ("стоимость не задана")
     */
    fun computeMessageCost(
        tokens: TokenUsage,
        serverCostUsd: Double?,
        pricing: ModelPricing?,
    ): ComputedUsageCost {
        val multiplier = (pricing?.multiplier ?: 1.0).coerceAtLeast(0.0)
        val cacheMultiplier = (pricing?.cacheMultiplier ?: 1.0).coerceAtLeast(0.0)

        val units = (tokens.input + tokens.output).toDouble() * multiplier +
            (tokens.cacheRead + tokens.cacheWrite).toDouble() * cacheMultiplier

        val customUsd = if (pricing != null && pricing.hasCustomPrice) {
            val inPrice = (pricing.inputPricePer1M ?: 0.0).coerceAtLeast(0.0)
            val outPrice = (pricing.outputPricePer1M ?: 0.0).coerceAtLeast(0.0)
            val raw = ((tokens.input.toDouble() * inPrice + tokens.output.toDouble() * outPrice) / 1_000_000.0) * multiplier
            raw.takeIf { it > 0.0 }
        } else {
            null
        }

        val resolvedUsd = customUsd ?: serverCostUsd?.takeIf { it > 0.0 }
        val estimatedByApp = customUsd != null || pricing != null

        return ComputedUsageCost(
            currencyCostUsd = resolvedUsd,
            providerUnits = units,
            isEstimatedByApp = estimatedByApp,
        )
    }

    fun computeSessionTotals(
        messages: List<OpenCodeMessage>,
        pricingByKey: Map<String, ModelPricing>,
    ): SessionUsageTotals {
        var input = 0L
        var output = 0L
        var reasoning = 0L
        var cacheRead = 0L
        var cacheWrite = 0L
        var totalUsd = 0.0
        var anyUsd = false
        var totalUnits = 0.0
        var anyCustomPricing = false
        var count = 0

        for (msg in messages) {
            if (msg.isUser) continue
            val t = msg.tokens ?: continue
            if (!t.hasAny && (msg.costUsd == null || msg.costUsd <= 0.0)) continue
            count++
            input += t.input
            output += t.output
            reasoning += t.reasoning
            cacheRead += t.cacheRead
            cacheWrite += t.cacheWrite

            val pricing = pricingByKey[ModelPricing.keyOf(msg.providerId, msg.modelId)]
            if (pricing != null) anyCustomPricing = true
            val cost = computeMessageCost(t, msg.costUsd, pricing)
            totalUnits += cost.providerUnits
            if (cost.currencyCostUsd != null && cost.currencyCostUsd > 0.0) {
                totalUsd += cost.currencyCostUsd
                anyUsd = true
            }
        }

        return SessionUsageTotals(
            inputTokens = input,
            outputTokens = output,
            reasoningTokens = reasoning,
            cacheReadTokens = cacheRead,
            cacheWriteTokens = cacheWrite,
            totalCurrencyCostUsd = totalUsd,
            hasAnyCurrencyCost = anyUsd,
            totalProviderUnits = totalUnits,
            hasAnyCustomPricing = anyCustomPricing,
            assistantMessageCount = count,
        )
    }

    fun computeContextUsage(
        messages: List<OpenCodeMessage>,
        providers: List<OpenCodeProvider>,
        selectedProviderId: String,
        selectedModelId: String,
    ): SessionContextUsage? {
        val lastAssistantWithTokens = messages.lastOrNull { !it.isUser && it.tokens?.hasAny == true }
            ?: return null
        val pId = lastAssistantWithTokens.providerId?.takeIf { it.isNotBlank() } ?: selectedProviderId
        val mId = lastAssistantWithTokens.modelId?.takeIf { it.isNotBlank() } ?: selectedModelId
        val model = providers
            .flatMap { it.models }
            .firstOrNull { it.providerId == pId && it.modelId == mId }
            ?: providers.flatMap { it.models }.firstOrNull { it.modelId == mId }
            ?: return null
        val limit = model.contextLimit?.takeIf { it > 0L } ?: return null
        val used = lastAssistantWithTokens.tokens?.contextFootprintTokens ?: return null
        if (used <= 0L) return null
        return SessionContextUsage(
            usedTokens = used,
            limitTokens = limit,
            modelDisplay = model.displayName,
        )
    }

    fun aggregateByDay(
        records: List<UsageRecord>,
        pricingByKey: Map<String, ModelPricing>,
    ): List<UsageAggregateRow> = aggregateGrouped(
        records = records,
        pricingByKey = pricingByKey,
        keySelector = { it.dayKey },
        titleSelector = { key, _ -> key },
        subtitleSelector = { _, list -> "${list.size} запр." },
    ).sortedByDescending { it.key }

    fun aggregateByModel(
        records: List<UsageRecord>,
        pricingByKey: Map<String, ModelPricing>,
    ): List<UsageAggregateRow> = aggregateGrouped(
        records = records,
        pricingByKey = pricingByKey,
        keySelector = { ModelPricing.keyOf(it.providerId, it.modelId) },
        titleSelector = { _, list -> list.first().modelId.ifBlank { "неизвестная модель" } },
        subtitleSelector = { _, list -> list.first().providerId.ifBlank { null } },
    ).sortedByDescending { it.totalTokens }

    fun aggregateByProvider(
        records: List<UsageRecord>,
        pricingByKey: Map<String, ModelPricing>,
    ): List<UsageAggregateRow> = aggregateGrouped(
        records = records,
        pricingByKey = pricingByKey,
        keySelector = { it.providerId.ifBlank { "unknown" } },
        titleSelector = { key, _ -> key },
        subtitleSelector = { _, list ->
            val modelCount = list.map { it.modelId }.distinct().size
            "$modelCount модел."
        },
    ).sortedByDescending { it.totalTokens }

    private fun aggregateGrouped(
        records: List<UsageRecord>,
        pricingByKey: Map<String, ModelPricing>,
        keySelector: (UsageRecord) -> String,
        titleSelector: (String, List<UsageRecord>) -> String,
        subtitleSelector: (String, List<UsageRecord>) -> String?,
    ): List<UsageAggregateRow> {
        return records.groupBy(keySelector).map { (key, list) ->
            var input = 0L
            var output = 0L
            var reasoning = 0L
            var cacheRead = 0L
            var cacheWrite = 0L
            var units = 0.0
            var usd = 0.0
            var anyUsd = false

            for (r in list) {
                input += r.inputTokens
                output += r.outputTokens
                reasoning += r.reasoningTokens
                cacheRead += r.cacheReadTokens
                cacheWrite += r.cacheWriteTokens
                val pricing = pricingByKey[ModelPricing.keyOf(r.providerId, r.modelId)]
                val cost = computeMessageCost(r.toTokenUsage(), r.serverCostUsd, pricing)
                units += cost.providerUnits
                if (cost.currencyCostUsd != null && cost.currencyCostUsd > 0.0) {
                    usd += cost.currencyCostUsd
                    anyUsd = true
                }
            }

            UsageAggregateRow(
                key = key,
                title = titleSelector(key, list),
                subtitle = subtitleSelector(key, list),
                requestCount = list.size,
                inputTokens = input,
                outputTokens = output,
                reasoningTokens = reasoning,
                cacheReadTokens = cacheRead,
                cacheWriteTokens = cacheWrite,
                totalProviderUnits = units,
                totalCurrencyCostUsd = if (anyUsd) usd else null,
            )
        }
    }

    fun computeManualBalanceSummary(
        initialBalanceUnits: Double,
        enteredAtMillis: Long,
        allScopeRecords: List<UsageRecord>,
        pricingByKey: Map<String, ModelPricing>,
    ): ManualBalanceSummary {
        var spentSince = 0.0
        var totalUnitsAll = 0.0
        var totalRequestsAll = 0
        var sinceRequests = 0

        for (r in allScopeRecords) {
            val pricing = pricingByKey[ModelPricing.keyOf(r.providerId, r.modelId)]
            val units = computeMessageCost(r.toTokenUsage(), r.serverCostUsd, pricing).providerUnits
            if (units > 0.0) {
                totalUnitsAll += units
                totalRequestsAll++
            }
            if (r.timestampMillis >= enteredAtMillis) {
                spentSince += units
                if (units > 0.0) sinceRequests++
            }
        }

        val remaining = (initialBalanceUnits - spentSince).coerceAtLeast(0.0)
        val avgPerReq = when {
            sinceRequests > 0 && spentSince > 0.0 -> spentSince / sinceRequests
            totalRequestsAll > 0 && totalUnitsAll > 0.0 -> totalUnitsAll / totalRequestsAll
            else -> null
        }
        val estRequests = if (avgPerReq != null && avgPerReq > 0.0) {
            (remaining / avgPerReq).toLong().coerceAtLeast(0L)
        } else {
            null
        }

        return ManualBalanceSummary(
            initialBalanceUnits = initialBalanceUnits,
            enteredAtMillis = enteredAtMillis,
            spentSinceEnteredUnits = spentSince,
            remainingUnits = remaining,
            averageUnitsPerRequest = avgPerReq,
            estimatedRemainingRequests = estRequests,
        )
    }

    /**
     * Generates a privacy-safe CSV export containing ONLY aggregated usage metrics
     * (date, provider, model, token counts, units, cost). Strictly excludes keys, passwords,
     * hostnames, and message text.
     */
    fun exportUsageToCsv(
        records: List<UsageRecord>,
        pricingByKey: Map<String, ModelPricing>,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("date,provider_id,model_id,requests,input_tokens,output_tokens,reasoning_tokens,cache_read_tokens,cache_write_tokens,total_tokens,provider_units,cost_usd")
        val grouped = records.groupBy { Triple(it.dayKey, it.providerId, it.modelId) }
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<Triple<String, String, String>, List<UsageRecord>>> { it.key.first }
                    .thenBy { it.key.second }
                    .thenBy { it.key.third },
            )

        for ((triple, list) in grouped) {
            val (day, providerId, modelId) = triple
            var input = 0L
            var output = 0L
            var reasoning = 0L
            var cacheRead = 0L
            var cacheWrite = 0L
            var units = 0.0
            var usd = 0.0
            var anyUsd = false
            val pricing = pricingByKey[ModelPricing.keyOf(providerId, modelId)]
            for (r in list) {
                input += r.inputTokens
                output += r.outputTokens
                reasoning += r.reasoningTokens
                cacheRead += r.cacheReadTokens
                cacheWrite += r.cacheWriteTokens
                val cost = computeMessageCost(r.toTokenUsage(), r.serverCostUsd, pricing)
                units += cost.providerUnits
                if (cost.currencyCostUsd != null && cost.currencyCostUsd > 0.0) {
                    usd += cost.currencyCostUsd
                    anyUsd = true
                }
            }
            val total = input + output + reasoning + cacheRead + cacheWrite
            val usdCell = if (anyUsd) String.format(Locale.US, "%.6f", usd) else ""
            val unitsCell = String.format(Locale.US, "%.2f", units)
            sb.appendLine(
                listOf(
                    csvEscape(day),
                    csvEscape(providerId),
                    csvEscape(modelId),
                    list.size.toString(),
                    input.toString(),
                    output.toString(),
                    reasoning.toString(),
                    cacheRead.toString(),
                    cacheWrite.toString(),
                    total.toString(),
                    unitsCell,
                    usdCell,
                ).joinToString(","),
            )
        }
        return sb.toString()
    }

    fun formatTokenCount(count: Long): String {
        return when {
            count >= 1_000_000L -> String.format(Locale.US, "%.2fM", count / 1_000_000.0)
            count >= 10_000L -> String.format(Locale.US, "%.1fk", count / 1_000.0)
            else -> String.format(Locale("ru", "RU"), "%,d", count).replace(',', ' ')
        }
    }

    fun formatUnits(units: Double): String {
        return when {
            units >= 1_000_000.0 -> String.format(Locale.US, "%.2fM ед.", units / 1_000_000.0)
            units >= 10_000.0 -> String.format(Locale.US, "%.1fk ед.", units / 1_000.0)
            units == units.toLong().toDouble() -> "${formatTokenCount(units.toLong())} ед."
            else -> String.format(Locale.US, "%.1f ед.", units)
        }
    }

    fun formatUsd(usd: Double): String {
        return when {
            usd >= 1.0 -> String.format(Locale.US, "$%.2f", usd)
            usd >= 0.01 -> String.format(Locale.US, "$%.3f", usd)
            else -> String.format(Locale.US, "$%.4f", usd)
        }
    }

    private fun csvEscape(value: String): String {
        val clean = value.replace("\r", " ").replace("\n", " ")
        return if (clean.contains(',') || clean.contains('"')) {
            "\"${clean.replace("\"", "\"\"")}\""
        } else {
            clean
        }
    }
}
