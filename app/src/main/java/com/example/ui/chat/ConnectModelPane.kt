package com.example.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.ConfiguredProviderInfo
import com.example.domain.model.CustomModelEntry
import com.example.domain.model.ModelTestResult
import com.example.domain.model.OpenCodeServerStatus
import com.example.domain.model.OpenCodeSetupUiState
import com.example.ui.components.StatusChip
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.MonoFontFamily

@Composable
internal fun ConnectModelPane(
    setupState: OpenCodeSetupUiState,
    openCodePort: Int,
    onClose: () -> Unit,
    onRefreshProviders: () -> Unit,
    onStartNewProvider: () -> Unit,
    onEditProvider: (ConfiguredProviderInfo) -> Unit,
    onChangeProviderKey: (ConfiguredProviderInfo, String) -> Unit,
    onDeleteProvider: (ConfiguredProviderInfo) -> Unit,
    onSelectCustomProvider: (Boolean) -> Unit,
    onProviderNameChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onFetchModels: () -> Unit,
    onModelSearchChange: (String) -> Unit,
    onToggleModelSelection: (String) -> Unit,
    onUpdateModelDisplayName: (String, String) -> Unit,
    onSelectDefaultModel: (String) -> Unit,
    onManualModelIdChange: (String) -> Unit,
    onAddManualModel: () -> Unit,
    onTestModel: () -> Unit,
    onSaveProvider: () -> Unit,
    onFinishFree: () -> Unit,
) {
    var showApiKey by remember { mutableStateOf(false) }
    var changeKeyTarget by remember { mutableStateOf<ConfiguredProviderInfo?>(null) }
    var deleteTarget by remember { mutableStateOf<ConfiguredProviderInfo?>(null) }

    val filteredModels = remember(setupState.availableModels, setupState.modelSearchQuery) {
        val q = setupState.modelSearchQuery.trim().lowercase()
        if (q.isEmpty()) {
            setupState.availableModels
        } else {
            setupState.availableModels.filter {
                it.id.lowercase().contains(q) || it.displayName.lowercase().contains(q)
            }
        }
    }
    val selectedModels = setupState.selectedModels
    val effectiveTestModelId = setupState.effectiveTestModelId

    Column(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 720.dp)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (setupState.serverStatus == OpenCodeServerStatus.RUNNING) {
                IconButton(
                    onClick = onClose,
                    enabled = !setupState.isSavingProvider && !setupState.isProviderActionBusy,
                    modifier = Modifier.testTag("connect_model_back_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Rounded.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.model_screen_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.setup_model_subtitle, openCodePort),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        setupState.verificationReport?.let { report ->
            VerificationReportCard(
                report = report,
                openCodePort = openCodePort,
            )
        }

        // Section 1: Configured Providers List (~/.config/opencode/opencode.json)
        ConfiguredProvidersSection(
            providers = setupState.configuredProviders,
            isLoading = setupState.isLoadingConfiguredProviders,
            isBusy = setupState.isProviderActionBusy || setupState.isSavingProvider,
            editingProviderId = setupState.editingProviderId,
            errorMessage = setupState.configuredProvidersError,
            onRefresh = onRefreshProviders,
            onAddNew = onStartNewProvider,
            onEdit = onEditProvider,
            onChangeKey = { changeKeyTarget = it },
            onDelete = { deleteTarget = it },
        )

        // Option 1: Free OpenCode models
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                1.dp,
                if (!setupState.useCustomProvider) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelectCustomProvider(false) }
                .testTag("setup_model_free_option"),
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = !setupState.useCustomProvider,
                    onClick = { onSelectCustomProvider(false) },
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.setup_model_option_free),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.setup_model_option_free_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Option 2: Custom OpenAI-compatible provider editor
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                1.dp,
                if (setupState.useCustomProvider) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("setup_model_custom_option"),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectCustomProvider(true) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = setupState.useCustomProvider,
                        onClick = { onSelectCustomProvider(true) },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (setupState.editingProviderId != null) {
                                stringResource(R.string.model_editor_edit_title, setupState.providerName.ifBlank { setupState.editingProviderId })
                            } else {
                                stringResource(R.string.setup_model_option_custom)
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.setup_model_option_custom_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (setupState.editingProviderId != null) {
                        TextButton(onClick = onStartNewProvider) {
                            Text(stringResource(R.string.model_providers_add_new))
                        }
                    }
                }

                AnimatedVisibility(visible = setupState.useCustomProvider) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = setupState.providerName,
                            onValueChange = onProviderNameChange,
                            label = { Text(stringResource(R.string.setup_model_provider_name_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("custom_provider_name_input"),
                        )

                        OutlinedTextField(
                            value = setupState.baseUrl,
                            onValueChange = onBaseUrlChange,
                            label = { Text(stringResource(R.string.setup_model_baseurl_label)) },
                            placeholder = { Text(stringResource(R.string.setup_model_baseurl_placeholder)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("custom_provider_baseurl_input"),
                        )

                        OutlinedTextField(
                            value = setupState.apiKeyInput,
                            onValueChange = onApiKeyChange,
                            label = { Text(stringResource(R.string.setup_model_apikey_label)) },
                            supportingText = setupState.editingMaskedApiKey?.let { masked ->
                                {
                                    Text(
                                        text = stringResource(R.string.model_editor_apikey_keep_hint, masked),
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                    )
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showApiKey = !showApiKey }) {
                                    Icon(
                                        imageVector = if (showApiKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                        contentDescription = stringResource(
                                            if (showApiKey) R.string.action_hide_password else R.string.action_show_password,
                                        ),
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("custom_provider_apikey_input"),
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = stringResource(R.string.setup_model_security_note),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // Step 1: Fetch models via GET {baseURL}/models from server
                        FilledTonalButton(
                            onClick = onFetchModels,
                            enabled = !setupState.isFetchingModels && !setupState.isSavingProvider && setupState.baseUrl.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("custom_provider_fetch_models_button"),
                        ) {
                            if (setupState.isFetchingModels) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = stringResource(R.string.model_fetch_loading))
                            } else {
                                Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = stringResource(R.string.model_fetch_button))
                            }
                        }

                        setupState.fetchModelsCount?.let { total ->
                            Text(
                                text = stringResource(R.string.model_fetch_loaded_summary, total, selectedModels.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalStatusColors.current.connected,
                            )
                        }

                        setupState.fetchModelsError?.let { err ->
                            ErrorBannerCard(message = err)
                        }

                        // Searchable multi-select model list
                        if (setupState.availableModels.isNotEmpty()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            OutlinedTextField(
                                value = setupState.modelSearchQuery,
                                onValueChange = onModelSearchChange,
                                placeholder = { Text(stringResource(R.string.model_search_placeholder)) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                },
                                trailingIcon = if (setupState.modelSearchQuery.isNotEmpty()) {
                                    {
                                        IconButton(onClick = { onModelSearchChange("") }) {
                                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close))
                                        }
                                    }
                                } else {
                                    null
                                },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("custom_provider_model_search_input"),
                            )

                            if (filteredModels.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.model_search_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            } else {
                                val listHeight = if (filteredModels.size <= 4) {
                                    (filteredModels.size * 56 + 8).dp
                                } else {
                                    248.dp
                                }
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(listHeight),
                                ) {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(vertical = 4.dp),
                                    ) {
                                        items(filteredModels, key = { it.id }) { model ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { onToggleModelSelection(model.id) }
                                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Checkbox(
                                                    checked = model.selected,
                                                    onCheckedChange = { onToggleModelSelection(model.id) },
                                                )
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = model.id,
                                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    if (model.displayName != model.id && model.displayName.isNotBlank()) {
                                                        Text(
                                                            text = model.displayName,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Selected models with editable display names & default picker
                        if (selectedModels.isNotEmpty()) {
                            SelectedModelsEditor(
                                selectedModels = selectedModels,
                                defaultModelId = effectiveTestModelId,
                                onSelectDefault = onSelectDefaultModel,
                                onUpdateName = onUpdateModelDisplayName,
                                onRemove = onToggleModelSelection,
                            )
                        }

                        // Manual model ID fallback input
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = setupState.manualModelIdInput,
                                onValueChange = onManualModelIdChange,
                                label = { Text(stringResource(R.string.model_manual_add_label)) },
                                placeholder = { Text(stringResource(R.string.setup_model_id_placeholder)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { onAddManualModel() }),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("custom_provider_manual_model_input"),
                            )
                            FilledTonalButton(
                                onClick = onAddManualModel,
                                enabled = setupState.manualModelIdInput.isNotBlank(),
                                modifier = Modifier.testTag("custom_provider_manual_model_add_button"),
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.model_manual_add_button))
                            }
                        }

                        // Step 2: Live test via POST {baseURL}/chat/completions ("ответь одним словом")
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        if (effectiveTestModelId.isNotBlank()) {
                            Text(
                                text = stringResource(R.string.model_test_target_label, effectiveTestModelId),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        OutlinedButton(
                            onClick = onTestModel,
                            enabled = !setupState.isTestingModel &&
                                !setupState.isSavingProvider &&
                                setupState.baseUrl.isNotBlank() &&
                                effectiveTestModelId.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("custom_provider_test_model_button"),
                        ) {
                            if (setupState.isTestingModel) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = stringResource(R.string.model_test_running))
                            } else {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = stringResource(R.string.model_test_button))
                            }
                        }

                        setupState.modelTestResult?.let { result ->
                            ModelTestResultCard(result = result)
                        }
                    }
                }
            }
        }

        setupState.errorMessage?.let { err ->
            ErrorBannerCard(message = err)
        }

        if (setupState.useCustomProvider) {
            Button(
                onClick = onSaveProvider,
                enabled = !setupState.isSavingProvider &&
                    !setupState.isProviderActionBusy &&
                    setupState.baseUrl.isNotBlank() &&
                    (selectedModels.isNotEmpty() || setupState.manualModelIdInput.isNotBlank()),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("custom_provider_save_button"),
            ) {
                if (setupState.isSavingProvider) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.model_saving_progress))
                } else {
                    Text(text = stringResource(R.string.model_save_and_select_button))
                }
            }
        } else {
            Button(
                onClick = onFinishFree,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("setup_model_open_chat_button"),
            ) {
                Text(text = stringResource(R.string.setup_model_open_chat))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    changeKeyTarget?.let { target ->
        ChangeProviderKeyDialog(
            provider = target,
            onConfirm = { newKey ->
                changeKeyTarget = null
                onChangeProviderKey(target, newKey)
            },
            onDismiss = { changeKeyTarget = null },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.model_delete_dialog_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.model_delete_dialog_desc,
                        target.name,
                        target.providerId,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        onDeleteProvider(target)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.model_provider_action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfiguredProvidersSection(
    providers: List<ConfiguredProviderInfo>,
    isLoading: Boolean,
    isBusy: Boolean,
    editingProviderId: String?,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onAddNew: () -> Unit,
    onEdit: (ConfiguredProviderInfo) -> Unit,
    onChangeKey: (ConfiguredProviderInfo) -> Unit,
    onDelete: (ConfiguredProviderInfo) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("configured_providers_section"),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.model_providers_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onRefresh,
                    enabled = !isLoading && !isBusy,
                    modifier = Modifier.testTag("configured_providers_refresh_button"),
                ) {
                    if (isLoading || isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.action_refresh),
                        )
                    }
                }
            }

            errorMessage?.let { err ->
                ErrorBannerCard(message = err)
            }

            when {
                isLoading && providers.isEmpty() -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 6.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.model_providers_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                providers.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.model_providers_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    providers.forEach { provider ->
                        val isEditingThis = editingProviderId == provider.providerId
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isEditingThis) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                            border = BorderStroke(
                                1.dp,
                                if (isEditingThis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("configured_provider_card_${provider.providerId}"),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = provider.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (provider.baseUrl.isNotBlank()) {
                                            Text(
                                                text = provider.baseUrl,
                                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    if (provider.isDefaultProvider) {
                                        StatusChip(
                                            label = stringResource(R.string.model_provider_default_badge),
                                            accentColor = LocalStatusColors.current.connected,
                                        )
                                    }
                                }

                                // Masked API key (••••XXXX) & model count
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Key,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Text(
                                            text = if (provider.maskedApiKey != null) {
                                                stringResource(R.string.model_provider_key_label, provider.maskedApiKey)
                                            } else {
                                                stringResource(R.string.model_provider_no_key)
                                            },
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.model_provider_models_count, provider.models.size),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                if (provider.models.isNotEmpty()) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        provider.models.take(6).forEach { m ->
                                            val isDef = provider.defaultModelId == m.id
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = if (isDef) {
                                                    MaterialTheme.colorScheme.primaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.surface
                                                },
                                                border = BorderStroke(
                                                    1.dp,
                                                    if (isDef) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                                ),
                                            ) {
                                                Text(
                                                    text = m.name,
                                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                )
                                            }
                                        }
                                        if (provider.models.size > 6) {
                                            Text(
                                                text = "+${provider.models.size - 6}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                                            )
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    OutlinedButton(
                                        onClick = { onEdit(provider) },
                                        enabled = !isBusy,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("provider_edit_${provider.providerId}"),
                                    ) {
                                        Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = stringResource(R.string.model_provider_action_edit),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { onChangeKey(provider) },
                                        enabled = !isBusy,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("provider_change_key_${provider.providerId}"),
                                    ) {
                                        Icon(Icons.Rounded.Key, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = stringResource(R.string.model_provider_action_change_key),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDelete(provider) },
                                        enabled = !isBusy,
                                        modifier = Modifier.testTag("provider_delete_${provider.providerId}"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = stringResource(R.string.model_provider_action_delete),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (editingProviderId != null) {
                        TextButton(
                            onClick = onAddNew,
                            enabled = !isBusy,
                            modifier = Modifier.testTag("configured_providers_add_new_button"),
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.model_providers_add_new))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectedModelsEditor(
    selectedModels: List<CustomModelEntry>,
    defaultModelId: String,
    onSelectDefault: (String) -> Unit,
    onUpdateName: (String, String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.model_selected_section_title, selectedModels.size),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.model_selected_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        selectedModels.forEach { entry ->
            val isDefault = entry.id == defaultModelId
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isDefault) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
                border = BorderStroke(
                    1.dp,
                    if (isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(
                            onClick = { onSelectDefault(entry.id) },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = if (isDefault) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                                contentDescription = stringResource(R.string.model_make_default),
                                tint = if (isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Text(
                            text = entry.id,
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (isDefault) {
                            StatusChip(
                                label = stringResource(R.string.model_provider_default_badge),
                                accentColor = MaterialTheme.colorScheme.primary,
                            )
                        }
                        IconButton(
                            onClick = { onRemove(entry.id) },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.model_provider_action_delete),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    OutlinedTextField(
                        value = entry.displayName,
                        onValueChange = { onUpdateName(entry.id, it) },
                        label = { Text(stringResource(R.string.model_display_name_inline_label)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("selected_model_name_${entry.id}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelTestResultCard(result: ModelTestResult) {
    val statusColors = LocalStatusColors.current
    val containerColor = if (result.success) {
        statusColors.connected.copy(alpha = 0.10f)
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val borderColor = if (result.success) {
        statusColors.connected.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
    }
    val contentColor = if (result.success) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    val accentTint = if (result.success) statusColors.connected else MaterialTheme.colorScheme.error

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model_test_result_card"),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (result.success) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = accentTint,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = when {
                        result.success -> stringResource(R.string.model_test_success_title, result.httpCode ?: 200)
                        result.httpCode != null -> stringResource(R.string.model_test_error_http_title, result.httpCode)
                        else -> stringResource(R.string.model_test_error_title)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = accentTint,
                )
            }
            SelectionContainer {
                Text(
                    text = result.message,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                    color = contentColor,
                )
            }
        }
    }
}

@Composable
private fun ChangeProviderKeyDialog(
    provider: ConfiguredProviderInfo,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var keyInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.model_change_key_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.model_change_key_dialog_desc, provider.name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text(stringResource(R.string.model_change_key_input_label)) },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                imageVector = if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = stringResource(
                                    if (showKey) R.string.action_hide_password else R.string.action_show_password,
                                ),
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("change_provider_key_input"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(keyInput) },
                enabled = keyInput.isNotBlank(),
                modifier = Modifier.testTag("change_provider_key_confirm_button"),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
