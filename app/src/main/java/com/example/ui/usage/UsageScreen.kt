package com.example.ui.usage

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.ManualBalanceSummary
import com.example.domain.model.UsageAggregateRow
import com.example.domain.model.UsageBreakdownTab
import com.example.domain.model.UsageCalculator
import com.example.domain.model.UsagePeriod
import com.example.domain.model.UsageScope
import com.example.ui.theme.MonoFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(
    viewModel: UsageViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val csvSavedMsg = stringResource(R.string.usage_csv_exported)
    val csvErrorMsg = stringResource(R.string.usage_csv_export_failed)

    val csvExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val csv = viewModel.buildCsvExport()
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(csv.toByteArray(Charsets.UTF_8))
                        out.flush()
                    } ?: error("无法打开文件进行写入")
                }
            }
            if (result.isSuccess) {
                snackbarHostState.showSnackbar(csvSavedMsg)
            } else {
                snackbarHostState.showSnackbar("$csvErrorMsg: ${result.exceptionOrNull()?.message.orEmpty()}")
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.usage_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (uiState.activeServerName.isNotBlank()) {
                            Text(
                                text = uiState.activeServerName,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("usage_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::syncFromServer,
                        enabled = uiState.isConnected && !uiState.isSyncing,
                        modifier = Modifier.testTag("usage_sync_button"),
                    ) {
                        if (uiState.isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.usage_sync_action),
                            )
                        }
                    }
                    IconButton(
                        onClick = { csvExportLauncher.launch("pocketpilot-usage.csv") },
                        enabled = uiState.records.isNotEmpty(),
                        modifier = Modifier.testTag("usage_export_csv_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FileDownload,
                            contentDescription = stringResource(R.string.usage_export_csv_action),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 720.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "scope_and_period") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("usage_scope_selector"),
                        ) {
                            val hasWorkspace = uiState.activeWorkspacePath.isNotBlank()
                            SegmentedButton(
                                selected = uiState.scope == UsageScope.WORKSPACE,
                                onClick = { viewModel.setScope(UsageScope.WORKSPACE) },
                                enabled = hasWorkspace,
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                modifier = Modifier.testTag("usage_scope_workspace"),
                            ) {
                                Text(
                                    text = stringResource(R.string.usage_scope_workspace),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            SegmentedButton(
                                selected = uiState.scope == UsageScope.SERVER,
                                onClick = { viewModel.setScope(UsageScope.SERVER) },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                modifier = Modifier.testTag("usage_scope_server"),
                            ) {
                                Text(
                                    text = stringResource(R.string.usage_scope_server),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        if (uiState.scope == UsageScope.WORKSPACE && uiState.activeWorkspacePath.isNotBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = uiState.activeWorkspacePath,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("usage_period_row"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val periods = listOf(
                                UsagePeriod.TODAY to R.string.usage_period_today,
                                UsagePeriod.DAYS_7 to R.string.usage_period_7d,
                                UsagePeriod.DAYS_30 to R.string.usage_period_30d,
                                UsagePeriod.ALL to R.string.usage_period_all,
                            )
                            periods.forEach { (period, labelRes) ->
                                FilterChip(
                                    selected = uiState.period == period,
                                    onClick = { viewModel.setPeriod(period) },
                                    label = {
                                        Text(
                                            text = stringResource(labelRes),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("usage_period_${period.name.lowercase()}"),
                                )
                            }
                        }
                    }
                }

                item(key = "summary_card") {
                    UsageSummaryCard(uiState = uiState)
                }

                item(key = "manual_balance_card") {
                    ManualBalanceCard(
                        summary = uiState.manualBalance,
                        inputText = uiState.manualBalanceInputText,
                        onInputChange = viewModel::onManualBalanceInputChange,
                        onSave = viewModel::saveManualBalance,
                        onClear = viewModel::clearManualBalance,
                    )
                }

                item(key = "breakdown_tabs") {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("usage_breakdown_tabs"),
                    ) {
                        val tabs = listOf(
                            UsageBreakdownTab.BY_DAY to R.string.usage_tab_by_day,
                            UsageBreakdownTab.BY_MODEL to R.string.usage_tab_by_model,
                            UsageBreakdownTab.BY_PROVIDER to R.string.usage_tab_by_provider,
                        )
                        tabs.forEachIndexed { index, (tab, labelRes) ->
                            SegmentedButton(
                                selected = uiState.tab == tab,
                                onClick = { viewModel.setTab(tab) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
                                modifier = Modifier.testTag("usage_tab_${tab.name.lowercase()}"),
                            ) {
                                Text(
                                    text = stringResource(labelRes),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                if (uiState.rows.isEmpty()) {
                    item(key = "empty_state") {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Analytics,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(32.dp),
                                )
                                Text(
                                    text = stringResource(R.string.usage_empty_title),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = stringResource(R.string.usage_empty_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (uiState.isConnected) {
                                    FilledTonalButton(
                                        onClick = viewModel::syncFromServer,
                                        enabled = !uiState.isSyncing,
                                        modifier = Modifier.testTag("usage_empty_sync_button"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Refresh,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(text = stringResource(R.string.usage_sync_action))
                                    }
                                }
                            }
                        }
                    }
                } else {
                    items(
                        items = uiState.rows,
                        key = { "${uiState.tab.name}_${it.key}" },
                    ) { row ->
                        UsageAggregateRowCard(row = row)
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageSummaryCard(
    uiState: UsageUiState,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("usage_summary_card"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.usage_summary_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.usage_requests_count, uiState.totalRequests),
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetricTile(
                    label = stringResource(R.string.usage_metric_total_tokens),
                    value = UsageCalculator.formatTokenCount(uiState.totalTokens),
                    subtext = stringResource(
                        R.string.usage_metric_io_breakdown,
                        UsageCalculator.formatTokenCount(uiState.totalInputTokens),
                        UsageCalculator.formatTokenCount(uiState.totalOutputTokens),
                    ),
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = stringResource(R.string.usage_metric_cost),
                    value = uiState.totalCurrencyCostUsd?.let { UsageCalculator.formatUsd(it) }
                        ?: stringResource(R.string.usage_cost_not_set),
                    subtext = if (uiState.totalCacheTokens > 0L) {
                        stringResource(
                            R.string.usage_metric_cache_breakdown,
                            UsageCalculator.formatTokenCount(uiState.totalCacheTokens),
                        )
                    } else {
                        null
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.usage_metric_provider_units),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = stringResource(R.string.usage_estimated_by_app_caption),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = UsageCalculator.formatUnits(uiState.totalProviderUnits),
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = MonoFontFamily),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("usage_total_units_text"),
                )
            }
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    subtext: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = MonoFontFamily),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtext.isNullOrBlank()) {
                Text(
                    text = subtext,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ManualBalanceCard(
    summary: ManualBalanceSummary?,
    inputText: String,
    onInputChange: (String) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    val enteredDateFormatted = remember(summary?.enteredAtMillis) {
        val ts = summary?.enteredAtMillis ?: return@remember null
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(ts))
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("usage_manual_balance_card"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.AccountBalanceWallet,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.usage_manual_balance_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.usage_manual_balance_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (summary != null) {
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier.testTag("usage_manual_balance_clear"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = stringResource(R.string.usage_manual_balance_clear_cd),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            if (summary != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (enteredDateFormatted != null) {
                            Text(
                                text = stringResource(
                                    R.string.usage_manual_balance_entered_at,
                                    UsageCalculator.formatUnits(summary.initialBalanceUnits),
                                    enteredDateFormatted,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = stringResource(
                                R.string.usage_manual_balance_spent,
                                UsageCalculator.formatUnits(summary.spentSinceEnteredUnits),
                            ),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                        )
                        Text(
                            text = stringResource(
                                R.string.usage_manual_balance_remaining,
                                UsageCalculator.formatUnits(summary.remainingUnits),
                            ),
                            style = MaterialTheme.typography.titleSmall.copy(fontFamily = MonoFontFamily),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        val estReq = summary.estimatedRemainingRequests
                        val avgUnits = summary.averageUnitsPerRequest
                        if (estReq != null && avgUnits != null) {
                            Text(
                                text = stringResource(
                                    R.string.usage_manual_balance_est_requests,
                                    estReq,
                                    UsageCalculator.formatUnits(avgUnits),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    label = { Text(stringResource(R.string.usage_manual_balance_input_label)) },
                    placeholder = { Text("1000000") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("usage_manual_balance_input"),
                )
                Button(
                    onClick = onSave,
                    enabled = inputText.isNotBlank(),
                    modifier = Modifier.testTag("usage_manual_balance_save"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Save,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.action_save))
                }
            }
        }
    }
}

@Composable
private fun UsageAggregateRowCard(
    row: UsageAggregateRow,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontFamily = MonoFontFamily),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!row.subtitle.isNullOrBlank()) {
                        Text(
                            text = row.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = row.totalCurrencyCostUsd?.let { UsageCalculator.formatUsd(it) }
                            ?: stringResource(R.string.usage_cost_not_set),
                        style = MaterialTheme.typography.labelLarge.copy(fontFamily = MonoFontFamily),
                        color = if (row.totalCurrencyCostUsd != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        text = UsageCalculator.formatUnits(row.totalProviderUnits),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val cacheTotal = row.cacheReadTokens + row.cacheWriteTokens
            val cachePart = if (cacheTotal > 0L) {
                " • 缓存 ${UsageCalculator.formatTokenCount(cacheTotal)}"
            } else {
                ""
            }
            Text(
                text = "输入 ${UsageCalculator.formatTokenCount(row.inputTokens)} / 输出 ${
                    UsageCalculator.formatTokenCount(row.outputTokens)
                }$cachePart • 总计 ${UsageCalculator.formatTokenCount(row.totalTokens)}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
