package com.example.data.opencode

import com.example.domain.model.DiffLineType
import com.example.domain.model.OpenCodeAgent
import com.example.domain.model.OpenCodeEvent
import com.example.domain.model.OpenCodeFileDiff
import com.example.domain.model.OpenCodeMessage
import com.example.domain.model.OpenCodeModel
import com.example.domain.model.OpenCodePart
import com.example.domain.model.OpenCodePermissionRequest
import com.example.domain.model.OpenCodeProvider
import com.example.domain.model.OpenCodeSession
import com.example.domain.model.TokenUsage
import com.example.domain.model.ToolCallStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * Schema field names verified against OpenCode's live `/doc` OpenAPI document
 * (`components.schemas.AssistantMessage`, `Message`, `Model`), falling back to
 * OpenCode's documented standard field names when `/doc` is unavailable.
 */
data class OpenCodeUsageSchemaFields(
    val tokensField: String = "tokens",
    val costField: String = "cost",
    val inputField: String = "input",
    val outputField: String = "output",
    val reasoningField: String = "reasoning",
    val cacheField: String = "cache",
    val cacheReadField: String = "read",
    val cacheWriteField: String = "write",
    val modelLimitField: String = "limit",
    val modelContextLimitField: String = "context",
    val modelOutputLimitField: String = "output",
    val modelCostField: String = "cost",
    val modelCostInputField: String = "input",
    val modelCostOutputField: String = "output",
)

data class DiscoveredOpenCodePaths(
    val messagesListSuffix: String = "message",
    val promptAsyncSupported: Boolean = true,
    val providersPath: String = "/config/providers",
    val agentsPath: String = "/agent",
    val revertSupported: Boolean = true,
    val forkSupported: Boolean = true,
    val sessionDiffSupported: Boolean = true,
    val globalPermissionReplySupported: Boolean = true,
    val sessionPermissionReplySupported: Boolean = true,
    val permissionListSupported: Boolean = true,
    val directoryQueryParamName: String? = "directory",
    val directoryHeaderName: String? = "x-opencode-directory",
    val usageSchema: OpenCodeUsageSchemaFields = OpenCodeUsageSchemaFields(),
)

object OpenCodeJsonParser {

    fun parseOpenApiDoc(rawJson: String): DiscoveredOpenCodePaths {
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return DiscoveredOpenCodePaths()
        val paths = root.optJSONObject("paths") ?: return DiscoveredOpenCodePaths()
        val keys = buildSet {
            val iter = paths.keys()
            while (iter.hasNext()) add(iter.next())
        }

        val hasSingularMessage = keys.any { it.matches(Regex("^/session/\\{[^}]+\\}/message$")) }
        val hasPluralMessages = keys.any { it.matches(Regex("^/session/\\{[^}]+\\}/messages$")) }
        val messagesSuffix = when {
            hasSingularMessage -> "message"
            hasPluralMessages -> "messages"
            else -> "message"
        }

        val promptAsync = keys.any { it.endsWith("/prompt_async") }
        val providersPath = when {
            "/config/providers" in keys -> "/config/providers"
            "/provider" in keys -> "/provider"
            else -> "/config/providers"
        }
        val agentsPath = when {
            "/agent" in keys -> "/agent"
            "/app/agents" in keys -> "/app/agents"
            else -> "/agent"
        }
        val revertSupported = keys.any { it.endsWith("/revert") }
        val forkSupported = keys.any { it.endsWith("/fork") }
        val sessionDiffSupported = keys.any { it.endsWith("/diff") }
        val globalPermissionReplySupported = keys.any { it.matches(Regex("^/permission/\\{[^}]+\\}/reply$")) }
        val sessionPermissionReplySupported = keys.any { it.matches(Regex("^/session/\\{[^}]+\\}/permissions/\\{[^}]+\\}$")) }
        val permissionListSupported = "/permission" in keys

        val (dirQueryParam, dirHeader) = detectDirectoryParameters(root, paths)
        val usageSchema = detectUsageSchemaFields(root)

        return DiscoveredOpenCodePaths(
            messagesListSuffix = messagesSuffix,
            promptAsyncSupported = promptAsync,
            providersPath = providersPath,
            agentsPath = agentsPath,
            revertSupported = revertSupported,
            forkSupported = forkSupported,
            sessionDiffSupported = sessionDiffSupported,
            globalPermissionReplySupported = globalPermissionReplySupported,
            sessionPermissionReplySupported = sessionPermissionReplySupported,
            permissionListSupported = permissionListSupported,
            directoryQueryParamName = dirQueryParam,
            directoryHeaderName = dirHeader,
            usageSchema = usageSchema,
        )
    }

    private fun detectUsageSchemaFields(root: JSONObject): OpenCodeUsageSchemaFields {
        val schemas = root.optJSONObject("components")?.optJSONObject("schemas")
            ?: return OpenCodeUsageSchemaFields()

        fun resolveSchema(raw: JSONObject?): JSONObject? {
            if (raw == null) return null
            val ref = raw.optString("\$ref").takeIf { it.isNotBlank() }
            if (ref != null) {
                return schemas.optJSONObject(ref.substringAfterLast('/'))
            }
            return raw
        }

        fun collectProperties(schemaObj: JSONObject?): Map<String, JSONObject> {
            val resolved = resolveSchema(schemaObj) ?: return emptyMap()
            val out = LinkedHashMap<String, JSONObject>()
            resolved.optJSONObject("properties")?.let { props ->
                val it = props.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    props.optJSONObject(k)?.let { v -> out[k] = v }
                }
            }
            for (comboKey in listOf("allOf", "anyOf", "oneOf")) {
                val arr = resolved.optJSONArray(comboKey) ?: continue
                for (i in 0 until arr.length()) {
                    out.putAll(collectProperties(arr.optJSONObject(i)))
                }
            }
            return out
        }

        fun pickKey(props: Map<String, JSONObject>, vararg candidates: String): String? {
            for (cand in candidates) {
                val exact = props.keys.firstOrNull { it == cand }
                if (exact != null) return exact
            }
            for (cand in candidates) {
                val ci = props.keys.firstOrNull { it.equals(cand, ignoreCase = true) }
                if (ci != null) return ci
            }
            return null
        }

        val assistantSchema = schemas.optJSONObject("AssistantMessage")
            ?: schemas.optJSONObject("Message")
        val msgProps = collectProperties(assistantSchema)
        val tokensField = pickKey(msgProps, "tokens", "usage") ?: "tokens"
        val costField = pickKey(msgProps, "cost", "costUsd", "totalCost") ?: "cost"

        val tokensProps = collectProperties(msgProps[tokensField])
        val inputField = pickKey(tokensProps, "input", "inputTokens", "prompt_tokens") ?: "input"
        val outputField = pickKey(tokensProps, "output", "outputTokens", "completion_tokens") ?: "output"
        val reasoningField = pickKey(tokensProps, "reasoning", "reasoningTokens") ?: "reasoning"
        val cacheField = pickKey(tokensProps, "cache", "cached") ?: "cache"
        val cacheProps = collectProperties(tokensProps[cacheField])
        val cacheReadField = pickKey(cacheProps, "read", "cacheRead", "cached") ?: "read"
        val cacheWriteField = pickKey(cacheProps, "write", "cacheWrite", "creation") ?: "write"

        val modelSchema = schemas.optJSONObject("Model")
            ?: schemas.optJSONObject("ProviderModel")
        val modelProps = collectProperties(modelSchema)
        val modelLimitField = pickKey(modelProps, "limit", "limits") ?: "limit"
        val limitProps = collectProperties(modelProps[modelLimitField])
        val modelContextLimitField = pickKey(limitProps, "context", "contextWindow", "context_length") ?: "context"
        val modelOutputLimitField = pickKey(limitProps, "output", "maxOutput", "max_tokens") ?: "output"

        val modelCostField = pickKey(modelProps, "cost", "pricing") ?: "cost"
        val modelCostProps = collectProperties(modelProps[modelCostField])
        val modelCostInputField = pickKey(modelCostProps, "input", "prompt") ?: "input"
        val modelCostOutputField = pickKey(modelCostProps, "output", "completion") ?: "output"

        return OpenCodeUsageSchemaFields(
            tokensField = tokensField,
            costField = costField,
            inputField = inputField,
            outputField = outputField,
            reasoningField = reasoningField,
            cacheField = cacheField,
            cacheReadField = cacheReadField,
            cacheWriteField = cacheWriteField,
            modelLimitField = modelLimitField,
            modelContextLimitField = modelContextLimitField,
            modelOutputLimitField = modelOutputLimitField,
            modelCostField = modelCostField,
            modelCostInputField = modelCostInputField,
            modelCostOutputField = modelCostOutputField,
        )
    }

    private fun detectDirectoryParameters(
        root: JSONObject,
        paths: JSONObject,
    ): Pair<String?, String?> {
        var foundQueryName: String? = null
        var foundHeaderName: String? = null
        val componentParams = root.optJSONObject("components")?.optJSONObject("parameters")

        fun inspectParam(rawParam: JSONObject?) {
            if (rawParam == null) return
            val param = if (rawParam.has("\$ref")) {
                val refName = rawParam.optString("\$ref").substringAfterLast('/')
                componentParams?.optJSONObject(refName) ?: return
            } else {
                rawParam
            }
            val location = param.optString("in").lowercase()
            val name = param.optString("name").trim()
            if (location == "query" && name.equals("directory", ignoreCase = true)) {
                foundQueryName = name
            } else if (location == "header" && name.equals("x-opencode-directory", ignoreCase = true)) {
                foundHeaderName = name
            }
        }

        fun inspectParamArray(arr: JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                inspectParam(arr.optJSONObject(i))
            }
        }

        val pathIter = paths.keys()
        while (pathIter.hasNext()) {
            val pathKey = pathIter.next()
            val pathItem = paths.optJSONObject(pathKey) ?: continue
            inspectParamArray(pathItem.optJSONArray("parameters"))
            for (method in listOf("get", "post", "put", "patch", "delete")) {
                val op = pathItem.optJSONObject(method) ?: continue
                inspectParamArray(op.optJSONArray("parameters"))
            }
        }

        // OpenCode's HTTP API supports ?directory=... and x-opencode-directory header.
        // If /doc declared either explicitly, honor its exact declaration; otherwise fall back to standard defaults.
        return if (foundQueryName != null || foundHeaderName != null) {
            foundQueryName to foundHeaderName
        } else {
            "directory" to "x-opencode-directory"
        }
    }

    fun parseSessions(rawJson: String): List<OpenCodeSession> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return emptyList()
        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> {
                val obj = JSONObject(trimmed)
                obj.optJSONArray("sessions") ?: obj.optJSONArray("data") ?: JSONArray()
            }
            else -> JSONArray()
        }
        val result = mutableListOf<OpenCodeSession>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            parseSession(item)?.let(result::add)
        }
        return result.sortedByDescending { it.updatedAtMillis }
    }

    fun parseSession(obj: JSONObject): OpenCodeSession? {
        val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
        val time = obj.optJSONObject("time")
        val created = normalizeEpochMillis(
            time?.optLong("created") ?: obj.optLong("createdAt", obj.optLong("created", 0L)),
        )
        val updated = normalizeEpochMillis(
            time?.optLong("updated") ?: obj.optLong("updatedAt", obj.optLong("updated", created)),
        )
        return OpenCodeSession(
            id = id,
            title = obj.optString("title").trim(),
            directory = obj.optString("directory").orEmpty(),
            parentId = obj.optString("parentID").takeIf { it.isNotBlank() },
            createdAtMillis = created,
            updatedAtMillis = updated,
        )
    }

    fun parseMessages(
        rawJson: String,
        fallbackSessionId: String,
        schema: OpenCodeUsageSchemaFields = OpenCodeUsageSchemaFields(),
    ): List<OpenCodeMessage> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return emptyList()
        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> {
                val obj = JSONObject(trimmed)
                obj.optJSONArray("messages") ?: obj.optJSONArray("data") ?: JSONArray()
            }
            else -> JSONArray()
        }
        val list = mutableListOf<OpenCodeMessage>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            parseMessageWrapper(item, fallbackSessionId, schema)?.let(list::add)
        }
        return list.sortedBy { it.createdAtMillis }
    }

    fun parseMessageWrapper(
        obj: JSONObject,
        fallbackSessionId: String,
        schema: OpenCodeUsageSchemaFields = OpenCodeUsageSchemaFields(),
    ): OpenCodeMessage? {
        val infoObj = obj.optJSONObject("info") ?: obj
        val base = parseMessageInfo(infoObj, fallbackSessionId, schema) ?: return null
        val partsArray = obj.optJSONArray("parts")
        val parts = if (partsArray != null) {
            buildList {
                for (i in 0 until partsArray.length()) {
                    val p = partsArray.optJSONObject(i) ?: continue
                    parsePart(p, base.sessionId, base.id)?.let(::add)
                }
            }
        } else {
            emptyList()
        }
        return base.copy(parts = parts)
    }

    fun parseMessageInfo(
        info: JSONObject,
        fallbackSessionId: String = "",
        schema: OpenCodeUsageSchemaFields = OpenCodeUsageSchemaFields(),
    ): OpenCodeMessage? {
        val id = info.optString("id").takeIf { it.isNotBlank() } ?: return null
        val sessionId = info.optString("sessionID").takeIf { it.isNotBlank() } ?: fallbackSessionId
        val role = info.optString("role").ifBlank { "assistant" }
        val time = info.optJSONObject("time")
        val created = normalizeEpochMillis(
            time?.optLong("created") ?: info.optLong("createdAt", System.currentTimeMillis()),
        )
        val rawCompleted = time?.optLong("completed", 0L)?.takeIf { it > 0L }
            ?: info.optLong("completedAt", 0L).takeIf { it > 0L }
        val completed = rawCompleted?.let { normalizeEpochMillis(it) }
        val modelObj = info.optJSONObject("model")
        val providerId = info.optString("providerID").takeIf { it.isNotBlank() }
            ?: modelObj?.optString("providerID")?.takeIf { it.isNotBlank() }
        val modelId = info.optString("modelID").takeIf { it.isNotBlank() }
            ?: modelObj?.optString("modelID")?.takeIf { it.isNotBlank() }
        val agent = info.optString("agent").takeIf { it.isNotBlank() }
            ?: info.optString("mode").takeIf { it.isNotBlank() }
        val errorMsg = extractErrorMessage(info)
        val tokens = parseTokens(info, schema)
        val costUsd = extractDoubleOrNull(info, schema.costField, "cost")

        return OpenCodeMessage(
            id = id,
            sessionId = sessionId,
            role = role,
            createdAtMillis = created,
            completedAtMillis = completed,
            providerId = providerId,
            modelId = modelId,
            agent = agent,
            error = errorMsg,
            tokens = tokens,
            costUsd = costUsd,
        )
    }

    private fun parseTokens(
        info: JSONObject,
        schema: OpenCodeUsageSchemaFields,
    ): TokenUsage? {
        val tokensObj = info.optJSONObject(schema.tokensField)
            ?: info.optJSONObject("tokens")
            ?: info.optJSONObject("usage")
            ?: return null
        val input = extractLong(tokensObj, schema.inputField, "input", "inputTokens", "prompt_tokens")
        val output = extractLong(tokensObj, schema.outputField, "output", "outputTokens", "completion_tokens")
        val reasoning = extractLong(tokensObj, schema.reasoningField, "reasoning", "reasoningTokens")
        val cacheObj = tokensObj.optJSONObject(schema.cacheField)
            ?: tokensObj.optJSONObject("cache")
        val cacheRead = if (cacheObj != null) {
            extractLong(cacheObj, schema.cacheReadField, "read", "cacheRead")
        } else {
            extractLong(tokensObj, "cacheRead", "cache_read")
        }
        val cacheWrite = if (cacheObj != null) {
            extractLong(cacheObj, schema.cacheWriteField, "write", "cacheWrite")
        } else {
            extractLong(tokensObj, "cacheWrite", "cache_write")
        }
        val usage = TokenUsage(
            input = input.coerceAtLeast(0L),
            output = output.coerceAtLeast(0L),
            reasoning = reasoning.coerceAtLeast(0L),
            cacheRead = cacheRead.coerceAtLeast(0L),
            cacheWrite = cacheWrite.coerceAtLeast(0L),
        )
        return usage.takeIf { it.hasAny }
    }

    private fun extractLong(obj: JSONObject, vararg keys: String): Long {
        for (k in keys) {
            if (k.isBlank() || !obj.has(k) || obj.isNull(k)) continue
            val value = obj.optDouble(k, Double.NaN)
            if (!value.isNaN() && !value.isInfinite()) return value.toLong()
        }
        return 0L
    }

    private fun extractDoubleOrNull(obj: JSONObject, vararg keys: String): Double? {
        for (k in keys) {
            if (k.isBlank() || !obj.has(k) || obj.isNull(k)) continue
            val value = obj.optDouble(k, Double.NaN)
            if (!value.isNaN() && !value.isInfinite()) return value
        }
        return null
    }

    private fun extractErrorMessage(container: JSONObject): String? {
        val errorObj = container.optJSONObject("error")
        if (errorObj != null) {
            val dataObj = errorObj.optJSONObject("data")
            val fromData = dataObj?.optString("message")?.takeIf { it.isNotBlank() }
                ?: dataObj?.optString("error")?.takeIf { it.isNotBlank() }
            val fromObj = errorObj.optString("message").takeIf { it.isNotBlank() }
            val name = errorObj.optString("name").takeIf { it.isNotBlank() }
            val statusCode = dataObj?.optInt("statusCode", 0)?.takeIf { it > 0 }
                ?: errorObj.optInt("statusCode", 0).takeIf { it > 0 }
            val body = fromData ?: fromObj
            return when {
                body != null && statusCode != null && !body.contains(statusCode.toString()) ->
                    "HTTP $statusCode: $body"
                body != null && name != null && !body.startsWith(name, ignoreCase = true) && name != "Error" && name != "UnknownError" ->
                    "$name: $body"
                body != null -> body
                name != null -> name
                else -> errorObj.toString().takeIf { it != "{}" }
            }
        }
        val raw = container.opt("error")
        if (raw is String && raw.isNotBlank() && !raw.equals("null", ignoreCase = true)) {
            return raw.trim()
        }
        return container.optString("message").takeIf {
            it.isNotBlank() && !container.has("id") && !container.has("role")
        }
    }

    fun parsePart(
        obj: JSONObject,
        fallbackSessionId: String = "",
        fallbackMessageId: String = "",
    ): OpenCodePart? {
        val type = obj.optString("type").lowercase()
        val id = obj.optString("id").ifBlank { "${fallbackMessageId}_${type}_${obj.hashCode()}" }
        val sessionId = obj.optString("sessionID").takeIf { it.isNotBlank() } ?: fallbackSessionId
        val messageId = obj.optString("messageID").takeIf { it.isNotBlank() } ?: fallbackMessageId

        return when (type) {
            "text" -> OpenCodePart.Text(
                id = id,
                messageId = messageId,
                sessionId = sessionId,
                text = obj.optString("text"),
            )

            "reasoning" -> OpenCodePart.Reasoning(
                id = id,
                messageId = messageId,
                sessionId = sessionId,
                text = obj.optString("text"),
            )

            "tool" -> {
                val toolName = obj.optString("tool").ifBlank { "tool" }
                val callId = obj.optString("callID").ifBlank { id }
                val stateObj = obj.optJSONObject("state")
                val statusStr = stateObj?.optString("status").orEmpty().lowercase()
                val status = when (statusStr) {
                    "running" -> ToolCallStatus.RUNNING
                    "completed" -> ToolCallStatus.COMPLETED
                    "error" -> ToolCallStatus.ERROR
                    else -> ToolCallStatus.PENDING
                }
                val inputObj = stateObj?.optJSONObject("input")
                val rawInput = inputObj?.toString(2)?.take(4_000)
                val title = stateObj?.optString("title")?.takeIf { it.isNotBlank() }
                val output = stateObj?.optString("output")?.takeIf { it.isNotBlank() }
                val error = stateObj?.optString("error")?.takeIf { it.isNotBlank() }
                val filePath = extractFilePath(inputObj)
                val summary = title ?: extractSummary(inputObj, filePath)

                OpenCodePart.Tool(
                    id = id,
                    messageId = messageId,
                    sessionId = sessionId,
                    callId = callId,
                    toolName = toolName,
                    status = status,
                    title = title,
                    inputSummary = summary,
                    rawInput = rawInput,
                    output = output,
                    error = error,
                    filePath = filePath,
                )
            }

            "file" -> OpenCodePart.FileAttachment(
                id = id,
                messageId = messageId,
                sessionId = sessionId,
                filename = obj.optString("filename").ifBlank {
                    obj.optString("url").substringAfterLast('/')
                },
                url = obj.optString("url"),
                mime = obj.optString("mime").ifBlank { "text/plain" },
            )

            "patch" -> {
                val filesArr = obj.optJSONArray("files")
                val files = buildList {
                    if (filesArr != null) {
                        for (i in 0 until filesArr.length()) {
                            filesArr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }
                }
                OpenCodePart.Patch(
                    id = id,
                    messageId = messageId,
                    sessionId = sessionId,
                    files = files,
                )
            }

            else -> null
        }
    }

    fun parseProviders(
        rawJson: String,
        schema: OpenCodeUsageSchemaFields = OpenCodeUsageSchemaFields(),
    ): List<OpenCodeProvider> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return emptyList()
        val providersArray = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> {
                val root = JSONObject(trimmed)
                root.optJSONArray("providers")
                    ?: root.optJSONArray("all")
                    ?: JSONArray()
            }
            else -> JSONArray()
        }

        val connectedIds: Set<String>? = runCatching {
            val root = JSONObject(trimmed)
            val arr = root.optJSONArray("connected") ?: return@runCatching null
            buildSet<String> {
                for (i in 0 until arr.length()) add(arr.optString(i))
            }
        }.getOrNull()

        val result = mutableListOf<OpenCodeProvider>()
        for (i in 0 until providersArray.length()) {
            val pObj = providersArray.optJSONObject(i) ?: continue
            val providerId = pObj.optString("id").takeIf { it.isNotBlank() } ?: continue
            if (connectedIds != null && connectedIds.isNotEmpty() && providerId !in connectedIds) {
                continue
            }
            val providerName = pObj.optString("name").ifBlank { providerId }
            val models = mutableListOf<OpenCodeModel>()

            val modelsObj = pObj.optJSONObject("models")
            val modelsArr = pObj.optJSONArray("models")
            if (modelsObj != null) {
                val keys = modelsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val mObj = modelsObj.optJSONObject(key)
                    val mId = mObj?.optString("id")?.takeIf { it.isNotBlank() } ?: key
                    val mName = mObj?.optString("name")?.takeIf { it.isNotBlank() } ?: mId
                    models.add(buildParsedModel(providerId, providerName, mId, mName, mObj, schema))
                }
            } else if (modelsArr != null) {
                for (mIdx in 0 until modelsArr.length()) {
                    val mObj = modelsArr.optJSONObject(mIdx) ?: continue
                    val mId = mObj.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val mName = mObj.optString("name").ifBlank { mId }
                    models.add(buildParsedModel(providerId, providerName, mId, mName, mObj, schema))
                }
            }

            if (models.isNotEmpty()) {
                result.add(
                    OpenCodeProvider(
                        id = providerId,
                        name = providerName,
                        models = models.sortedBy { it.displayName.lowercase() },
                    ),
                )
            }
        }
        return result.sortedBy { it.name.lowercase() }
    }

    private fun buildParsedModel(
        providerId: String,
        providerName: String,
        modelId: String,
        displayName: String,
        mObj: JSONObject?,
        schema: OpenCodeUsageSchemaFields,
    ): OpenCodeModel {
        val limitObj = mObj?.optJSONObject(schema.modelLimitField)
            ?: mObj?.optJSONObject("limit")
        val contextLimit = limitObj
            ?.let { extractLong(it, schema.modelContextLimitField, "context", "contextWindow") }
            ?.takeIf { it > 0L }
            ?: mObj?.let { extractLong(it, "context_length", "contextWindow") }?.takeIf { it > 0L }
        val outputLimit = limitObj
            ?.let { extractLong(it, schema.modelOutputLimitField, "output", "maxOutput") }
            ?.takeIf { it > 0L }

        val costObj = mObj?.optJSONObject(schema.modelCostField)
            ?: mObj?.optJSONObject("cost")
        val inputCost = costObj?.let { extractDoubleOrNull(it, schema.modelCostInputField, "input") }
            ?.takeIf { it >= 0.0 }
        val outputCost = costObj?.let { extractDoubleOrNull(it, schema.modelCostOutputField, "output") }
            ?.takeIf { it >= 0.0 }

        return OpenCodeModel(
            providerId = providerId,
            providerName = providerName,
            modelId = modelId,
            displayName = displayName,
            contextLimit = contextLimit,
            outputLimit = outputLimit,
            inputCostPer1M = inputCost,
            outputCostPer1M = outputCost,
        )
    }

    fun parseAgents(rawJson: String): List<OpenCodeAgent> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return defaultAgents()
        val arr = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> {
                val root = JSONObject(trimmed)
                root.optJSONArray("agents") ?: root.optJSONArray("data") ?: JSONArray()
            }
            else -> JSONArray()
        }
        val agents = mutableListOf<OpenCodeAgent>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name").takeIf { it.isNotBlank() } ?: continue
            val mode = obj.optString("mode").ifBlank { "primary" }
            val hidden = obj.optBoolean("hidden", false)
            if (hidden || mode == "subagent") continue
            agents.add(
                OpenCodeAgent(
                    name = name,
                    description = obj.optString("description").orEmpty(),
                    mode = mode,
                ),
            )
        }
        return agents.ifEmpty { defaultAgents() }
    }

    fun parseSseEvent(
        rawData: String,
        schema: OpenCodeUsageSchemaFields = OpenCodeUsageSchemaFields(),
    ): OpenCodeEvent? {
        val root = runCatching { JSONObject(rawData) }.getOrNull() ?: return null
        val payload = root.optJSONObject("payload") ?: root
        val type = payload.optString("type").lowercase()
        val props = payload.optJSONObject("properties") ?: JSONObject()

        return when (type) {
            "session.created", "session.updated" -> {
                val info = props.optJSONObject("info") ?: props
                parseSession(info)?.let { OpenCodeEvent.SessionUpdated(it) }
            }

            "session.deleted" -> {
                val info = props.optJSONObject("info")
                val id = info?.optString("id")?.takeIf { it.isNotBlank() }
                    ?: props.optString("sessionID").takeIf { it.isNotBlank() }
                    ?: props.optString("id").takeIf { it.isNotBlank() }
                id?.let { OpenCodeEvent.SessionDeleted(it) }
            }

            "session.idle" -> {
                val id = props.optString("sessionID").takeIf { it.isNotBlank() }
                id?.let { OpenCodeEvent.SessionStatusChanged(it, isBusy = false) }
            }

            "session.status" -> {
                val id = props.optString("sessionID").takeIf { it.isNotBlank() } ?: return null
                val statusObj = props.optJSONObject("status")
                val statusType = statusObj?.optString("type")?.lowercase()
                    ?: props.optString("status").lowercase()
                val busy = statusType == "busy" || statusType == "retry" || statusType == "running"
                OpenCodeEvent.SessionStatusChanged(id, isBusy = busy)
            }

            "message.updated" -> {
                val info = props.optJSONObject("info") ?: props
                parseMessageInfo(info, schema = schema)?.let { OpenCodeEvent.MessageUpdated(it) }
            }

            "message.part.updated" -> {
                val partObj = props.optJSONObject("part") ?: return null
                val delta = if (props.has("delta") && !props.isNull("delta")) {
                    props.optString("delta")
                } else {
                    null
                }
                parsePart(partObj)?.let { OpenCodeEvent.PartUpdated(it, delta) }
            }

            "message.part.delta" -> {
                val sessionId = props.optString("sessionID")
                val messageId = props.optString("messageID")
                val partId = props.optString("partID")
                val field = props.optString("field").ifBlank { "text" }
                val delta = props.optString("delta")
                if (sessionId.isNotBlank() && messageId.isNotBlank() && partId.isNotBlank() && delta.isNotEmpty()) {
                    OpenCodeEvent.PartDelta(sessionId, messageId, partId, field, delta)
                } else {
                    null
                }
            }

            "permission.asked", "permission.updated" -> {
                val reqObj = props.optJSONObject("permission")
                    ?.takeIf { it.has("id") || it.has("requestID") }
                    ?: props.optJSONObject("info")
                    ?: props
                parsePermissionRequest(reqObj)?.let { OpenCodeEvent.PermissionAsked(it) }
            }

            "permission.replied" -> {
                val reqId = props.optString("requestID").takeIf { it.isNotBlank() }
                    ?: props.optString("permissionID").takeIf { it.isNotBlank() }
                    ?: props.optString("id").takeIf { it.isNotBlank() }
                val sessionId = props.optString("sessionID").takeIf { it.isNotBlank() }
                reqId?.let { OpenCodeEvent.PermissionReplied(requestId = it, sessionId = sessionId) }
            }

            "session.diff" -> {
                val sessionId = props.optString("sessionID").takeIf { it.isNotBlank() } ?: return null
                val diffArr = props.optJSONArray("diff") ?: props.optJSONArray("files") ?: JSONArray()
                val parsedDiffs = buildList {
                    for (i in 0 until diffArr.length()) {
                        val item = diffArr.optJSONObject(i) ?: continue
                        parseFileDiff(item)?.let(::add)
                    }
                }
                OpenCodeEvent.SessionDiffUpdated(sessionId = sessionId, diffs = parsedDiffs)
            }

            "session.error" -> {
                val sessionId = props.optString("sessionID").takeIf { it.isNotBlank() }
                    ?: props.optString("id").takeIf { it.isNotBlank() }
                val message = extractErrorMessage(props)
                    ?: "OpenCode 会话错误"
                OpenCodeEvent.SessionError(sessionId = sessionId, message = message)
            }

            else -> null
        }
    }

    fun parsePermissions(rawJson: String): List<OpenCodePermissionRequest> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return emptyList()
        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> {
                val obj = JSONObject(trimmed)
                obj.optJSONArray("permissions") ?: obj.optJSONArray("data") ?: JSONArray()
            }
            else -> JSONArray()
        }
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                parsePermissionRequest(item)?.let(::add)
            }
        }
    }

    fun parsePermissionRequest(obj: JSONObject): OpenCodePermissionRequest? {
        val id = obj.optString("id").takeIf { it.isNotBlank() }
            ?: obj.optString("requestID").takeIf { it.isNotBlank() }
            ?: obj.optString("permissionID").takeIf { it.isNotBlank() }
            ?: return null
        val sessionId = obj.optString("sessionID").orEmpty()
        val permissionType = obj.optString("permission").takeIf { it.isNotBlank() }
            ?: obj.optString("type").takeIf { it.isNotBlank() }
            ?: "action"
        val title = obj.optString("title").takeIf { it.isNotBlank() }
            ?: permissionType

        val patterns = buildList {
            val arr = obj.optJSONArray("patterns")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
            val single = obj.optString("pattern").takeIf { it.isNotBlank() }
            if (single != null && single !in this) add(single)
        }

        val alwaysPatterns = buildList {
            val arr = obj.optJSONArray("always")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }

        val metadata = obj.optJSONObject("metadata")
        val command = metadata?.optString("command")?.takeIf { it.isNotBlank() }
            ?: obj.optString("command").takeIf { it.isNotBlank() }
        val filePath = extractFilePath(metadata)
            ?: extractFilePath(obj)
            ?: patterns.firstOrNull { it.startsWith("/") }
        val details = metadata?.toString(2)?.take(2_000)?.takeIf { it != "{}" }

        return OpenCodePermissionRequest(
            id = id,
            sessionId = sessionId,
            permission = permissionType,
            title = title,
            patterns = patterns,
            command = command,
            filePath = filePath,
            details = details,
            alwaysPatterns = alwaysPatterns,
        )
    }

    fun parseFileDiffs(rawJson: String): List<OpenCodeFileDiff> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return emptyList()
        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> {
                val obj = JSONObject(trimmed)
                obj.optJSONArray("diff") ?: obj.optJSONArray("files") ?: obj.optJSONArray("data") ?: JSONArray()
            }
            else -> JSONArray()
        }
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                parseFileDiff(item)?.let(::add)
            }
        }
    }

    fun parseFileDiff(obj: JSONObject): OpenCodeFileDiff? {
        val file = obj.optString("file").takeIf { it.isNotBlank() }
            ?: obj.optString("path").takeIf { it.isNotBlank() }
            ?: obj.optString("filename").takeIf { it.isNotBlank() }
            ?: return null
        val before = obj.optString("before", "")
        val after = obj.optString("after", "")
        val rawPatch = obj.optString("diff").takeIf { it.isNotBlank() }
            ?: obj.optString("patch").takeIf { it.isNotBlank() }

        val lines = UnifiedDiffBuilder.buildDiffLines(
            before = before,
            after = after,
            rawPatch = rawPatch,
        )
        val computedAdditions = lines.count { it.type == DiffLineType.ADDED }
        val computedDeletions = lines.count { it.type == DiffLineType.REMOVED }
        val additions = if (obj.has("additions")) obj.optInt("additions", computedAdditions) else computedAdditions
        val deletions = if (obj.has("deletions")) obj.optInt("deletions", computedDeletions) else computedDeletions

        return OpenCodeFileDiff(
            file = file,
            before = before,
            after = after,
            additions = additions,
            deletions = deletions,
            rawPatch = rawPatch,
            lines = lines,
        )
    }

    fun defaultAgents(): List<OpenCodeAgent> = listOf(
        OpenCodeAgent(name = "build", description = "可完整访问文件编辑和命令执行"),
        OpenCodeAgent(name = "plan", description = "仅分析和规划，不修改文件"),
    )

    private fun extractFilePath(input: JSONObject?): String? {
        if (input == null) return null
        val keys = listOf("filePath", "path", "file_path", "filename", "target_file")
        for (k in keys) {
            val value = input.optString(k).trim()
            if (value.isNotEmpty() && !value.contains('\n')) return value
        }
        return null
    }

    private fun extractSummary(input: JSONObject?, filePath: String?): String? {
        if (filePath != null) return filePath
        if (input == null) return null
        val command = input.optString("command").takeIf { it.isNotBlank() }
        if (command != null) return command.take(120)
        val pattern = input.optString("pattern").takeIf { it.isNotBlank() }
        if (pattern != null) return pattern.take(100)
        val query = input.optString("query").takeIf { it.isNotBlank() }
        if (query != null) return query.take(100)
        return null
    }

    private fun normalizeEpochMillis(raw: Long): Long {
        if (raw <= 0L) return System.currentTimeMillis()
        return if (raw < 10_000_000_000L) raw * 1000L else raw
    }
}
