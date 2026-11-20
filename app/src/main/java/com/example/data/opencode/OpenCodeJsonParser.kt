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
import com.example.domain.model.ToolCallStatus
import org.json.JSONArray
import org.json.JSONObject

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
        )
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

    fun parseMessages(rawJson: String, fallbackSessionId: String): List<OpenCodeMessage> {
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
            parseMessageWrapper(item, fallbackSessionId)?.let(list::add)
        }
        return list.sortedBy { it.createdAtMillis }
    }

    fun parseMessageWrapper(obj: JSONObject, fallbackSessionId: String): OpenCodeMessage? {
        val infoObj = obj.optJSONObject("info") ?: obj
        val base = parseMessageInfo(infoObj, fallbackSessionId) ?: return null
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

    fun parseMessageInfo(info: JSONObject, fallbackSessionId: String = ""): OpenCodeMessage? {
        val id = info.optString("id").takeIf { it.isNotBlank() } ?: return null
        val sessionId = info.optString("sessionID").takeIf { it.isNotBlank() } ?: fallbackSessionId
        val role = info.optString("role").ifBlank { "assistant" }
        val time = info.optJSONObject("time")
        val created = normalizeEpochMillis(
            time?.optLong("created") ?: info.optLong("createdAt", System.currentTimeMillis()),
        )
        val modelObj = info.optJSONObject("model")
        val providerId = info.optString("providerID").takeIf { it.isNotBlank() }
            ?: modelObj?.optString("providerID")?.takeIf { it.isNotBlank() }
        val modelId = info.optString("modelID").takeIf { it.isNotBlank() }
            ?: modelObj?.optString("modelID")?.takeIf { it.isNotBlank() }
        val agent = info.optString("agent").takeIf { it.isNotBlank() }
            ?: info.optString("mode").takeIf { it.isNotBlank() }
        val errorObj = info.optJSONObject("error")
        val errorMsg = errorObj?.optJSONObject("data")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: errorObj?.optString("message")?.takeIf { it.isNotBlank() }
            ?: errorObj?.optString("name")?.takeIf { it.isNotBlank() }

        return OpenCodeMessage(
            id = id,
            sessionId = sessionId,
            role = role,
            createdAtMillis = created,
            providerId = providerId,
            modelId = modelId,
            agent = agent,
            error = errorMsg,
        )
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

    fun parseProviders(rawJson: String): List<OpenCodeProvider> {
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
                    models.add(
                        OpenCodeModel(
                            providerId = providerId,
                            providerName = providerName,
                            modelId = mId,
                            displayName = mName,
                        ),
                    )
                }
            } else if (modelsArr != null) {
                for (mIdx in 0 until modelsArr.length()) {
                    val mObj = modelsArr.optJSONObject(mIdx) ?: continue
                    val mId = mObj.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val mName = mObj.optString("name").ifBlank { mId }
                    models.add(
                        OpenCodeModel(
                            providerId = providerId,
                            providerName = providerName,
                            modelId = mId,
                            displayName = mName,
                        ),
                    )
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

    fun parseSseEvent(rawData: String): OpenCodeEvent? {
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
                parseMessageInfo(info)?.let { OpenCodeEvent.MessageUpdated(it) }
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
        OpenCodeAgent(name = "build", description = "Полный доступ к правке файлов и командам"),
        OpenCodeAgent(name = "plan", description = "Анализ и планирование без изменения файлов"),
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
