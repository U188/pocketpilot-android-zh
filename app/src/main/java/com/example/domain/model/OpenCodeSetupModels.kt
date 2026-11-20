package com.example.domain.model

enum class OpenCodeServerStatus {
    CHECKING,
    NOT_INSTALLED,
    INSTALLED_STOPPED,
    RUNNING,
}

enum class SystemdServiceState {
    ACTIVE,
    INACTIVE,
    FAILED,
    NOT_FOUND,
    UNKNOWN,
}

enum class InstallStepStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    ERROR,
}

enum class SetupWizardStage {
    IDLE,
    PREVIEW_COMMANDS,
    INSTALLING,
    CONNECT_MODEL,
}

data class OpenCodeEnvInfo(
    val osName: String = "",
    val arch: String = "",
    val userName: String = "",
    val homeDir: String = "",
    val freeSpaceMb: Long = -1L,
    val hasCurl: Boolean = false,
    val hasSystemd: Boolean = false,
    val hasTmux: Boolean = false,
    val isRoot: Boolean = false,
    val hasPasswordlessSudo: Boolean = false,
    val hasSudo: Boolean = false,
    val opencodePath: String? = null,
    val opencodeVersion: String? = null,
    val serviceState: SystemdServiceState = SystemdServiceState.NOT_FOUND,
    val isUserService: Boolean = false,
    val isTransientService: Boolean = false,
    val hasPasswordProtection: Boolean = false,
    val openCodePort: Int = 4096,
) {
    val hasPersistentUnit: Boolean
        get() = serviceState != SystemdServiceState.NOT_FOUND && !isTransientService
}

data class OpenCodeVerificationReport(
    val serviceActive: Boolean = false,
    val serviceActiveDetail: String = "",
    val portListening: Boolean = false,
    val portListeningDetail: String = "",
    val sessionJsonOk: Boolean = false,
    val sessionJsonDetail: String = "",
    val restartSurvived: Boolean? = null,
    val restartSurvivedDetail: String = "",
) {
    val allPassed: Boolean
        get() = serviceActive && portListening && sessionJsonOk && (restartSurvived != false)
}

data class InstallStepItem(
    val id: String,
    val title: String,
    val description: String,
    val commandPreview: String,
    val status: InstallStepStatus = InstallStepStatus.PENDING,
    val detail: String? = null,
)

data class CustomModelEntry(
    val id: String,
    val displayName: String = id,
    val selected: Boolean = false,
)

data class ConfiguredModelInfo(
    val id: String,
    val name: String,
)

/**
 * Provider record parsed from ~/.config/opencode/opencode.json on the server.
 * Security invariant: raw API keys are NEVER held in this model — only a masked suffix (••••XXXX).
 */
data class ConfiguredProviderInfo(
    val providerId: String,
    val name: String,
    val baseUrl: String,
    val npm: String = "@ai-sdk/openai-compatible",
    val maskedApiKey: String? = null,
    val hasApiKey: Boolean = false,
    val models: List<ConfiguredModelInfo> = emptyList(),
    val isDefaultProvider: Boolean = false,
    val defaultModelId: String? = null,
)

data class ModelTestResult(
    val success: Boolean,
    val httpCode: Int? = null,
    val modelId: String,
    val message: String,
)

data class CustomProviderSaveRequest(
    val providerId: String,
    val providerName: String,
    val baseUrl: String,
    val apiKey: String?,
    val selectedModels: List<CustomModelEntry>,
    val defaultModelId: String,
    val replaceProviderModels: Boolean = false,
)

data class CustomProviderConfig(
    val providerId: String,
    val providerName: String,
    val baseUrl: String,
    val apiKey: String,
    val modelId: String,
    val modelDisplayName: String,
)

data class OpenCodeSetupUiState(
    val serverStatus: OpenCodeServerStatus = OpenCodeServerStatus.CHECKING,
    val wizardStage: SetupWizardStage = SetupWizardStage.IDLE,
    val envInfo: OpenCodeEnvInfo = OpenCodeEnvInfo(),
    val steps: List<InstallStepItem> = emptyList(),
    val failedStepIndex: Int? = null,
    val liveLog: String = "",
    val isRunningAction: Boolean = false,
    val errorMessage: String? = null,
    val enablePasswordAuth: Boolean = true,
    val verifyPersistenceOnRestart: Boolean = true,
    val forceUpdateBinary: Boolean = false,
    val verificationReport: OpenCodeVerificationReport? = null,
    val useCustomProvider: Boolean = false,
    // Connected providers list from ~/.config/opencode/opencode.json
    val configuredProviders: List<ConfiguredProviderInfo> = emptyList(),
    val isLoadingConfiguredProviders: Boolean = false,
    val configuredProvidersError: String? = null,
    val isProviderActionBusy: Boolean = false,
    // Editor state for creating/editing a custom OpenAI-compatible provider
    val editingProviderId: String? = null,
    val editingMaskedApiKey: String? = null,
    val providerName: String = "Custom OpenAI",
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKeyInput: String = "",
    val availableModels: List<CustomModelEntry> = emptyList(),
    val modelSearchQuery: String = "",
    val manualModelIdInput: String = "",
    val defaultSelectedModelId: String = "",
    val isFetchingModels: Boolean = false,
    val fetchModelsError: String? = null,
    val fetchModelsCount: Int? = null,
    val isTestingModel: Boolean = false,
    val modelTestResult: ModelTestResult? = null,
    val isSavingProvider: Boolean = false,
) {
    val selectedModels: List<CustomModelEntry>
        get() = availableModels.filter { it.selected }

    val effectiveTestModelId: String
        get() = defaultSelectedModelId.takeIf { id -> selectedModels.any { it.id == id } }
            ?: selectedModels.firstOrNull()?.id
            ?: manualModelIdInput.trim()
}
