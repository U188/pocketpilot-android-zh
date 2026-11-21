package com.example.ui.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppPreferencesRepository
import com.example.data.repository.UsageRepository
import com.example.data.ssh.SshConnectionManager
import com.example.domain.model.ManualBalanceSummary
import com.example.domain.model.ModelPricing
import com.example.domain.model.SshConnectionState
import com.example.domain.model.UsageAggregateRow
import com.example.domain.model.UsageBreakdownTab
import com.example.domain.model.UsageCalculator
import com.example.domain.model.UsagePeriod
import com.example.domain.model.UsageRecord
import com.example.domain.model.UsageScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UsageUiState(
    val scope: UsageScope = UsageScope.SERVER,
    val period: UsagePeriod = UsagePeriod.DAYS_7,
    val tab: UsageBreakdownTab = UsageBreakdownTab.BY_DAY,
    val activeServerId: Long = 0L,
    val activeServerName: String = "",
    val activeWorkspacePath: String = "",
    val isConnected: Boolean = false,
    val isSyncing: Boolean = false,
    val records: List<UsageRecord> = emptyList(),
    val pricingByKey: Map<String, ModelPricing> = emptyMap(),
    val totalInputTokens: Long = 0L,
    val totalOutputTokens: Long = 0L,
    val totalReasoningTokens: Long = 0L,
    val totalCacheTokens: Long = 0L,
    val totalTokens: Long = 0L,
    val totalProviderUnits: Double = 0.0,
    val totalCurrencyCostUsd: Double? = null,
    val totalRequests: Int = 0,
    val rows: List<UsageAggregateRow> = emptyList(),
    val manualBalance: ManualBalanceSummary? = null,
    val manualBalanceInputText: String = "",
)

private data class UsageFilterState(
    val scope: UsageScope = UsageScope.WORKSPACE,
    val period: UsagePeriod = UsagePeriod.DAYS_7,
    val tab: UsageBreakdownTab = UsageBreakdownTab.BY_DAY,
    val isSyncing: Boolean = false,
    val manualBalanceInputText: String = "",
)

private data class ResolvedUsageContext(
    val serverId: Long,
    val serverName: String,
    val workspacePath: String,
    val isConnected: Boolean,
    val effectiveScope: UsageScope,
    val queryWorkspacePath: String,
    val period: UsagePeriod,
    val tab: UsageBreakdownTab,
    val isSyncing: Boolean,
    val manualBalanceInputText: String,
)

@OptIn(ExperimentalCoroutinesApi::class)
class UsageViewModel(
    private val usageRepository: UsageRepository,
    private val connectionManager: SshConnectionManager,
    private val preferencesRepository: AppPreferencesRepository,
) : ViewModel() {

    private val filterState = MutableStateFlow(UsageFilterState())

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val contextFlow = combine(
        connectionManager.connectionState,
        preferencesRepository.settings,
        filterState,
    ) { conn, settings, filter ->
        val activeProfile = when (conn) {
            is SshConnectionState.Connected -> conn.profile
            is SshConnectionState.Connecting -> conn.profile
            is SshConnectionState.AwaitingHostKeyVerification -> conn.profile
            is SshConnectionState.Reconnecting -> conn.profile
            is SshConnectionState.Error -> conn.profile
            SshConnectionState.Disconnected -> null
        }
        val serverId = activeProfile?.id ?: settings.lastActiveProfileId ?: 0L
        val workspacePath = if (serverId > 0L) {
            settings.activeWorkspaceByProfile[serverId].orEmpty().trim()
        } else {
            ""
        }
        val effectiveScope = if (workspacePath.isBlank()) UsageScope.SERVER else filter.scope
        val queryWorkspace = if (effectiveScope == UsageScope.WORKSPACE) workspacePath else ""
        ResolvedUsageContext(
            serverId = serverId,
            serverName = activeProfile?.name.orEmpty(),
            workspacePath = workspacePath,
            isConnected = conn is SshConnectionState.Connected,
            effectiveScope = effectiveScope,
            queryWorkspacePath = queryWorkspace,
            period = filter.period,
            tab = filter.tab,
            isSyncing = filter.isSyncing,
            manualBalanceInputText = filter.manualBalanceInputText,
        )
    }

    private val filteredRecordsFlow = contextFlow.flatMapLatest { ctx ->
        usageRepository.observeRecords(
            serverProfileId = ctx.serverId,
            workspacePath = ctx.queryWorkspacePath,
            period = ctx.period,
        )
    }

    private val allServerRecordsFlow = contextFlow.flatMapLatest { ctx ->
        usageRepository.observeRecords(
            serverProfileId = ctx.serverId,
            workspacePath = "",
            period = UsagePeriod.ALL,
        )
    }

    private val manualBalanceEntityFlow = contextFlow.flatMapLatest { ctx ->
        usageRepository.observeManualBalance(ctx.serverId)
    }

    val uiState: StateFlow<UsageUiState> = combine(
        contextFlow,
        filteredRecordsFlow,
        allServerRecordsFlow,
        usageRepository.allPricingMap,
        manualBalanceEntityFlow,
    ) { ctx, records, allServerRecords, pricingByKey, balanceEntity ->
        var input = 0L
        var output = 0L
        var reasoning = 0L
        var cacheRead = 0L
        var cacheWrite = 0L
        var units = 0.0
        var usd = 0.0
        var anyUsd = false

        for (r in records) {
            input += r.inputTokens
            output += r.outputTokens
            reasoning += r.reasoningTokens
            cacheRead += r.cacheReadTokens
            cacheWrite += r.cacheWriteTokens
            val pricing = pricingByKey[ModelPricing.keyOf(r.providerId, r.modelId)]
            val cost = UsageCalculator.computeMessageCost(r.toTokenUsage(), r.serverCostUsd, pricing)
            units += cost.providerUnits
            if (cost.currencyCostUsd != null && cost.currencyCostUsd > 0.0) {
                usd += cost.currencyCostUsd
                anyUsd = true
            }
        }

        val rows = when (ctx.tab) {
            UsageBreakdownTab.BY_DAY -> UsageCalculator.aggregateByDay(records, pricingByKey)
            UsageBreakdownTab.BY_MODEL -> UsageCalculator.aggregateByModel(records, pricingByKey)
            UsageBreakdownTab.BY_PROVIDER -> UsageCalculator.aggregateByProvider(records, pricingByKey)
        }

        val balanceSummary = balanceEntity?.let { entity ->
            UsageCalculator.computeManualBalanceSummary(
                initialBalanceUnits = entity.initialBalanceUnits,
                enteredAtMillis = entity.enteredAtMillis,
                allScopeRecords = allServerRecords,
                pricingByKey = pricingByKey,
            )
        }

        UsageUiState(
            scope = ctx.effectiveScope,
            period = ctx.period,
            tab = ctx.tab,
            activeServerId = ctx.serverId,
            activeServerName = ctx.serverName,
            activeWorkspacePath = ctx.workspacePath,
            isConnected = ctx.isConnected,
            isSyncing = ctx.isSyncing,
            records = records,
            pricingByKey = pricingByKey,
            totalInputTokens = input,
            totalOutputTokens = output,
            totalReasoningTokens = reasoning,
            totalCacheTokens = cacheRead + cacheWrite,
            totalTokens = input + output + reasoning + cacheRead + cacheWrite,
            totalProviderUnits = units,
            totalCurrencyCostUsd = if (anyUsd) usd else null,
            totalRequests = records.size,
            rows = rows,
            manualBalance = balanceSummary,
            manualBalanceInputText = ctx.manualBalanceInputText,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UsageUiState(),
    )

    fun setScope(scope: UsageScope) {
        filterState.update { it.copy(scope = scope) }
    }

    fun setPeriod(period: UsagePeriod) {
        filterState.update { it.copy(period = period) }
    }

    fun setTab(tab: UsageBreakdownTab) {
        filterState.update { it.copy(tab = tab) }
    }

    fun onManualBalanceInputChange(value: String) {
        filterState.update { it.copy(manualBalanceInputText = value) }
    }

    fun saveManualBalance() {
        val current = uiState.value
        val parsed = current.manualBalanceInputText
            .trim()
            .replace(" ", "")
            .replace(',', '.')
            .toDoubleOrNull()
        if (parsed == null || parsed < 0.0) {
            _events.tryEmit("请输入有效的余额数量")
            return
        }
        viewModelScope.launch {
            usageRepository.saveManualBalance(
                serverProfileId = current.activeServerId,
                balanceUnits = parsed,
            )
            filterState.update { it.copy(manualBalanceInputText = "") }
            _events.tryEmit("余额已保存")
        }
    }

    fun clearManualBalance() {
        val current = uiState.value
        viewModelScope.launch {
            usageRepository.clearManualBalance(current.activeServerId)
            _events.tryEmit("余额已清空")
        }
    }

    fun syncFromServer() {
        val current = uiState.value
        if (!current.isConnected || current.activeServerId <= 0L) {
            _events.tryEmit("连接服务器后即可同步")
            return
        }
        if (current.isSyncing) return
        viewModelScope.launch {
            filterState.update { it.copy(isSyncing = true) }
            try {
                val result = usageRepository.syncSessionsFromServer(
                    serverProfileId = current.activeServerId,
                    workspacePath = current.activeWorkspacePath.takeIf { it.isNotBlank() },
                    includeAllServerSessions = current.scope == UsageScope.SERVER,
                )
                _events.tryEmit(
                    "已同步：${result.sessionsScanned} 个会话，${result.messagesCached} 条消息",
                )
            } catch (c: CancellationException) {
                throw c
            } catch (e: Throwable) {
                _events.tryEmit("同步失败：${e.message ?: e::class.java.simpleName}")
            } finally {
                filterState.update { it.copy(isSyncing = false) }
            }
        }
    }

    fun buildCsvExport(): String {
        val current = uiState.value
        return UsageCalculator.exportUsageToCsv(
            records = current.records,
            pricingByKey = current.pricingByKey,
        )
    }

    class Factory(
        private val usageRepository: UsageRepository,
        private val connectionManager: SshConnectionManager,
        private val preferencesRepository: AppPreferencesRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return UsageViewModel(
                usageRepository = usageRepository,
                connectionManager = connectionManager,
                preferencesRepository = preferencesRepository,
            ) as T
        }
    }
}
