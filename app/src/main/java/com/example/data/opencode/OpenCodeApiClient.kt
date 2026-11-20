package com.example.data.opencode

import com.example.data.local.KeystoreSecretStore
import com.example.data.ssh.SshConnectionManager
import com.example.domain.model.AttachedFileRef
import com.example.domain.model.OpenCodeAgent
import com.example.domain.model.OpenCodeEvent
import com.example.domain.model.OpenCodeFileDiff
import com.example.domain.model.OpenCodeMessage
import com.example.domain.model.OpenCodePermissionRequest
import com.example.domain.model.OpenCodeProvider
import com.example.domain.model.OpenCodeSession
import com.example.domain.model.PermissionReplyOption
import com.example.domain.model.SseConnectionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.math.min

class OpenCodeApiClient(
    private val connectionManager: SshConnectionManager,
    private val secretStore: KeystoreSecretStore? = null,
) {
    private val restClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val sseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var discoveredPort: Int? = null

    @Volatile
    private var discoveredPaths: DiscoveredOpenCodePaths = DiscoveredOpenCodePaths()

    fun invalidateDiscoveryCache() {
        discoveredPort = null
    }

    private fun buildBasicAuthHeader(): String? {
        val profileId = connectionManager.getActiveProfileId() ?: return null
        val auth = secretStore?.getOpenCodeServerAuth(profileId) ?: return null
        return Credentials.basic(auth.username.ifBlank { "opencode" }, auth.password, StandardCharsets.UTF_8)
    }

    private fun Request.Builder.applyOpenCodeAuth(): Request.Builder {
        buildBasicAuthHeader()?.let { header("Authorization", it) }
        return this
    }

    private fun requireBaseUrl(): Pair<Int, String> {
        val port = connectionManager.getActiveLocalTunnelPort()
            ?: throw IllegalStateException("SSH-туннель к OpenCode не активен")
        return port to "http://127.0.0.1:$port"
    }

    suspend fun ensureApiDiscovered(): DiscoveredOpenCodePaths = withContext(Dispatchers.IO) {
        val (port, baseUrl) = requireBaseUrl()
        if (discoveredPort == port) return@withContext discoveredPaths

        val docJson = runCatching { executeGet("$baseUrl/doc") }.getOrNull()
        val parsed = if (!docJson.isNullOrBlank()) {
            OpenCodeJsonParser.parseOpenApiDoc(docJson)
        } else {
            DiscoveredOpenCodePaths()
        }
        discoveredPaths = parsed
        discoveredPort = port
        parsed
    }

    suspend fun listSessions(): List<OpenCodeSession> = withContext(Dispatchers.IO) {
        ensureApiDiscovered()
        val (_, baseUrl) = requireBaseUrl()
        val body = executeGet("$baseUrl/session")
        OpenCodeJsonParser.parseSessions(body)
    }

    suspend fun createSession(title: String? = null): OpenCodeSession = withContext(Dispatchers.IO) {
        ensureApiDiscovered()
        val (_, baseUrl) = requireBaseUrl()
        val payload = JSONObject().apply {
            if (!title.isNullOrBlank()) put("title", title.trim())
        }
        val body = executeJsonRequest("POST", "$baseUrl/session", payload)
        OpenCodeJsonParser.parseSession(JSONObject(body))
            ?: throw IllegalStateException("Сервер OpenCode вернул пустую сессию")
    }

    suspend fun renameSession(sessionId: String, title: String): OpenCodeSession =
        withContext(Dispatchers.IO) {
            val (_, baseUrl) = requireBaseUrl()
            val payload = JSONObject().put("title", title.trim())
            val body = executeJsonRequest("PATCH", "$baseUrl/session/$sessionId", payload)
            OpenCodeJsonParser.parseSession(JSONObject(body))
                ?: OpenCodeSession(id = sessionId, title = title.trim())
        }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        val (_, baseUrl) = requireBaseUrl()
        executeDelete("$baseUrl/session/$sessionId")
    }

    suspend fun forkSession(sessionId: String, messageId: String? = null): OpenCodeSession =
        withContext(Dispatchers.IO) {
            val (_, baseUrl) = requireBaseUrl()
            val payload = JSONObject().apply {
                if (!messageId.isNullOrBlank()) put("messageID", messageId)
            }
            val body = executeJsonRequest("POST", "$baseUrl/session/$sessionId/fork", payload)
            OpenCodeJsonParser.parseSession(JSONObject(body))
                ?: throw IllegalStateException("Не удалось выполнить форк сессии")
        }

    suspend fun getMessages(sessionId: String): List<OpenCodeMessage> = withContext(Dispatchers.IO) {
        val paths = ensureApiDiscovered()
        val (_, baseUrl) = requireBaseUrl()
        val primaryUrl = "$baseUrl/session/$sessionId/${paths.messagesListSuffix}"
        val body = runCatching { executeGet(primaryUrl) }.getOrElse {
            val fallbackSuffix = if (paths.messagesListSuffix == "message") "messages" else "message"
            executeGet("$baseUrl/session/$sessionId/$fallbackSuffix")
        }
        OpenCodeJsonParser.parseMessages(body, fallbackSessionId = sessionId)
    }

    suspend fun sendMessage(
        sessionId: String,
        text: String,
        attachments: List<AttachedFileRef>,
        providerId: String?,
        modelId: String?,
        agent: String?,
    ) = withContext(Dispatchers.IO) {
        val paths = ensureApiDiscovered()
        val (_, baseUrl) = requireBaseUrl()

        val promptText = buildString {
            val trimmed = text.trim()
            if (trimmed.isNotEmpty()) append(trimmed)
            val missingRefs = attachments.filter { !trimmed.contains("@${it.path}") && !trimmed.contains("@${it.name}") }
            if (missingRefs.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(missingRefs.joinToString(" ") { "@${it.path}" })
            }
        }

        val parts = JSONArray().apply {
            if (promptText.isNotBlank()) {
                put(
                    JSONObject()
                        .put("type", "text")
                        .put("text", promptText),
                )
            }
            attachments.forEach { file ->
                put(
                    JSONObject()
                        .put("type", "file")
                        .put("mime", "text/plain")
                        .put("filename", file.name)
                        .put("url", "file://${file.path}"),
                )
            }
        }

        val payload = JSONObject().apply {
            put("parts", parts)
            if (!providerId.isNullOrBlank() && !modelId.isNullOrBlank()) {
                put(
                    "model",
                    JSONObject()
                        .put("providerID", providerId)
                        .put("modelID", modelId),
                )
            }
            if (!agent.isNullOrBlank()) {
                put("agent", agent)
            }
        }

        if (paths.promptAsyncSupported) {
            runCatching {
                executeJsonRequest("POST", "$baseUrl/session/$sessionId/prompt_async", payload)
            }.onFailure {
                executeJsonRequest("POST", "$baseUrl/session/$sessionId/message", payload)
            }
        } else {
            executeJsonRequest("POST", "$baseUrl/session/$sessionId/message", payload)
        }
    }

    suspend fun abortSession(sessionId: String) = withContext(Dispatchers.IO) {
        val (_, baseUrl) = requireBaseUrl()
        executeJsonRequest("POST", "$baseUrl/session/$sessionId/abort", JSONObject())
    }

    suspend fun revertSession(sessionId: String, messageId: String) = withContext(Dispatchers.IO) {
        val (_, baseUrl) = requireBaseUrl()
        val payload = JSONObject().put("messageID", messageId)
        executeJsonRequest("POST", "$baseUrl/session/$sessionId/revert", payload)
    }

    suspend fun getProviders(): List<OpenCodeProvider> = withContext(Dispatchers.IO) {
        val paths = ensureApiDiscovered()
        val (_, baseUrl) = requireBaseUrl()
        val body = runCatching { executeGet("$baseUrl${paths.providersPath}") }.getOrElse {
            val alt = if (paths.providersPath == "/config/providers") "/provider" else "/config/providers"
            executeGet("$baseUrl$alt")
        }
        OpenCodeJsonParser.parseProviders(body)
    }

    suspend fun getAgents(): List<OpenCodeAgent> = withContext(Dispatchers.IO) {
        val paths = ensureApiDiscovered()
        val (_, baseUrl) = requireBaseUrl()
        val body = runCatching { executeGet("$baseUrl${paths.agentsPath}") }.getOrNull()
        if (body.isNullOrBlank()) {
            OpenCodeJsonParser.defaultAgents()
        } else {
            OpenCodeJsonParser.parseAgents(body)
        }
    }

    suspend fun getSessionDiff(sessionId: String): List<OpenCodeFileDiff> = withContext(Dispatchers.IO) {
        ensureApiDiscovered()
        val (_, baseUrl) = requireBaseUrl()
        val body = executeGet("$baseUrl/session/$sessionId/diff")
        OpenCodeJsonParser.parseFileDiffs(body)
    }

    suspend fun listPendingPermissions(): List<OpenCodePermissionRequest> = withContext(Dispatchers.IO) {
        val paths = ensureApiDiscovered()
        if (!paths.permissionListSupported) return@withContext emptyList()
        val (_, baseUrl) = requireBaseUrl()
        val body = runCatching { executeGet("$baseUrl/permission") }.getOrNull() ?: return@withContext emptyList()
        OpenCodeJsonParser.parsePermissions(body)
    }

    suspend fun replyPermission(
        request: OpenCodePermissionRequest,
        option: PermissionReplyOption,
    ) = withContext(Dispatchers.IO) {
        val paths = ensureApiDiscovered()
        val (_, baseUrl) = requireBaseUrl()

        val globalUrl = "$baseUrl/permission/${request.id}/reply"
        val globalPayload = JSONObject().put("reply", option.apiValue)
        val sessionUrl = if (request.sessionId.isNotBlank()) {
            "$baseUrl/session/${request.sessionId}/permissions/${request.id}"
        } else {
            null
        }
        val sessionPayload = JSONObject().put("response", option.apiValue)

        if (paths.globalPermissionReplySupported) {
            runCatching {
                executeJsonRequest("POST", globalUrl, globalPayload)
            }.onFailure { err ->
                if (sessionUrl != null) {
                    executeJsonRequest("POST", sessionUrl, sessionPayload)
                } else {
                    throw err
                }
            }
        } else if (sessionUrl != null) {
            runCatching {
                executeJsonRequest("POST", sessionUrl, sessionPayload)
            }.onFailure {
                executeJsonRequest("POST", globalUrl, globalPayload)
            }
        } else {
            executeJsonRequest("POST", globalUrl, globalPayload)
        }
    }

    fun observeEvents(tunnelPort: Int): Flow<Pair<SseConnectionStatus, OpenCodeEvent?>> = flow {
        val url = "http://127.0.0.1:$tunnelPort/event"
        var attempt = 0

        while (currentCoroutineContext().isActive) {
            emit((if (attempt == 0) SseConnectionStatus.CONNECTING else SseConnectionStatus.RECONNECTING) to null)
            val request = Request.Builder()
                .url(url)
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .applyOpenCodeAuth()
                .get()
                .build()

            val call = sseClient.newCall(request)
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("SSE HTTP ${response.code}")
                    }
                    attempt = 0
                    emit(SseConnectionStatus.CONNECTED to null)
                    val bodyStream = response.body?.byteStream()
                        ?: throw IllegalStateException("Пустой SSE поток")

                    val reader = BufferedReader(InputStreamReader(bodyStream, StandardCharsets.UTF_8))
                    val dataBuffer = StringBuilder()

                    while (currentCoroutineContext().isActive) {
                        val line = reader.readLine() ?: break
                        when {
                            line.startsWith("data:") -> {
                                if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                                dataBuffer.append(line.removePrefix("data:").trimStart())
                            }

                            line.isEmpty() -> {
                                if (dataBuffer.isNotEmpty()) {
                                    val event = OpenCodeJsonParser.parseSseEvent(dataBuffer.toString())
                                    dataBuffer.setLength(0)
                                    if (event != null) {
                                        emit(SseConnectionStatus.CONNECTED to event)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                call.cancel()
                throw cancelled
            } catch (_: Throwable) {
                call.cancel()
            }

            if (!currentCoroutineContext().isActive) break
            attempt++
            emit(SseConnectionStatus.RECONNECTING to null)
            val delayMs = min(1_500L shl (attempt - 1).coerceAtMost(4), 15_000L)
            delay(delayMs)
        }
    }.flowOn(Dispatchers.IO)

    private fun executeGet(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .applyOpenCodeAuth()
            .get()
            .build()
        return execute(req)
    }

    private fun executeDelete(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .applyOpenCodeAuth()
            .delete()
            .build()
        return execute(req)
    }

    private fun executeJsonRequest(method: String, url: String, payload: JSONObject): String {
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .applyOpenCodeAuth()
            .method(method, body)
            .build()
        return execute(req)
    }

    private fun execute(request: Request): String {
        restClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val parsedError = runCatching {
                    val obj = JSONObject(responseBody)
                    obj.optString("message").takeIf { it.isNotBlank() }
                        ?: obj.optString("error").takeIf { it.isNotBlank() }
                }.getOrNull()
                throw IllegalStateException(
                    parsedError ?: "OpenCode API ошибка HTTP ${response.code}",
                )
            }
            return responseBody
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
