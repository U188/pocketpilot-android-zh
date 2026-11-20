package com.example.data.opencode

import com.example.data.sftp.SftpRepository
import com.example.data.ssh.SshConnectionManager
import com.example.domain.model.ConfiguredProviderInfo
import com.example.domain.model.CustomModelEntry
import com.example.domain.model.CustomProviderSaveRequest
import com.example.domain.model.InstallStepStatus
import com.example.domain.model.ModelTestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class ServerUnreachableException(
    message: String = OpenCodeConfigHelper.SERVER_UNREACHABLE_MESSAGE,
) : IllegalStateException(message)

sealed interface ProviderApplyOutcome {
    data class Success(
        val providerId: String? = null,
        val defaultModelId: String? = null,
        val tunnelPort: Int? = null,
        val detail: String = "",
    ) : ProviderApplyOutcome

    /**
     * opencode.json was written and opencode-serve is `active`, so the config is NOT rolled back.
     * The post-restart /session check did not pass within 40s — show warning with "Повторить" / "Продолжить".
     */
    data class SavedWithCheckWarning(
        val providerId: String? = null,
        val defaultModelId: String? = null,
        val tunnelPort: Int? = null,
        val warningMessage: String,
    ) : ProviderApplyOutcome

    /**
     * opencode-serve failed to become `active` after restart — opencode.json was rolled back to backup.
     */
    data class RolledBackServiceInactive(
        val errorMessage: String,
    ) : ProviderApplyOutcome
}

private data class RemoteCurlResponse(
    val exitCode: Int,
    val httpCode: Int,
    val body: String,
    val stderr: String,
)

class OpenCodeProviderManager(
    private val connectionManager: SshConnectionManager,
    private val sftpRepository: SftpRepository,
    private val serverInstaller: OpenCodeServerInstaller,
) {

    /**
     * Reads configured providers from ~/.config/opencode/opencode.json on the server.
     * Raw API keys are immediately masked to "••••XXXX" and never returned to UI or persisted locally.
     */
    suspend fun loadConfiguredProviders(openCodePort: Int): List<ConfiguredProviderInfo> =
        withContext(Dispatchers.IO) {
            val configPath = resolveConfigPath(openCodePort)
            val existing = runCatching { sftpRepository.readTextFile(configPath).text }.getOrNull()
            OpenCodeConfigHelper.parseConfiguredProviders(existing)
        }

    /**
     * Executes GET {baseURL}/models from the remote server via SSH curl (-K - stdin so the API key
     * never appears in process args, shell history, or logs).
     */
    suspend fun fetchRemoteModels(
        baseUrl: String,
        apiKeyInput: String,
        existingProviderId: String?,
        openCodePort: Int,
    ): List<CustomModelEntry> = withContext(Dispatchers.IO) {
        val normalizedBase = OpenCodeConfigHelper.normalizeBaseUrl(baseUrl)
        val effectiveKey = resolveEffectiveKeyTransient(apiKeyInput, existingProviderId, openCodePort)
        val response = executeRemoteCurl(
            method = "GET",
            url = "$normalizedBase/models",
            apiKey = effectiveKey,
            jsonBody = null,
            connectTimeoutSec = 10,
            maxTimeSec = 20,
        )
        throwIfServerUnreachable(response, effectiveKey)

        if (response.httpCode !in 200..299) {
            val cleanBody = OpenCodeConfigHelper.sanitizeLogs(
                response.body.trim().take(500),
                listOfNotNull(effectiveKey),
            )
            throw IllegalStateException(
                buildString {
                    append("HTTP ${response.httpCode}")
                    if (cleanBody.isNotBlank()) {
                        append("\n")
                        append(cleanBody)
                    }
                },
            )
        }

        val models = runCatching {
            OpenCodeConfigHelper.parseOpenAiModelsResponse(response.body)
        }.getOrElse { err ->
            throw IllegalStateException("Некорректный JSON от /models: ${err.message ?: "ошибка разбора"}")
        }
        if (models.isEmpty()) {
            throw IllegalStateException("Провайдер ответил HTTP ${response.httpCode}, но список моделей пуст")
        }
        models
    }

    /**
     * Sends a live test prompt ("ответь одним словом") via POST {baseURL}/chat/completions
     * executed from the remote server, returning either the model's reply or the HTTP code + error body.
     */
    suspend fun testModelCompletion(
        baseUrl: String,
        apiKeyInput: String,
        existingProviderId: String?,
        modelId: String,
        openCodePort: Int,
    ): ModelTestResult = withContext(Dispatchers.IO) {
        val cleanModel = modelId.trim()
        require(cleanModel.isNotEmpty()) { "Выберите модель для проверки" }

        val normalizedBase = OpenCodeConfigHelper.normalizeBaseUrl(baseUrl)
        val effectiveKey = resolveEffectiveKeyTransient(apiKeyInput, existingProviderId, openCodePort)

        val payload = JSONObject().apply {
            put("model", cleanModel)
            put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", "ответь одним словом"),
                ),
            )
            put("max_tokens", 32)
        }.toString()

        val response = executeRemoteCurl(
            method = "POST",
            url = "$normalizedBase/chat/completions",
            apiKey = effectiveKey,
            jsonBody = payload,
            connectTimeoutSec = 10,
            maxTimeSec = 30,
        )

        if (isUnreachableResponse(response)) {
            return@withContext ModelTestResult(
                success = false,
                httpCode = null,
                modelId = cleanModel,
                message = OpenCodeConfigHelper.SERVER_UNREACHABLE_MESSAGE,
            )
        }

        if (response.exitCode == 127) {
            return@withContext ModelTestResult(
                success = false,
                httpCode = null,
                modelId = cleanModel,
                message = "На сервере не установлен curl",
            )
        }

        if (response.exitCode == 35 || response.exitCode == 60) {
            return@withContext ModelTestResult(
                success = false,
                httpCode = null,
                modelId = cleanModel,
                message = "Ошибка TLS/SSL при подключении сервера к адресу (код curl ${response.exitCode})",
            )
        }

        val cleanBody = OpenCodeConfigHelper.sanitizeLogs(
            response.body.trim(),
            listOfNotNull(effectiveKey),
        )

        if (response.httpCode in 200..299) {
            val reply = runCatching {
                OpenCodeConfigHelper.parseChatCompletionReply(cleanBody)
            }.getOrElse { cleanBody.take(240) }.ifBlank { "OK (пустой ответ)" }

            ModelTestResult(
                success = true,
                httpCode = response.httpCode,
                modelId = cleanModel,
                message = reply.take(300),
            )
        } else {
            val errorDetail = cleanBody.take(600)
                .ifBlank { OpenCodeConfigHelper.sanitizeLogs(response.stderr.trim().take(300), listOfNotNull(effectiveKey)) }
                .ifBlank { "Пустой ответ сервера" }
            ModelTestResult(
                success = false,
                httpCode = response.httpCode.takeIf { it > 0 },
                modelId = cleanModel,
                message = errorDetail,
            )
        }
    }

    /**
     * Checks from the remote server that baseURL is reachable over the network (no timeout/DNS/connect failure).
     * Throws ServerUnreachableException("Сервер не имеет доступа к интернету или адресу") if unreachable.
     */
    suspend fun verifyServerReachable(
        baseUrl: String,
        apiKey: String?,
    ) = withContext(Dispatchers.IO) {
        val normalizedBase = OpenCodeConfigHelper.normalizeBaseUrl(baseUrl)
        val response = executeRemoteCurl(
            method = "GET",
            url = "$normalizedBase/models",
            apiKey = apiKey,
            jsonBody = null,
            connectTimeoutSec = 8,
            maxTimeSec = 12,
        )
        throwIfServerUnreachable(response, apiKey)
    }

    /**
     * 1) Verifies the remote server can reach baseURL (aborts WITHOUT saving on timeout/unreachability).
     * 2) Safely merges the provider and selected models into ~/.config/opencode/opencode.json (chmod 600).
     * 3) Restarts opencode-serve; ONLY rolls back opencode.json to backup if systemd service fails to become active.
     * 4) Waits up to 40s (every 2s) for /session with Basic Auth (401 = alive) and SSH tunnel rebuild on drop.
     *    If health check fails while service IS active, keeps opencode.json and returns SavedWithCheckWarning.
     */
    suspend fun saveProviderAndRestart(
        profileId: Long?,
        request: CustomProviderSaveRequest,
        openCodePort: Int,
        localTunnelPort: Int?,
        onStepUpdate: (stepIndex: Int, status: InstallStepStatus, detail: String?) -> Unit = { _, _, _ -> },
    ): ProviderApplyOutcome = withContext(Dispatchers.IO) {
        val effectiveKey = resolveEffectiveKeyTransient(
            apiKeyInput = request.apiKey.orEmpty(),
            existingProviderId = request.providerId.takeIf { it.isNotBlank() },
            openCodePort = openCodePort,
        )

        onStepUpdate(0, InstallStepStatus.RUNNING, "Проверка доступности адреса с сервера…")
        // Verify reachability from server BEFORE modifying opencode.json
        verifyServerReachable(request.baseUrl, effectiveKey)

        val homeDir = resolveHomeDir(openCodePort)
        val configDir = "$homeDir/.config/opencode"
        val configPath = "$configDir/opencode.json"

        ensureConfigDir(configDir)

        val previousConfig = runCatching {
            sftpRepository.readTextFile(configPath).text
        }.getOrNull()

        val merged = OpenCodeConfigHelper.mergeOpenAiCompatibleProvider(previousConfig, request)

        onStepUpdate(0, InstallStepStatus.RUNNING, "Запись ~/.config/opencode/opencode.json по SFTP…")
        // Write exclusively via SFTP so API key never touches shell commands or logs
        sftpRepository.writeTextFile(
            path = configPath,
            text = merged,
            expectedMtimeEpochSec = null,
            forceOverwrite = true,
        )
        runCatching { sftpRepository.chmod(configPath, 0x180) }
        onStepUpdate(0, InstallStepStatus.SUCCESS, "opencode.json записан (chmod 600)")

        val resolvedProviderId = OpenCodeConfigHelper.sanitizeProviderId(
            request.providerId.ifBlank { request.providerName },
        )
        val resolvedDefaultModel = request.defaultModelId.trim()
            .takeIf { id -> request.selectedModels.any { it.id.trim() == id } }
            ?: request.selectedModels.first().id.trim()

        restartAndWaitWithSelectiveRollback(
            profileId = profileId,
            openCodePort = openCodePort,
            localTunnelPort = localTunnelPort,
            configPath = configPath,
            previousConfig = previousConfig,
            providerId = resolvedProviderId,
            defaultModelId = resolvedDefaultModel,
            onStepUpdate = onStepUpdate,
        )
    }

    /**
     * Rotates ONLY the API key for an existing provider in ~/.config/opencode/opencode.json (chmod 600),
     * verifies server reachability first, then restarts opencode-serve with selective rollback and 40s wait.
     */
    suspend fun updateProviderKeyAndRestart(
        profileId: Long?,
        providerId: String,
        baseUrl: String,
        newApiKey: String,
        openCodePort: Int,
        localTunnelPort: Int?,
        onStepUpdate: (stepIndex: Int, status: InstallStepStatus, detail: String?) -> Unit = { _, _, _ -> },
    ): ProviderApplyOutcome = withContext(Dispatchers.IO) {
        val cleanKey = newApiKey.trim()
        require(cleanKey.isNotEmpty()) { "Введите новый API-ключ" }

        onStepUpdate(0, InstallStepStatus.RUNNING, "Проверка доступа с новым ключом…")
        if (baseUrl.isNotBlank()) {
            verifyServerReachable(baseUrl, cleanKey)
        }

        val configPath = resolveConfigPath(openCodePort)
        val previousConfig = sftpRepository.readTextFile(configPath).text
        val updated = OpenCodeConfigHelper.updateProviderApiKey(previousConfig, providerId, cleanKey)

        onStepUpdate(0, InstallStepStatus.RUNNING, "Обновление ключа в opencode.json по SFTP…")
        sftpRepository.writeTextFile(
            path = configPath,
            text = updated,
            expectedMtimeEpochSec = null,
            forceOverwrite = true,
        )
        runCatching { sftpRepository.chmod(configPath, 0x180) }
        onStepUpdate(0, InstallStepStatus.SUCCESS, "Ключ записан в opencode.json (chmod 600)")

        restartAndWaitWithSelectiveRollback(
            profileId = profileId,
            openCodePort = openCodePort,
            localTunnelPort = localTunnelPort,
            configPath = configPath,
            previousConfig = previousConfig,
            providerId = providerId,
            defaultModelId = null,
            onStepUpdate = onStepUpdate,
        )
    }

    /**
     * Removes a single provider from ~/.config/opencode/opencode.json (chmod 600),
     * preserves all unrelated settings/providers, restarts opencode-serve with selective rollback and 40s wait.
     */
    suspend fun deleteProviderAndRestart(
        profileId: Long?,
        providerId: String,
        openCodePort: Int,
        localTunnelPort: Int?,
        onStepUpdate: (stepIndex: Int, status: InstallStepStatus, detail: String?) -> Unit = { _, _, _ -> },
    ): ProviderApplyOutcome = withContext(Dispatchers.IO) {
        onStepUpdate(0, InstallStepStatus.RUNNING, "Обновление opencode.json по SFTP…")
        val configPath = resolveConfigPath(openCodePort)
        val previousConfig = sftpRepository.readTextFile(configPath).text
        val updated = OpenCodeConfigHelper.removeProvider(previousConfig, providerId)

        sftpRepository.writeTextFile(
            path = configPath,
            text = updated,
            expectedMtimeEpochSec = null,
            forceOverwrite = true,
        )
        runCatching { sftpRepository.chmod(configPath, 0x180) }
        onStepUpdate(0, InstallStepStatus.SUCCESS, "Провайдер удалён из opencode.json")

        restartAndWaitWithSelectiveRollback(
            profileId = profileId,
            openCodePort = openCodePort,
            localTunnelPort = localTunnelPort,
            configPath = configPath,
            previousConfig = previousConfig,
            providerId = null,
            defaultModelId = null,
            onStepUpdate = onStepUpdate,
        )
    }

    /**
     * Retries the 40-second server readiness check without modifying opencode.json.
     */
    suspend fun retryServerHealthCheck(
        profileId: Long?,
        localTunnelPort: Int?,
        providerId: String?,
        defaultModelId: String?,
        onStepUpdate: (stepIndex: Int, status: InstallStepStatus, detail: String?) -> Unit,
    ): ProviderApplyOutcome = withContext(Dispatchers.IO) {
        onStepUpdate(2, InstallStepStatus.RUNNING, "Повторное ожидание ответа сервера (до 40 с)…")
        val ready = serverInstaller.waitForServerReadyAfterRestart(
            profileId = profileId,
            isUserService = null,
            initialTunnelPort = localTunnelPort,
            onProgress = { msg -> onStepUpdate(2, InstallStepStatus.RUNNING, msg) },
        )
        if (ready.serverAlive) {
            onStepUpdate(2, InstallStepStatus.SUCCESS, ready.detail)
            ProviderApplyOutcome.Success(
                providerId = providerId,
                defaultModelId = defaultModelId,
                tunnelPort = ready.activeTunnelPort,
                detail = ready.detail,
            )
        } else {
            onStepUpdate(2, InstallStepStatus.ERROR, ready.detail)
            ProviderApplyOutcome.SavedWithCheckWarning(
                providerId = providerId,
                defaultModelId = defaultModelId,
                tunnelPort = ready.activeTunnelPort,
                warningMessage = "Конфиг opencode.json сохранён и не откатывался, но сервер не ответил на /session за 40 с (${ready.detail}). Вы можете повторить проверку или продолжить.",
            )
        }
    }

    private suspend fun restartAndWaitWithSelectiveRollback(
        profileId: Long?,
        openCodePort: Int,
        localTunnelPort: Int?,
        configPath: String,
        previousConfig: String?,
        providerId: String?,
        defaultModelId: String?,
        onStepUpdate: (stepIndex: Int, status: InstallStepStatus, detail: String?) -> Unit,
    ): ProviderApplyOutcome {
        // Step 1: Restart opencode-serve and verify it transitions to active
        onStepUpdate(1, InstallStepStatus.RUNNING, "Перезапуск службы opencode-serve…")
        val restartRes = runCatching {
            serverInstaller.restartServiceAndCheckActive(port = openCodePort)
        }.getOrElse { err ->
            onStepUpdate(1, InstallStepStatus.ERROR, err.message ?: "Ошибка перезапуска службы")
            val rollbackNote = rollbackConfigToBackup(configPath, previousConfig, openCodePort)
            return ProviderApplyOutcome.RolledBackServiceInactive(
                errorMessage = "Служба opencode-serve не перешла в active после перезапуска (${err.message ?: "ошибка"}). $rollbackNote",
            )
        }

        if (!restartRes.serviceActive) {
            onStepUpdate(1, InstallStepStatus.ERROR, restartRes.detail)
            val rollbackNote = rollbackConfigToBackup(configPath, previousConfig, openCodePort)
            return ProviderApplyOutcome.RolledBackServiceInactive(
                errorMessage = "Служба opencode-serve не стала active (${restartRes.stateText}). $rollbackNote",
            )
        }
        onStepUpdate(1, InstallStepStatus.SUCCESS, restartRes.detail)

        // Step 2: Poll GET /session every 2s for 40s (Basic Auth, 401 = alive, rebuild SSH tunnel on drop)
        onStepUpdate(2, InstallStepStatus.RUNNING, "Ожидание ответа GET /session (до 40 с)…")
        val ready = serverInstaller.waitForServerReadyAfterRestart(
            profileId = profileId,
            isUserService = restartRes.isUserService,
            initialTunnelPort = localTunnelPort,
            onProgress = { msg -> onStepUpdate(2, InstallStepStatus.RUNNING, msg) },
        )

        if (!ready.serviceActive) {
            onStepUpdate(1, InstallStepStatus.ERROR, "Служба остановилась после запуска")
            onStepUpdate(2, InstallStepStatus.ERROR, ready.detail)
            val rollbackNote = rollbackConfigToBackup(configPath, previousConfig, openCodePort)
            return ProviderApplyOutcome.RolledBackServiceInactive(
                errorMessage = "Служба opencode-serve не удержалась в состоянии active (${ready.detail}). $rollbackNote",
            )
        }

        if (ready.serverAlive) {
            onStepUpdate(2, InstallStepStatus.SUCCESS, ready.detail)
            return ProviderApplyOutcome.Success(
                providerId = providerId,
                defaultModelId = defaultModelId,
                tunnelPort = ready.activeTunnelPort,
                detail = ready.detail,
            )
        }

        // Config was written AND opencode-serve IS active -> DO NOT ROLL BACK!
        onStepUpdate(2, InstallStepStatus.ERROR, "Таймаут 40 с: ${ready.detail}")
        return ProviderApplyOutcome.SavedWithCheckWarning(
            providerId = providerId,
            defaultModelId = defaultModelId,
            tunnelPort = ready.activeTunnelPort,
            warningMessage = "Файл ~/.config/opencode/opencode.json успешно записан, служба opencode-serve активна (active), но автопроверка /session не прошла за 40 с (${ready.detail}). Конфиг сохранён без отката.",
        )
    }

    private suspend fun rollbackConfigToBackup(
        configPath: String,
        previousConfig: String?,
        openCodePort: Int,
    ): String {
        val restored = runCatching {
            if (previousConfig != null) {
                sftpRepository.writeTextFile(
                    path = configPath,
                    text = previousConfig,
                    expectedMtimeEpochSec = null,
                    forceOverwrite = true,
                )
                runCatching { sftpRepository.chmod(configPath, 0x180) }
            } else {
                sftpRepository.deletePath(configPath, isDirectory = false)
            }
            // Best-effort restart with restored config
            runCatching { serverInstaller.restartServiceAndCheckActive(port = openCodePort) }
        }.isSuccess

        return if (restored) {
            "Выполнен откат ~/.config/opencode/opencode.json к резервной копии и повторный перезапуск службы."
        } else {
            "Не удалось автоматически восстановить резервную копию opencode.json — проверьте файл вручную."
        }
    }

    private suspend fun resolveEffectiveKeyTransient(
        apiKeyInput: String,
        existingProviderId: String?,
        openCodePort: Int,
    ): String? {
        val typed = apiKeyInput.trim()
        if (typed.isNotEmpty()) return typed
        if (existingProviderId.isNullOrBlank()) return null
        val configPath = resolveConfigPath(openCodePort)
        val existing = runCatching { sftpRepository.readTextFile(configPath).text }.getOrNull()
        return OpenCodeConfigHelper.extractRawApiKeyForProvider(existing, existingProviderId)
    }

    private suspend fun resolveHomeDir(openCodePort: Int): String {
        val env = serverInstaller.inspectEnvironment(openCodePort = openCodePort, requireSystemd = false)
        return env.homeDir.trimEnd('/').ifBlank { "/root" }
    }

    private suspend fun resolveConfigPath(openCodePort: Int): String {
        return "${resolveHomeDir(openCodePort)}/.config/opencode/opencode.json"
    }

    private suspend fun ensureConfigDir(configDir: String) {
        val client = connectionManager.getActiveSshClient()
            ?: throw IllegalStateException("SSH-соединение не активно")
        val quoted = "'" + configDir.replace("'", "'\\''") + "'"
        client.startSession().use { session ->
            val cmd = session.exec("mkdir -p $quoted && chmod 700 $quoted")
            cmd.join(15, TimeUnit.SECONDS)
        }
    }

    private suspend fun executeRemoteCurl(
        method: String,
        url: String,
        apiKey: String?,
        jsonBody: String?,
        connectTimeoutSec: Int,
        maxTimeSec: Int,
    ): RemoteCurlResponse = coroutineScope {
        val client = connectionManager.getActiveSshClient()
            ?: throw IllegalStateException("SSH-соединение не активно")

        val sanitizedUrl = url.replace("\r", "").replace("\n", "").trim()
        val sanitizedKey = apiKey?.replace("\r", "")?.replace("\n", "")?.trim()

        // Pass URL, headers (including Authorization), and body via curl -K - stdin so secrets
        // NEVER appear in remote argv, /proc/cmdline, shell history, or logs.
        val curlConfig = buildString {
            appendLine("url = \"${OpenCodeConfigHelper.escapeCurlConfigValue(sanitizedUrl)}\"")
            appendLine("request = \"$method\"")
            appendLine("header = \"Accept: application/json\"")
            if (jsonBody != null) {
                appendLine("header = \"Content-Type: application/json\"")
            }
            if (!sanitizedKey.isNullOrBlank()) {
                appendLine("header = \"Authorization: Bearer ${OpenCodeConfigHelper.escapeCurlConfigValue(sanitizedKey)}\"")
            }
            if (jsonBody != null) {
                appendLine("data-raw = \"${OpenCodeConfigHelper.escapeCurlConfigValue(jsonBody)}\"")
            }
        }

        val remoteCmd =
            "curl -sS --connect-timeout $connectTimeoutSec --max-time $maxTimeSec -w '\\n$HTTP_CODE_MARKER%{http_code}' -K -"

        client.startSession().use { session ->
            val cmd = session.exec(remoteCmd)

            // Write curl config to stdin and send EOF so curl executes immediately
            runCatching {
                cmd.outputStream.write(curlConfig.toByteArray(StandardCharsets.UTF_8))
                cmd.outputStream.flush()
                cmd.outputStream.close()
            }

            val stdoutDeferred = async(Dispatchers.IO) {
                BufferedReader(InputStreamReader(cmd.inputStream, StandardCharsets.UTF_8)).use { it.readText() }
            }
            val stderrDeferred = async(Dispatchers.IO) {
                BufferedReader(InputStreamReader(cmd.errorStream, StandardCharsets.UTF_8)).use { it.readText() }
            }

            val rawStdout = stdoutDeferred.await()
            val rawStderr = stderrDeferred.await()
            cmd.join((maxTimeSec + 5).toLong(), TimeUnit.SECONDS)
            val exitCode = cmd.exitStatus ?: -1

            val markerIdx = rawStdout.lastIndexOf(HTTP_CODE_MARKER)
            val (body, httpCode) = if (markerIdx >= 0) {
                val bodyPart = rawStdout.substring(0, markerIdx).trimEnd('\r', '\n')
                val codePart = rawStdout.substring(markerIdx + HTTP_CODE_MARKER.length).trim()
                bodyPart to (codePart.toIntOrNull() ?: 0)
            } else {
                rawStdout to 0
            }

            RemoteCurlResponse(
                exitCode = exitCode,
                httpCode = httpCode,
                body = body,
                stderr = rawStderr,
            )
        }
    }

    private fun isUnreachableResponse(response: RemoteCurlResponse): Boolean {
        if (response.exitCode in UNREACHABLE_CURL_CODES) return true
        if (response.httpCode == 0 && response.exitCode != 0 && response.exitCode != 35 && response.exitCode != 60 && response.exitCode != 127) {
            return true
        }
        return false
    }

    private fun throwIfServerUnreachable(response: RemoteCurlResponse, secret: String?) {
        if (response.exitCode == 127) {
            throw IllegalStateException("На сервере не установлен curl — невозможно обратиться к провайдеру")
        }
        if (isUnreachableResponse(response)) {
            throw ServerUnreachableException(OpenCodeConfigHelper.SERVER_UNREACHABLE_MESSAGE)
        }
        if (response.exitCode == 35 || response.exitCode == 60) {
            throw IllegalStateException("Ошибка TLS/SSL при подключении сервера к адресу (код curl ${response.exitCode})")
        }
        if (response.httpCode == 0 && response.exitCode != 0) {
            val detail = OpenCodeConfigHelper.sanitizeLogs(response.stderr.trim(), listOfNotNull(secret))
            throw IllegalStateException(detail.ifBlank { OpenCodeConfigHelper.SERVER_UNREACHABLE_MESSAGE })
        }
    }

    companion object {
        private const val HTTP_CODE_MARKER = "__OPENCODE_HTTP_CODE__:"
        // curl exit codes: 6=DNS, 7=connect failed, 28=timeout, 52=empty reply, 56=recv failure
        private val UNREACHABLE_CURL_CODES = setOf(6, 7, 28, 52, 56)
    }
}
