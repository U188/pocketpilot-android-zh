package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.AppSettings
import com.example.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "opencode_preferences",
)

class AppPreferencesRepository(context: Context) {

    private val dataStore = context.applicationContext.appSettingsDataStore

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        val mode = prefs[KEY_THEME_MODE]
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.SYSTEM
        AppSettings(
            themeMode = mode,
            dynamicColor = prefs[KEY_DYNAMIC_COLOR] ?: false,
            lastActiveProfileId = prefs[KEY_LAST_ACTIVE_PROFILE_ID],
            editorFontSizeSp = (prefs[KEY_EDITOR_FONT_SIZE] ?: 13f).coerceIn(10f, 24f),
            editorWordWrap = prefs[KEY_EDITOR_WORD_WRAP] ?: true,
            showHiddenFiles = prefs[KEY_SHOW_HIDDEN_FILES] ?: false,
            defaultProviderId = prefs[KEY_DEFAULT_PROVIDER_ID].orEmpty(),
            defaultModelId = prefs[KEY_DEFAULT_MODEL_ID].orEmpty(),
            defaultAgentName = prefs[KEY_DEFAULT_AGENT_NAME]?.ifBlank { "build" } ?: "build",
            keepAliveInBackground = prefs[KEY_KEEP_ALIVE_BG] ?: true,
            biometricLockEnabled = prefs[KEY_BIOMETRIC_LOCK] ?: false,
            biometricLockTimeoutSec = (prefs[KEY_BIOMETRIC_TIMEOUT_SEC] ?: 60).coerceIn(0, 600),
            secureScreenEnabled = prefs[KEY_SECURE_SCREEN] ?: false,
            lastChatSessionId = prefs[KEY_LAST_CHAT_SESSION_ID],
            lastChatDraft = prefs[KEY_LAST_CHAT_DRAFT].orEmpty(),
            autoCheckUpdates = prefs[KEY_AUTO_CHECK_UPDATES] ?: true,
            lastUpdateCheckEpochMs = prefs[KEY_LAST_UPDATE_CHECK_MS] ?: 0L,
            activeWorkspaceByProfile = parseWorkspaceHistory(prefs[KEY_WORKSPACE_HISTORY_JSON]).first,
            recentWorkspacesByProfile = parseWorkspaceHistory(prefs[KEY_WORKSPACE_HISTORY_JSON]).second,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    suspend fun setLastActiveProfileId(profileId: Long?) {
        dataStore.edit { prefs ->
            if (profileId == null) {
                prefs.remove(KEY_LAST_ACTIVE_PROFILE_ID)
            } else {
                prefs[KEY_LAST_ACTIVE_PROFILE_ID] = profileId
            }
        }
    }

    suspend fun setEditorFontSizeSp(sizeSp: Float) {
        dataStore.edit { it[KEY_EDITOR_FONT_SIZE] = sizeSp.coerceIn(10f, 24f) }
    }

    suspend fun setEditorWordWrap(enabled: Boolean) {
        dataStore.edit { it[KEY_EDITOR_WORD_WRAP] = enabled }
    }

    suspend fun setShowHiddenFiles(show: Boolean) {
        dataStore.edit { it[KEY_SHOW_HIDDEN_FILES] = show }
    }

    suspend fun setDefaultModel(providerId: String, modelId: String) {
        dataStore.edit {
            it[KEY_DEFAULT_PROVIDER_ID] = providerId
            it[KEY_DEFAULT_MODEL_ID] = modelId
        }
    }

    suspend fun setDefaultAgent(agentName: String) {
        dataStore.edit {
            it[KEY_DEFAULT_AGENT_NAME] = agentName.ifBlank { "build" }
        }
    }

    suspend fun setKeepAliveInBackground(enabled: Boolean) {
        dataStore.edit { it[KEY_KEEP_ALIVE_BG] = enabled }
    }

    suspend fun setBiometricLockEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_BIOMETRIC_LOCK] = enabled }
    }

    suspend fun setBiometricLockTimeoutSec(seconds: Int) {
        dataStore.edit { it[KEY_BIOMETRIC_TIMEOUT_SEC] = seconds.coerceIn(0, 600) }
    }

    suspend fun setSecureScreenEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_SECURE_SCREEN] = enabled }
    }

    suspend fun setLastChatSessionId(sessionId: String?) {
        dataStore.edit { prefs ->
            if (sessionId.isNullOrBlank()) {
                prefs.remove(KEY_LAST_CHAT_SESSION_ID)
            } else {
                prefs[KEY_LAST_CHAT_SESSION_ID] = sessionId
            }
        }
    }

    suspend fun setLastChatDraft(draft: String) {
        dataStore.edit { it[KEY_LAST_CHAT_DRAFT] = draft }
    }

    suspend fun setAutoCheckUpdates(enabled: Boolean) {
        dataStore.edit { it[KEY_AUTO_CHECK_UPDATES] = enabled }
    }

    suspend fun setLastUpdateCheckEpochMs(epochMs: Long) {
        dataStore.edit { it[KEY_LAST_UPDATE_CHECK_MS] = epochMs.coerceAtLeast(0L) }
    }

    suspend fun setActiveWorkspace(profileId: Long, path: String) {
        val cleanPath = path.trim().ifEmpty { return }
        dataStore.edit { prefs ->
            val root = runCatching {
                JSONObject(prefs[KEY_WORKSPACE_HISTORY_JSON].orEmpty())
            }.getOrElse { JSONObject() }

            val key = profileId.toString()
            val entry = root.optJSONObject(key) ?: JSONObject()
            entry.put("active", cleanPath)

            val recentArr = entry.optJSONArray("recent") ?: JSONArray()
            val updatedRecent = buildList {
                add(cleanPath)
                for (i in 0 until recentArr.length()) {
                    val item = recentArr.optString(i).trim()
                    if (item.isNotEmpty() && item != cleanPath && size < MAX_RECENT_WORKSPACES) {
                        add(item)
                    }
                }
            }
            val newRecentArr = JSONArray()
            updatedRecent.forEach { newRecentArr.put(it) }
            entry.put("recent", newRecentArr)
            root.put(key, entry)
            prefs[KEY_WORKSPACE_HISTORY_JSON] = root.toString()
        }
    }

    private fun parseWorkspaceHistory(raw: String?): Pair<Map<Long, String>, Map<Long, List<String>>> {
        if (raw.isNullOrBlank()) return emptyMap<Long, String>() to emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            val activeMap = LinkedHashMap<Long, String>()
            val recentMap = LinkedHashMap<Long, List<String>>()
            val iter = root.keys()
            while (iter.hasNext()) {
                val key = iter.next()
                val profileId = key.toLongOrNull() ?: continue
                val obj = root.optJSONObject(key) ?: continue
                val active = obj.optString("active").trim()
                if (active.isNotEmpty()) {
                    activeMap[profileId] = active
                }
                val arr = obj.optJSONArray("recent")
                if (arr != null) {
                    val list = buildList {
                        for (i in 0 until arr.length()) {
                            val item = arr.optString(i).trim()
                            if (item.isNotEmpty() && item !in this) add(item)
                        }
                    }
                    if (list.isNotEmpty()) {
                        recentMap[profileId] = list
                    }
                }
            }
            activeMap to recentMap
        }.getOrElse { emptyMap<Long, String>() to emptyMap() }
    }

    companion object {
        private const val MAX_RECENT_WORKSPACES = 8
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val KEY_LAST_ACTIVE_PROFILE_ID = longPreferencesKey("last_active_profile_id")
        private val KEY_EDITOR_FONT_SIZE = floatPreferencesKey("editor_font_size")
        private val KEY_EDITOR_WORD_WRAP = booleanPreferencesKey("editor_word_wrap")
        private val KEY_SHOW_HIDDEN_FILES = booleanPreferencesKey("show_hidden_files")
        private val KEY_DEFAULT_PROVIDER_ID = stringPreferencesKey("default_provider_id")
        private val KEY_DEFAULT_MODEL_ID = stringPreferencesKey("default_model_id")
        private val KEY_DEFAULT_AGENT_NAME = stringPreferencesKey("default_agent_name")
        private val KEY_KEEP_ALIVE_BG = booleanPreferencesKey("keep_alive_bg")
        private val KEY_BIOMETRIC_LOCK = booleanPreferencesKey("biometric_lock")
        private val KEY_BIOMETRIC_TIMEOUT_SEC = intPreferencesKey("biometric_timeout_sec")
        private val KEY_SECURE_SCREEN = booleanPreferencesKey("secure_screen")
        private val KEY_LAST_CHAT_SESSION_ID = stringPreferencesKey("last_chat_session_id")
        private val KEY_LAST_CHAT_DRAFT = stringPreferencesKey("last_chat_draft")
        private val KEY_AUTO_CHECK_UPDATES = booleanPreferencesKey("auto_check_updates")
        private val KEY_LAST_UPDATE_CHECK_MS = longPreferencesKey("last_update_check_ms")
        private val KEY_WORKSPACE_HISTORY_JSON = stringPreferencesKey("workspace_history_json")
    }
}
