package com.example.domain.model

enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT,
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val lastActiveProfileId: Long? = null,
    val editorFontSizeSp: Float = 13f,
    val editorWordWrap: Boolean = true,
    val showHiddenFiles: Boolean = false,
    val defaultProviderId: String = "",
    val defaultModelId: String = "",
    val defaultAgentName: String = "build",
    val keepAliveInBackground: Boolean = true,
    val biometricLockEnabled: Boolean = false,
    val biometricLockTimeoutSec: Int = 60,
    val secureScreenEnabled: Boolean = false,
    val lastChatSessionId: String? = null,
    val lastChatDraft: String = "",
    val autoCheckUpdates: Boolean = true,
    val lastUpdateCheckEpochMs: Long = 0L,
)
