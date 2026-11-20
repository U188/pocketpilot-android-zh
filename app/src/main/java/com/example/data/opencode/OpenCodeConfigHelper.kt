package com.example.data.opencode

import com.example.domain.model.ConfiguredModelInfo
import com.example.domain.model.ConfiguredProviderInfo
import com.example.domain.model.CustomModelEntry
import com.example.domain.model.CustomProviderConfig
import com.example.domain.model.CustomProviderSaveRequest
import com.example.domain.model.InstallStepItem
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

object OpenCodeConfigHelper {

    const val STEP_CHECK_ENV = "check_env"
    const val STEP_INSTALL = "install_opencode"
    const val STEP_MIGRATE = "migrate_legacy"
    const val STEP_SYSTEMD = "configure_systemd"
    const val STEP_VERIFY = "verify_server"

    const val DEFAULT_AUTH_USERNAME = "opencode"
    const val SERVER_UNREACHABLE_MESSAGE = "Сервер не имеет доступа к интернету или адресу"

    private const val PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    private val secureRandom = SecureRandom()

    fun generateStrongPassword(length: Int = 28): String {
        val sb = StringBuilder(length)
        repeat(length) {
            sb.append(PASSWORD_ALPHABET[secureRandom.nextInt(PASSWORD_ALPHABET.length)])
        }
        return sb.toString()
    }

    fun buildDefaultSteps(
        port: Int,
        alreadyInstalled: Boolean = false,
        forceUpdateBinary: Boolean = false,
        enablePasswordAuth: Boolean = true,
        verifyPersistence: Boolean = true,
    ): List<InstallStepItem> {
        val installTitle = when {
            alreadyInstalled && forceUpdateBinary -> "2. Обновление OpenCode"
            alreadyInstalled -> "2. Проверка бинарника OpenCode"
            else -> "2. Установка OpenCode"
        }
        val installDesc = when {
            alreadyInstalled && forceUpdateBinary ->
                "Обновление существующего бинарника и определение абсолютного пути"
            alreadyInstalled ->
                "OpenCode уже установлен — повторное скачивание не требуется"
            else ->
                "Скачивание официального бинарника и определение абсолютного пути (\$HOME/.opencode/bin/opencode)"
        }
        val installPreview = if (alreadyInstalled && !forceUpdateBinary) {
            "test -x \"\$HOME/.opencode/bin/opencode\" || bash -lc 'command -v opencode'"
        } else {
            "bash -lc 'curl -fsSL https://opencode.ai/install | bash' && resolve \$HOME/.opencode/bin/opencode"
        }

        val unitDesc = if (enablePasswordAuth) {
            "Загрузка постоянного unit-файла и /etc/opencode-serve.env (chmod 600, Basic Auth) по SFTP, daemon-reload, enable --now (только 127.0.0.1:$port)"
        } else {
            "Загрузка постоянного unit-файла по SFTP, daemon-reload, enable --now (только 127.0.0.1:$port)"
        }

        val verifyDesc = if (verifyPersistence) {
            "Проверка active, ss -ltn (127.0.0.1:$port), GET /session (JSON) + тест systemctl restart"
        } else {
            "Проверка active, ss -ltn (127.0.0.1:$port) и ответа GET /session (JSON)"
        }

        val verifyCmd = if (verifyPersistence) {
            "systemctl is-active opencode-serve && ss -ltn | grep 127.0.0.1:$port && GET /session && systemctl restart opencode-serve"
        } else {
            "systemctl is-active opencode-serve && ss -ltn | grep 127.0.0.1:$port && GET /session"
        }

        return listOf(
            InstallStepItem(
                id = STEP_CHECK_ENV,
                title = "1. Проверка окружения",
                description = "ОС, архитектура, curl, systemd, права root/sudo и свободное место на диске",
                commandPreview = "uname -s -m; command -v curl systemctl sudo; df -Pm \"\$HOME\"",
            ),
            InstallStepItem(
                id = STEP_INSTALL,
                title = installTitle,
                description = installDesc,
                commandPreview = installPreview,
            ),
            InstallStepItem(
                id = STEP_MIGRATE,
                title = "3. Миграция старого запуска",
                description = "Остановка временных (transient) служб и сиротских процессов opencode serve для освобождения порта $port",
                commandPreview = "systemctl stop opencode-serve; systemctl reset-failed opencode-serve; pkill -f 'opencode serve'",
            ),
            InstallStepItem(
                id = STEP_SYSTEMD,
                title = "4. Постоянный systemd-сервис (127.0.0.1:$port)",
                description = unitDesc,
                commandPreview = "SFTP → opencode-serve.service (--hostname 127.0.0.1 --port $port) && systemctl daemon-reload && systemctl enable --now opencode-serve",
            ),
            InstallStepItem(
                id = STEP_VERIFY,
                title = "5. Автопроверка и устойчивость",
                description = verifyDesc,
                commandPreview = verifyCmd,
            ),
        )
    }

    fun buildSystemdUnit(
        opencodeBinaryPath: String,
        userName: String,
        homeDir: String,
        port: Int,
        isUserService: Boolean,
        envFilePath: String? = null,
    ): String {
        val binDir = opencodeBinaryPath.substringBeforeLast('/', "/usr/local/bin")
        val cleanHome = homeDir.ifBlank { "/root" }
        val cleanPort = port.coerceIn(1, 65535)
        return buildString {
            appendLine("[Unit]")
            appendLine("Description=OpenCode Local Server")
            appendLine("After=network-online.target")
            appendLine("Wants=network-online.target")
            appendLine()
            appendLine("[Service]")
            appendLine("Type=simple")
            if (!isUserService && userName.isNotBlank()) {
                appendLine("User=$userName")
            }
            appendLine("WorkingDirectory=$cleanHome")
            appendLine("Environment=HOME=$cleanHome")
            appendLine("Environment=PATH=$binDir:$cleanHome/.opencode/bin:$cleanHome/.local/bin:/usr/local/bin:/usr/bin:/bin")
            if (!envFilePath.isNullOrBlank()) {
                appendLine("EnvironmentFile=$envFilePath")
            }
            appendLine("ExecStart=$opencodeBinaryPath serve --hostname 127.0.0.1 --port $cleanPort")
            appendLine("Restart=always")
            appendLine("RestartSec=3")
            appendLine()
            appendLine("[Install]")
            appendLine(if (isUserService) "WantedBy=default.target" else "WantedBy=multi-user.target")
        }
    }

    fun buildEnvFileContent(
        username: String = DEFAULT_AUTH_USERNAME,
        password: String,
    ): String = buildString {
        appendLine("OPENCODE_SERVER_USERNAME=${username.ifBlank { DEFAULT_AUTH_USERNAME }}")
        appendLine("OPENCODE_SERVER_PASSWORD=$password")
    }

    /**
     * Masks an API key for display: shows only "••••" + last 4 characters.
     */
    fun maskApiKey(rawKey: String?): String? {
        val trimmed = rawKey?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return if (trimmed.length <= 4) {
            "••••"
        } else {
            "••••${trimmed.takeLast(4)}"
        }
    }

    /**
     * Parses configured providers from ~/.config/opencode/opencode.json without ever exposing raw API keys.
     */
    fun parseConfiguredProviders(existingJson: String?): List<ConfiguredProviderInfo> {
        val root = parseConfigRootOrNull(existingJson) ?: return emptyList()
        val defaultModelSpec = root.optString("model", "").trim()
        val defaultProviderId = defaultModelSpec.substringBefore('/', "").takeIf { defaultModelSpec.contains('/') }
        val defaultModelId = defaultModelSpec.substringAfter('/', "").takeIf { defaultModelSpec.contains('/') }

        val providersObj = root.optJSONObject("provider") ?: return emptyList()
        val result = mutableListOf<ConfiguredProviderInfo>()
        val keys = providersObj.keys()
        while (keys.hasNext()) {
            val providerId = keys.next()
            val pObj = providersObj.optJSONObject(providerId) ?: continue
            val name = pObj.optString("name").ifBlank { providerId }
            val npm = pObj.optString("npm").ifBlank { "@ai-sdk/openai-compatible" }
            val options = pObj.optJSONObject("options")
            val baseUrl = options?.optString("baseURL").orEmpty()
                .ifBlank { options?.optString("baseUrl").orEmpty() }
            val rawKey = options?.optString("apiKey").orEmpty()
            val hasApiKey = rawKey.isNotBlank()
            val masked = maskApiKey(rawKey)

            val models = mutableListOf<ConfiguredModelInfo>()
            val modelsObj = pObj.optJSONObject("models")
            if (modelsObj != null) {
                val mKeys = modelsObj.keys()
                while (mKeys.hasNext()) {
                    val mId = mKeys.next()
                    val mObj = modelsObj.optJSONObject(mId)
                    val mName = mObj?.optString("name")?.takeIf { it.isNotBlank() } ?: mId
                    val costObj = mObj?.optJSONObject("cost")
                    val inputCost = costObj?.optDouble("input", Double.NaN)
                        ?.takeIf { !it.isNaN() && !it.isInfinite() && it >= 0.0 }
                    val outputCost = costObj?.optDouble("output", Double.NaN)
                        ?.takeIf { !it.isNaN() && !it.isInfinite() && it >= 0.0 }
                    val contextLimit = mObj?.optJSONObject("limit")
                        ?.optLong("context", 0L)
                        ?.takeIf { it > 0L }
                    models.add(
                        ConfiguredModelInfo(
                            id = mId,
                            name = mName,
                            inputPricePer1M = inputCost,
                            outputPricePer1M = outputCost,
                            contextLimit = contextLimit,
                        ),
                    )
                }
            }

            result.add(
                ConfiguredProviderInfo(
                    providerId = providerId,
                    name = name,
                    baseUrl = baseUrl,
                    npm = npm,
                    maskedApiKey = masked,
                    hasApiKey = hasApiKey,
                    models = models.sortedBy { it.id.lowercase() },
                    isDefaultProvider = providerId == defaultProviderId,
                    defaultModelId = if (providerId == defaultProviderId) defaultModelId else null,
                ),
            )
        }
        return result.sortedWith(
            compareByDescending<ConfiguredProviderInfo> { it.isDefaultProvider }
                .thenBy { it.name.lowercase() },
        )
    }

    /**
     * Reads a provider's raw apiKey transiently on IO thread for server-side curl verification
     * when editing an existing provider without re-entering the key. Never store or log the return value.
     */
    fun extractRawApiKeyForProvider(existingJson: String?, providerId: String): String? {
        val root = parseConfigRootOrNull(existingJson) ?: return null
        val pObj = root.optJSONObject("provider")?.optJSONObject(providerId) ?: return null
        return pObj.optJSONObject("options")?.optString("apiKey")?.trim()?.takeIf { it.isNotBlank() }
    }

    /**
     * Backward-compatible single-model merge helper.
     */
    fun mergeOpenAiCompatibleProvider(
        existingJson: String?,
        config: CustomProviderConfig,
    ): String {
        val modelId = config.modelId.trim()
        val display = config.modelDisplayName.trim().ifBlank { modelId }
        return mergeOpenAiCompatibleProvider(
            existingJson = existingJson,
            request = CustomProviderSaveRequest(
                providerId = config.providerId,
                providerName = config.providerName,
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                selectedModels = listOf(CustomModelEntry(id = modelId, displayName = display, selected = true)),
                defaultModelId = modelId,
                replaceProviderModels = false,
            ),
        )
    }

    /**
     * Safely merges an OpenAI-compatible provider with 1..N selected models into ~/.config/opencode/opencode.json
     * without overwriting unrelated root keys, other providers, or extra provider/model options.
     */
    fun mergeOpenAiCompatibleProvider(
        existingJson: String?,
        request: CustomProviderSaveRequest,
    ): String {
        val root = parseConfigRootOrNull(existingJson) ?: JSONObject()

        if (!root.has("\$schema")) {
            root.put("\$schema", "https://opencode.ai/config.json")
        }

        val providerId = sanitizeProviderId(request.providerId.ifBlank { request.providerName })
        val normalizedBaseUrl = normalizeBaseUrl(request.baseUrl)
        val displayProviderName = request.providerName.trim().ifBlank { providerId }
        val cleanModels = request.selectedModels
            .mapNotNull { entry ->
                val id = entry.id.trim()
                if (id.isBlank()) null
                else entry.copy(id = id, displayName = entry.displayName.trim().ifBlank { id })
            }
            .distinctBy { it.id }

        require(cleanModels.isNotEmpty()) { "Выберите хотя бы одну модель" }

        val providersObj = root.optJSONObject("provider") ?: JSONObject().also {
            root.put("provider", it)
        }
        val providerObj = providersObj.optJSONObject(providerId) ?: JSONObject().also {
            providersObj.put(providerId, it)
        }

        providerObj.put("npm", "@ai-sdk/openai-compatible")
        providerObj.put("name", displayProviderName)

        val optionsObj = providerObj.optJSONObject("options") ?: JSONObject().also {
            providerObj.put("options", it)
        }
        optionsObj.put("baseURL", normalizedBaseUrl)
        val incomingKey = request.apiKey?.trim().orEmpty()
        if (incomingKey.isNotEmpty()) {
            optionsObj.put("apiKey", incomingKey)
        }

        val previousModelsObj = providerObj.optJSONObject("models")
        val targetModelsObj = if (request.replaceProviderModels) {
            JSONObject()
        } else {
            previousModelsObj ?: JSONObject()
        }

        cleanModels.forEach { entry ->
            val modelObj = previousModelsObj?.optJSONObject(entry.id) ?: JSONObject()
            modelObj.put("name", entry.displayName)
            val inPrice = entry.parsedInputPricePer1M
            val outPrice = entry.parsedOutputPricePer1M
            if (inPrice != null || outPrice != null) {
                val costObj = modelObj.optJSONObject("cost") ?: JSONObject()
                if (inPrice != null) costObj.put("input", inPrice)
                if (outPrice != null) costObj.put("output", outPrice)
                modelObj.put("cost", costObj)
            }
            targetModelsObj.put(entry.id, modelObj)
        }
        providerObj.put("models", targetModelsObj)

        val effectiveDefaultModelId = request.defaultModelId.trim()
            .takeIf { id -> cleanModels.any { it.id == id } }
            ?: cleanModels.first().id

        root.put("model", "$providerId/$effectiveDefaultModelId")
        return root.toString(2) + "\n"
    }

    /**
     * Updates only the apiKey of an existing provider in ~/.config/opencode/opencode.json
     * without touching any other fields or providers.
     */
    fun updateProviderApiKey(
        existingJson: String,
        providerId: String,
        newApiKey: String,
    ): String {
        val trimmedKey = newApiKey.trim()
        require(trimmedKey.isNotEmpty()) { "Введите новый API-ключ" }
        val root = parseConfigRootOrNull(existingJson)
            ?: throw IllegalStateException("Не удалось прочитать существующий opencode.json")
        val providersObj = root.optJSONObject("provider")
            ?: throw IllegalStateException("Секция provider не найдена в opencode.json")
        val providerObj = providersObj.optJSONObject(providerId)
            ?: throw IllegalStateException("Провайдер $providerId не найден в opencode.json")
        val optionsObj = providerObj.optJSONObject("options") ?: JSONObject().also {
            providerObj.put("options", it)
        }
        optionsObj.put("apiKey", trimmedKey)
        return root.toString(2) + "\n"
    }

    /**
     * Removes a single provider from ~/.config/opencode/opencode.json without disturbing
     * unrelated root keys or other providers. Clears root "model" only if it pointed to the removed provider.
     */
    fun removeProvider(
        existingJson: String,
        providerId: String,
    ): String {
        val root = parseConfigRootOrNull(existingJson)
            ?: throw IllegalStateException("Не удалось прочитать существующий opencode.json")
        val providersObj = root.optJSONObject("provider")
        providersObj?.remove(providerId)

        val currentModel = root.optString("model", "").trim()
        if (currentModel.startsWith("$providerId/")) {
            root.remove("model")
        }
        return root.toString(2) + "\n"
    }

    /**
     * Parses OpenAI-compatible GET {baseURL}/models JSON payload.
     * Per specification: model display name defaults to its ID (and can be edited by the user).
     */
    fun parseOpenAiModelsResponse(jsonBody: String): List<CustomModelEntry> {
        val trimmed = jsonBody.trim()
        if (trimmed.isEmpty()) return emptyList()

        val ids = LinkedHashSet<String>()
        val collectArray: (JSONArray?) -> Unit = { arr ->
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    when (val item = arr.opt(i)) {
                        is JSONObject -> {
                            val id = item.optString("id").trim()
                                .ifBlank { item.optString("name").trim() }
                            if (id.isNotBlank()) ids.add(id)
                        }

                        is String -> {
                            val id = item.trim()
                            if (id.isNotBlank()) ids.add(id)
                        }
                    }
                }
            }
        }

        if (trimmed.startsWith("[")) {
            collectArray(JSONArray(trimmed))
        } else {
            val root = JSONObject(trimmed)
            collectArray(root.optJSONArray("data"))
            if (ids.isEmpty()) {
                collectArray(root.optJSONArray("models"))
            }
        }

        return ids
            .sortedBy { it.lowercase() }
            .map { id -> CustomModelEntry(id = id, displayName = id, selected = false) }
    }

    /**
     * Extracts assistant reply text from POST {baseURL}/chat/completions JSON response.
     */
    fun parseChatCompletionReply(jsonBody: String): String {
        val root = JSONObject(jsonBody.trim())
        val choices = root.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val first = choices.optJSONObject(0)
            if (first != null) {
                val message = first.optJSONObject("message")
                if (message != null) {
                    val contentAny = message.opt("content")
                    when (contentAny) {
                        is String -> if (contentAny.isNotBlank()) return contentAny.trim()
                        is JSONArray -> {
                            val joined = buildString {
                                for (i in 0 until contentAny.length()) {
                                    val part = contentAny.optJSONObject(i)
                                    val txt = part?.optString("text").orEmpty()
                                    if (txt.isNotBlank()) append(txt)
                                }
                            }.trim()
                            if (joined.isNotBlank()) return joined
                        }
                    }
                    val reasoning = message.optString("reasoning_content").trim()
                    if (reasoning.isNotBlank()) return reasoning
                }
                val text = first.optString("text").trim()
                if (text.isNotBlank()) return text
            }
        }
        return jsonBody.trim().take(240)
    }

    /**
     * Safely strips // and /* */ JSONC comments outside string literals so hand-edited configs parse cleanly.
     */
    fun stripJsonComments(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        var inString = false
        var escaped = false
        while (i < raw.length) {
            val c = raw[i]
            if (inString) {
                out.append(c)
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                i++
            } else {
                if (c == '"') {
                    inString = true
                    out.append(c)
                    i++
                } else if (c == '/' && i + 1 < raw.length && raw[i + 1] == '/') {
                    i += 2
                    while (i < raw.length && raw[i] != '\n') i++
                } else if (c == '/' && i + 1 < raw.length && raw[i + 1] == '*') {
                    i += 2
                    while (i + 1 < raw.length && !(raw[i] == '*' && raw[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(raw.length)
                } else {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    fun escapeCurlConfigValue(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun parseConfigRootOrNull(existingJson: String?): JSONObject? {
        if (existingJson.isNullOrBlank()) return null
        return runCatching { JSONObject(existingJson) }.getOrNull()
            ?: runCatching { JSONObject(stripJsonComments(existingJson)) }.getOrNull()
    }

    fun normalizeBaseUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim().trimEnd('/')
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }
        return "https://$trimmed"
    }

    fun sanitizeProviderId(raw: String): String {
        val slug = raw.trim().lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "-")
            .trim('-', '_')
        return slug.ifBlank { "custom-openai" }
    }

    fun sanitizeLogs(raw: String, explicitSecrets: List<String> = emptyList()): String {
        var result = raw
        explicitSecrets
            .filter { it.length >= 4 }
            .forEach { secret ->
                result = result.replace(secret, "[REDACTED]")
            }
        result = BEARER_REGEX.replace(result, "Bearer [REDACTED]")
        result = BASIC_AUTH_REGEX.replace(result, "Basic [REDACTED]")
        result = ENV_PASSWORD_REGEX.replace(result, "OPENCODE_SERVER_PASSWORD=[REDACTED]")
        result = API_KEY_JSON_REGEX.replace(result, "\"apiKey\": \"[REDACTED]\"")
        result = SK_TOKEN_REGEX.replace(result, "[REDACTED_KEY]")
        return result
    }

    private val BEARER_REGEX = Regex("Bearer\\s+[A-Za-z0-9._~+/=-]{8,}", RegexOption.IGNORE_CASE)
    private val BASIC_AUTH_REGEX = Regex("Basic\\s+[A-Za-z0-9+/=]{8,}", RegexOption.IGNORE_CASE)
    private val ENV_PASSWORD_REGEX = Regex("OPENCODE_SERVER_PASSWORD\\s*=\\s*[^\\s\"']+", RegexOption.IGNORE_CASE)
    private val API_KEY_JSON_REGEX = Regex("\"apiKey\"\\s*:\\s*\"[^\"]+\"", RegexOption.IGNORE_CASE)
    private val SK_TOKEN_REGEX = Regex("\\b(sk-[A-Za-z0-9_-]{12,}|gsk_[A-Za-z0-9_-]{12,}|or-[A-Za-z0-9_-]{12,})\\b")
}
