package com.example.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.domain.model.ConfiguredProviderInfo
import com.example.domain.model.InstallStepItem
import com.example.domain.model.InstallStepStatus
import com.example.domain.model.OpenCodeEnvInfo
import com.example.domain.model.OpenCodeServerStatus
import com.example.domain.model.OpenCodeSetupUiState
import com.example.domain.model.OpenCodeVerificationReport
import com.example.domain.model.SetupWizardStage
import com.example.ui.theme.LocalStatusColors
import com.example.ui.theme.MonoFontFamily

@Composable
fun OpenCodeSetupView(
    setupState: OpenCodeSetupUiState,
    openCodePort: Int,
    onRecheck: () -> Unit,
    onOpenInstallPreview: () -> Unit,
    onOpenUpdatePreview: () -> Unit,
    onTogglePasswordAuth: (Boolean) -> Unit,
    onToggleVerifyPersistence: (Boolean) -> Unit,
    onConfirmInstall: () -> Unit,
    onCancelWizard: () -> Unit,
    onRetryFailedStep: () -> Unit,
    onStartServer: () -> Unit,
    onRefreshConfiguredProviders: () -> Unit,
    onStartNewProvider: () -> Unit,
    onEditConfiguredProvider: (ConfiguredProviderInfo) -> Unit,
    onChangeProviderKey: (ConfiguredProviderInfo, String) -> Unit,
    onDeleteConfiguredProvider: (ConfiguredProviderInfo) -> Unit,
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
    onTestSelectedModel: () -> Unit,
    onSaveCustomProvider: () -> Unit,
    onFinishWithFreeModels: () -> Unit,
    onLogCopied: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        when (setupState.wizardStage) {
            SetupWizardStage.PREVIEW_COMMANDS -> {
                InstallPreviewPane(
                    setupState = setupState,
                    openCodePort = openCodePort,
                    onTogglePasswordAuth = onTogglePasswordAuth,
                    onToggleVerifyPersistence = onToggleVerifyPersistence,
                    onConfirm = onConfirmInstall,
                    onCancel = onCancelWizard,
                )
            }

            SetupWizardStage.INSTALLING -> {
                InstallProgressPane(
                    setupState = setupState,
                    openCodePort = openCodePort,
                    onRetryStep = onRetryFailedStep,
                    onCancel = onCancelWizard,
                    onLogCopied = onLogCopied,
                )
            }

            SetupWizardStage.CONNECT_MODEL -> {
                ConnectModelPane(
                    setupState = setupState,
                    openCodePort = openCodePort,
                    onClose = onCancelWizard,
                    onRefreshProviders = onRefreshConfiguredProviders,
                    onStartNewProvider = onStartNewProvider,
                    onEditProvider = onEditConfiguredProvider,
                    onChangeProviderKey = onChangeProviderKey,
                    onDeleteProvider = onDeleteConfiguredProvider,
                    onSelectCustomProvider = onSelectCustomProvider,
                    onProviderNameChange = onProviderNameChange,
                    onBaseUrlChange = onBaseUrlChange,
                    onApiKeyChange = onApiKeyChange,
                    onFetchModels = onFetchModels,
                    onModelSearchChange = onModelSearchChange,
                    onToggleModelSelection = onToggleModelSelection,
                    onUpdateModelDisplayName = onUpdateModelDisplayName,
                    onSelectDefaultModel = onSelectDefaultModel,
                    onManualModelIdChange = onManualModelIdChange,
                    onAddManualModel = onAddManualModel,
                    onTestModel = onTestSelectedModel,
                    onSaveProvider = onSaveCustomProvider,
                    onFinishFree = onFinishWithFreeModels,
                )
            }

            SetupWizardStage.IDLE -> {
                when (setupState.serverStatus) {
                    OpenCodeServerStatus.CHECKING -> {
                        CheckingServerPane(openCodePort = openCodePort)
                    }

                    OpenCodeServerStatus.NOT_INSTALLED -> {
                        NotInstalledPane(
                            openCodePort = openCodePort,
                            errorMessage = setupState.errorMessage,
                            onInstallClick = onOpenInstallPreview,
                            onRecheck = onRecheck,
                        )
                    }

                    OpenCodeServerStatus.INSTALLED_STOPPED -> {
                        InstalledStoppedPane(
                            envInfo = setupState.envInfo,
                            openCodePort = openCodePort,
                            isStarting = setupState.isRunningAction,
                            errorMessage = setupState.errorMessage,
                            liveLog = setupState.liveLog,
                            onStartServer = onStartServer,
                            onConfigureService = onOpenInstallPreview,
                            onUpdateBinary = onOpenUpdatePreview,
                            onRecheck = onRecheck,
                            onLogCopied = onLogCopied,
                        )
                    }

                    OpenCodeServerStatus.RUNNING -> Unit
                }
            }
        }
    }
}

@Composable
private fun CheckingServerPane(openCodePort: Int) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.setup_checking_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.setup_checking_subtitle, openCodePort),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NotInstalledPane(
    openCodePort: Int,
    errorMessage: String?,
    onInstallClick: () -> Unit,
    onRecheck: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 540.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp),
                    )
                }

                Text(
                    text = stringResource(R.string.setup_not_installed_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.setup_not_installed_desc, openCodePort),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                errorMessage?.let { err ->
                    ErrorBannerCard(message = err)
                }

                Button(
                    onClick = onInstallClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("opencode_install_start_button"),
                ) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.setup_install_button))
                }

                TextButton(
                    onClick = onRecheck,
                    modifier = Modifier.testTag("opencode_recheck_button"),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.setup_recheck_button))
                }
            }
        }
    }
}

@Composable
private fun InstalledStoppedPane(
    envInfo: OpenCodeEnvInfo,
    openCodePort: Int,
    isStarting: Boolean,
    errorMessage: String?,
    liveLog: String,
    onStartServer: () -> Unit,
    onConfigureService: () -> Unit,
    onUpdateBinary: () -> Unit,
    onRecheck: () -> Unit,
    onLogCopied: () -> Unit,
) {
    val context = LocalContext.current
    val binaryLabel = buildString {
        append(envInfo.opencodePath ?: "opencode")
        envInfo.opencodeVersion?.let { append(" • $it") }
    }
    val hasPersistentUnit = envInfo.hasPersistentUnit

    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 540.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(LocalStatusColors.current.warning.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = LocalStatusColors.current.warning,
                        modifier = Modifier.size(34.dp),
                    )
                }

                Text(
                    text = stringResource(R.string.setup_stopped_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.setup_stopped_desc, binaryLabel, openCodePort),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (envInfo.isTransientService || !hasPersistentUnit) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = LocalStatusColors.current.warning.copy(alpha = 0.14f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.setup_transient_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                errorMessage?.let { err ->
                    ErrorBannerCard(message = err)
                }

                if (hasPersistentUnit) {
                    Button(
                        onClick = onStartServer,
                        enabled = !isStarting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("opencode_start_server_button"),
                    ) {
                        if (isStarting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.setup_start_server_button))
                    }
                } else {
                    Button(
                        onClick = onConfigureService,
                        enabled = !isStarting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("opencode_configure_service_button"),
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.setup_configure_service_button))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = onUpdateBinary,
                        enabled = !isStarting,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("opencode_update_binary_button"),
                    ) {
                        Icon(Icons.Rounded.Update, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = stringResource(R.string.setup_already_installed_update))
                    }
                    if (hasPersistentUnit) {
                        OutlinedButton(
                            onClick = onConfigureService,
                            enabled = !isStarting,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(text = stringResource(R.string.settings_opencode_repair))
                        }
                    }
                }

                if (liveLog.isNotBlank()) {
                    CollapsibleLiveLogCard(
                        logText = liveLog,
                        onCopy = {
                            copySetupLog(context, liveLog)
                            onLogCopied()
                        },
                    )
                }

                TextButton(onClick = onRecheck, enabled = !isStarting) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.setup_recheck_button))
                }
            }
        }
    }
}

@Composable
private fun InstallPreviewPane(
    setupState: OpenCodeSetupUiState,
    openCodePort: Int,
    onTogglePasswordAuth: (Boolean) -> Unit,
    onToggleVerifyPersistence: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 700.dp)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.setup_preview_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.setup_preview_subtitle, openCodePort),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Configurable options before confirming
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.setup_option_password_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(R.string.setup_option_password_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = setupState.enablePasswordAuth,
                        onCheckedChange = onTogglePasswordAuth,
                        modifier = Modifier.testTag("setup_password_auth_switch"),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VerifiedUser,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.setup_option_persistence_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(R.string.setup_option_persistence_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = setupState.verifyPersistenceOnRestart,
                        onCheckedChange = onToggleVerifyPersistence,
                        modifier = Modifier.testTag("setup_persistence_check_switch"),
                    )
                }
            }
        }

        setupState.steps.forEach { step ->
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
                    Text(
                        text = step.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = step.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = step.commandPreview,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = MonoFontFamily,
                                fontSize = 11.sp,
                            ),
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = stringResource(R.string.action_cancel))
            }
            Button(
                onClick = onConfirm,
                modifier = Modifier
                    .weight(1.4f)
                    .testTag("opencode_confirm_install_button"),
            ) {
                Text(text = stringResource(R.string.setup_preview_confirm))
            }
        }
    }
}

@Composable
private fun InstallProgressPane(
    setupState: OpenCodeSetupUiState,
    openCodePort: Int,
    onRetryStep: () -> Unit,
    onCancel: () -> Unit,
    onLogCopied: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 700.dp)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.setup_installing_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )

        setupState.steps.forEach { step ->
            StepStatusCard(step = step)
        }

        setupState.verificationReport?.let { report ->
            VerificationReportCard(
                report = report,
                openCodePort = openCodePort,
            )
        }

        setupState.errorMessage?.let { err ->
            ErrorBannerCard(message = err)
        }

        CollapsibleLiveLogCard(
            logText = setupState.liveLog,
            initiallyExpanded = setupState.errorMessage != null,
            onCopy = {
                copySetupLog(context, setupState.liveLog)
                onLogCopied()
            },
        )

        if (setupState.failedStepIndex != null && !setupState.isRunningAction) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        copySetupLog(context, setupState.liveLog)
                        onLogCopied()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("opencode_copy_log_button"),
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.setup_copy_log))
                }
                Button(
                    onClick = onRetryStep,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("opencode_retry_step_button"),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.setup_retry_step))
                }
            }
            TextButton(onClick = onCancel) {
                Text(text = stringResource(R.string.action_back))
            }
        }
    }
}

@Composable
internal fun VerificationReportCard(
    report: OpenCodeVerificationReport,
    openCodePort: Int,
) {
    val statusColors = LocalStatusColors.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = statusColors.connected.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, statusColors.connected.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("setup_verification_report_card"),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.setup_verify_card_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            VerificationCheckRow(
                passed = report.serviceActive,
                label = stringResource(R.string.setup_verify_service_label),
                detail = report.serviceActiveDetail,
            )
            VerificationCheckRow(
                passed = report.portListening,
                label = stringResource(R.string.setup_verify_port_label, openCodePort),
                detail = report.portListeningDetail,
            )
            VerificationCheckRow(
                passed = report.sessionJsonOk,
                label = stringResource(R.string.setup_verify_session_label),
                detail = report.sessionJsonDetail,
            )
            report.restartSurvived?.let { survived ->
                VerificationCheckRow(
                    passed = survived,
                    label = stringResource(R.string.setup_verify_restart_label),
                    detail = report.restartSurvivedDetail,
                )
            }
            Text(
                text = stringResource(R.string.setup_reboot_autostart_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VerificationCheckRow(
    passed: Boolean,
    label: String,
    detail: String,
) {
    val tint = if (passed) LocalStatusColors.current.connected else MaterialTheme.colorScheme.error
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (passed) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                    color = tint,
                )
            }
        }
    }
}

@Composable
private fun StepStatusCard(step: InstallStepItem) {
    val statusColors = LocalStatusColors.current
    val (labelRes, tint) = when (step.status) {
        InstallStepStatus.PENDING -> R.string.setup_step_pending to MaterialTheme.colorScheme.onSurfaceVariant
        InstallStepStatus.RUNNING -> R.string.setup_step_running to MaterialTheme.colorScheme.primary
        InstallStepStatus.SUCCESS -> R.string.setup_step_success to statusColors.connected
        InstallStepStatus.ERROR -> R.string.setup_step_error to MaterialTheme.colorScheme.error
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (step.status == InstallStepStatus.ERROR) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (step.status) {
                InstallStepStatus.PENDING -> Icon(
                    imageVector = Icons.Rounded.HourglassEmpty,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )

                InstallStepStatus.RUNNING -> CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp,
                )

                InstallStepStatus.SUCCESS -> Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )

                InstallStepStatus.ERROR -> Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = step.detail?.takeIf { it.isNotBlank() } ?: stringResource(labelRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = tint,
                )
            }
        }
    }
}

@Composable
private fun CollapsibleLiveLogCard(
    logText: String,
    initiallyExpanded: Boolean = false,
    onCopy: () -> Unit,
) {
    var expanded by remember(initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.setup_live_log_toggle),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (logText.isNotBlank()) {
                    IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = stringResource(R.string.setup_copy_log),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 90.dp, max = 260.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = logText.ifBlank { "…" },
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = MonoFontFamily,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ErrorBannerCard(message: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp),
            )
            SelectionContainer {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

private fun copySetupLog(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("OpenCode Install Log", text))
}
