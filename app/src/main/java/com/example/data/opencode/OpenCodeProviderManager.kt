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
     * 重启后 /session 检查在 40 秒内未通过时显示警告，配置不会回滚。
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
            throw IllegalStateException("/models 返回了无效 JSON：${err.message ?: "解析失败"}")
        }
        if (models.isEmpty()) {
            throw IllegalStateException("供应商返回 HTTP ${response.httpCode}，但模型列表为空")
        }
        models
    }

    /**
     * 通过 POST {baseURL}/chat/completions 从远程服务器发送实时测试提示词“请只回答一个词”，
     * 并返回模型回复或 HTTP 状态码及错误正文。
     */
    suspend fun testModelCompletion(
        baseUrl: String,
        apiKeyInput: String,
        existingProviderId: String?,
        modelId: String,
        openCodePort: Int,
    ): ModelTestResult = withContext(Dispatchers.IO) {
        val cleanModel = modelId.trim()
        require(cleanModel.isNotEmpty()) { "请选择要测试的模型" }

        val normalizedBase = OpenCodeConfigHelper.normalizeBaseUrl(baseUrl)
        val effectiveKey = resolveEffectiveKeyTransient(apiKeyInput, existingProviderId, openCodePort)

        val payload = JSONObject().apply {
            put("model", cleanModel)
            put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", "请只回答一个词"),
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
                message = "服务器未安装 curl",
            )
        }

        if (response.exitCode == 35 || response.exitCode == 60) {
            return@withContext ModelTestResult(
                success = false,
                httpCode = null,
                modelId = cleanModel,
                message = "服务器连接地址时发生 TLS/SSL 错误（curl 退出码 ${response.exitCode}）",
            )
        }

        val cleanBody = OpenCodeConfigHelper.sanitizeLogs(
            response.body.trim(),
            listOfNotNull(effectiveKey),
        )

        if (response.httpCode in 200..299) {
            val reply = runCatching {
                OpenCodeConfigHelper.parseChatCompletionReply(cleanBody)
            }.getOrElse { cleanBody.take(240) }.ifBlank { "成功（空响应）" }

            ModelTestResult(
                success = true,
                httpCode = response.httpCode,
                modelId = cleanModel,
                message = reply.take(300),
            )
        } else {
            val errorDetail = cleanBody.take(600)
                .ifBlank { OpenCodeConfigHelper.sanitizeLogs(response.stderr.trim().take(300), listOfNotNull(effectiveKey)) }
                .ifBlank { "服务器返回空响应" }
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
     * Throws ServerUnreachableException("服务器无法访问互联网或目标地址") if unreachable.
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

        onStepUpdate(0, InstallStepStatus.RUNNING, "正在从服务器检查地址是否可访问…")
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

        onStepUpdate(0, InstallStepStatus.RUNNING, "正在通过 SFTP 写入 ~/.config/opencode/opencode.json…")
        // Write exclusively via SFTP so API key never touches shell commands or logs
        sftpRepository.writeTextFile(
            path = configPath,
            text = merged,
            expectedMtimeEpochSec = null,
            forceOverwrite = true,
        )
        runCatching { sftpRepository.chmod(configPath, 0x180) }
        onStepUpdate(0, InstallStepStatus.SUCCESS, "opencode.json 已写入（chmod 600）")

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
        require(cleanKey.isNotEmpty()) { "请输入新的 API 密钥" }

        onStepUpdate(0, InstallStepStatus.RUNNING, "正在使用新密钥检查访问权限…")
        if (baseUrl.isNotBlank()) {
            verifyServerReachable(baseUrl, cleanKey)
        }

        val configPath = resolveConfigPath(openCodePort)
        val previousConfig = sftpRepository.readTextFile(configPath).text
        val updated = OpenCodeConfigHelper.updateProviderApiKey(previousConfig, providerId, cleanKey)

        onStepUpdate(0, InstallStepStatus.RUNNING, "正在通过 SFTP 更新 opencode.json 中的密钥…")
        sftpRepository.writeTextFile(
            path = configPath,
            text = updated,
            expectedMtimeEpochSec = null,
            forceOverwrite = true,
        )
        runCatching { sftpRepository.chmod(configPath, 0x180) }
        onStepUpdate(0, InstallStepStatus.SUCCESS, "密钥已写入 opencode.json（chmod 600）")

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
        onStepUpdate(0, InstallStepStatus.RUNNING, "正在通过 SFTP 更新 opencode.json…")
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
        onStepUpdate(0, InstallStepStatus.SUCCESS, "已从 opencode.json 移除供应商")

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
        onStepUpdate(2, InstallStepStatus.RUNNING, "再次等待服务器响应（最多 40 秒）…")
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
                warningMessage = "opencode.json 配置已保存且未回滚，但服务器在 40 秒内未响应 /session（${ready.detail}）。你可以重试检查或继续。",
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
        onStepUpdate(1, InstallStepStatus.RUNNING, "正在重启 opencode-serve 服务…")
        val restartRes = runCatching {
            serverInstaller.restartServiceAndCheckActive(port = openCodePort)
        }.getOrElse { err ->
            onStepUpdate(1, InstallStepStatus.ERROR, err.message ?: "重启服务失败")
            val rollbackNote = rollbackConfigToBackup(configPath, previousConfig, openCodePort)
            return ProviderApplyOutcome.RolledBackServiceInactive(
                errorMessage = "重启后 opencode-serve 未进入 active 状态（${err.message ?: "错误"}）。$rollbackNote",
            )
        }

        if (!restartRes.serviceActive) {
            onStepUpdate(1, InstallStepStatus.ERROR, restartRes.detail)
            val rollbackNote = rollbackConfigToBackup(configPath, previousConfig, openCodePort)
            return ProviderApplyOutcome.RolledBackServiceInactive(
                errorMessage = "opencode-serve 未进入 active 状态（${restartRes.stateText}）。$rollbackNote",
            )
        }
        onStepUpdate(1, InstallStepStatus.SUCCESS, restartRes.detail)

        // Step 2: Poll GET /session every 2s for 40s (Basic Auth, 401 = alive, rebuild SSH tunnel on drop)
        onStepUpdate(2, InstallStepStatus.RUNNING, "正在等待 GET /session 响应（最多 40 秒）…")
        val ready = serverInstaller.waitForServerReadyAfterRestart(
            profileId = profileId,
            isUserService = restartRes.isUserService,
            initialTunnelPort = localTunnelPort,
            onProgress = { msg -> onStepUpdate(2, InstallStepStatus.RUNNING, msg) },
        )

        if (!ready.serviceActive) {
            onStepUpdate(1, InstallStepStatus.ERROR, "服务启动后停止运行")
            onStepUpdate(2, InstallStepStatus.ERROR, ready.detail)
            val rollbackNote = rollbackConfigToBackup(configPath, previousConfig, openCodePort)
            return ProviderApplyOutcome.RolledBackServiceInactive(
                errorMessage = "opencode-serve 未能保持 active 状态（${ready.detail}）。$rollbackNote",
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
        onStepUpdate(2, InstallStepStatus.ERROR, "等待超时（40 秒）：${ready.detail}")
        return ProviderApplyOutcome.SavedWithCheckWarning(
            providerId = providerId,
            defaultModelId = defaultModelId,
            tunnelPort = ready.activeTunnelPort,
            warningMessage = "~/.config/opencode/opencode.json 已成功写入，opencode-serve 服务处于 active 状态，但 /session 自动检查在 40 秒内未通过（${ready.detail}）。配置已保留，未回滚。",
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
            "已将 ~/.config/opencode/opencode.json 恢复为备份，并再次重启服务。"
        } else {
            "无法自动恢复 opencode.json 备份，请手动检查文件。"
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
            ?: throw IllegalStateException("SSH 连接未激活")
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
            ?: throw IllegalStateException("SSH 连接未激活")

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
            throw IllegalStateException("服务器未安装 curl — 无法连接供应商")
        }
        if (isUnreachableResponse(response)) {
            throw ServerUnreachableException(OpenCodeConfigHelper.SERVER_UNREACHABLE_MESSAGE)
        }
        if (response.exitCode == 35 || response.exitCode == 60) {
            throw IllegalStateException("服务器连接地址时发生 TLS/SSL 错误（curl 退出码 ${response.exitCode}）")
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
