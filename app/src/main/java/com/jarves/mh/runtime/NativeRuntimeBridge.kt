package com.jarves.mh.runtime

import android.content.Context
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.ProviderProtocol
import com.jarves.mh.model.RiskLevel
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import com.jarves.mh.termux.TermuxEnvironment
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * The built-in agent (v50, AndroidHarness-style architecture): a native
 * ReAct loop that runs entirely inside the app process — no guest CLI, no
 * install, nothing to download. It talks to the active provider profile
 * over the OpenAI-compatible chat API with streaming, and executes tools
 * against the project workspace: file tools host-side on
 * `filesDir/workspaces/<projectId>`, the shell tool inside the app's
 * Termux environment where that same folder appears at /workspace.
 *
 * Loop contract (from the design doc): max [MAX_ITERATIONS] model turns,
 * tool timeout 120s default / 600s max, destructive shell commands go
 * through the shared approval gate, every turn is checkpointed so the
 * Changes tab can undo it.
 */
class NativeRuntimeBridge(
    private val context: Context,
    private val secretFor: (ProviderProfile) -> String?,
) : RuntimeBridge {
    private val installer = RuntimeInstaller(context)
    private val checkpoints = WorkspaceCheckpoints(context.filesDir, context)
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 256)
    override val events: Flow<RuntimeEvent> = eventBus
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val finishedSessions = ConcurrentHashMap.newKeySet<String>()
    private val pendingApprovals = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    @Volatile private var activeSessionId: String? = null
    @Volatile private var stopRequested = false
    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var activeProcess: Process? = null

    fun configureProjectRoot(projectId: String, rootPath: String) {
        checkpoints.configureProjectRoot(projectId, rootPath)
    }

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String {
        val sessionId = UUID.randomUUID().toString()
        activeSessionId = sessionId
        stopRequested = false
        scope.launch {
            runTurn(sessionId, projectId, projectSlug, prompt, conversationHistory, provider)
        }
        return sessionId
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {
        // Only surface the outcome when this tap actually resolved a pending
        // request — a late tap after the approval timeout must not paint an
        // "approved" row for a command that never ran.
        val deferred = pendingApprovals.remove(request.approvalId) ?: return
        deferred.complete(approved)
        eventBus.emit(
            if (approved) RuntimeEvent.ToolApproved(request.sessionId, request.approvalId)
            else RuntimeEvent.ToolRejected(request.sessionId, request.approvalId),
        )
    }

    override suspend fun stopSession(sessionId: String) {
        if (activeSessionId != sessionId) return
        stopRequested = true
        runCatching { activeConnection?.disconnect() }
        val process = activeProcess
        runCatching { process?.destroy() }
        pendingApprovals.values.forEach { it.complete(false) }
        pendingApprovals.clear()
        if (process != null) {
            // Grace-then-force, mirroring the Hermes bridge: a guest process
            // that defers SIGTERM must not keep writing to the workspace
            // after the turn is reported stopped.
            kotlinx.coroutines.delay(500)
            if (process.isAlive) runCatching { process.destroyForcibly() }
        }
        emitFailureOnce(sessionId, "Stopped by user")
    }

    override suspend fun stopActiveSession() {
        activeSessionId?.let { stopSession(it) }
    }

    // -- Changes / undo: same checkpoint machinery the CLI bridges use. --

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val checkpoint = checkpoints.checkpointDir(projectId)
        val backup = File(checkpoint, "project")
        val manifest = File(checkpoint, "changes.json")
        if (!backup.isDirectory || !manifest.isFile) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val paths = runCatching {
            val array = JSONArray(manifest.readText())
            (0 until array.length()).map(array::getString)
        }.getOrElse { return@withContext false }
            .filterNot { checkpoints.isInternalRuntimePath(it) }
        paths.forEach { path ->
            val target = checkpoints.safeWorkspaceFile(workspace, path)
            val original = checkpoints.safeWorkspaceFile(backup, path)
            if (original.isFile) {
                target.parentFile?.mkdirs()
                original.copyTo(target, overwrite = true)
            } else if (target.isFile) {
                target.delete()
            }
        }
        checkpoint.deleteRecursively()
        true
    }

    override suspend fun acceptLastChanges(projectId: String) {
        withContext(Dispatchers.IO) {
            checkpoints.checkpointDir(projectId).deleteRecursively()
        }
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> =
        withContext(Dispatchers.IO) {
            val workspace = checkpoints.ensureWorkspace(projectId)
            val paths = checkpoints.readChangedPaths(projectId)
                .filterNot { checkpoints.isInternalRuntimePath(it) }
            if (paths.isEmpty()) emptyList()
            else checkpoints.buildChangeDetails(projectId, workspace, paths)
        }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean =
        withContext(Dispatchers.IO) {
            if (checkpoints.isInternalRuntimePath(path) || path !in checkpoints.readChangedPaths(projectId)) {
                return@withContext false
            }
            val workspace = checkpoints.ensureWorkspace(projectId)
            val backup = File(checkpoints.checkpointDir(projectId), "project")
            val target = checkpoints.safeWorkspaceFile(workspace, path)
            val original = checkpoints.safeWorkspaceFile(backup, path)
            if (original.isFile) {
                target.parentFile?.mkdirs()
                original.copyTo(target, overwrite = true)
            } else if (target.isFile) {
                target.delete()
            }
            checkpoints.removeChangedPath(projectId, path)
            true
        }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean =
        withContext(Dispatchers.IO) {
            if (checkpoints.isInternalRuntimePath(path) || path !in checkpoints.readChangedPaths(projectId)) {
                return@withContext false
            }
            val workspace = checkpoints.ensureWorkspace(projectId)
            val backup = File(checkpoints.checkpointDir(projectId), "project")
            val current = checkpoints.safeWorkspaceFile(workspace, path)
            val baseline = checkpoints.safeWorkspaceFile(backup, path)
            if (current.isFile) {
                baseline.parentFile?.mkdirs()
                current.copyTo(baseline, overwrite = true)
            } else if (baseline.isFile) {
                baseline.delete()
            }
            checkpoints.removeChangedPath(projectId, path)
            true
        }

    private suspend fun emitCompletedOnce(sessionId: String) {
        if (finishedSessions.add(sessionId)) eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
    }

    private suspend fun emitFailureOnce(sessionId: String, reason: String) {
        if (finishedSessions.add(sessionId)) eventBus.emit(RuntimeEvent.SessionFailed(sessionId, reason))
    }

    // ------------------------------------------------------------------
    // Agent loop
    // ------------------------------------------------------------------

    private suspend fun runTurn(
        sessionId: String,
        projectId: String,
        projectSlug: String,
        prompt: String,
        history: List<ChatMessage>,
        provider: ProviderProfile,
    ) {
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        val workspace = runCatching { checkpoints.ensureWorkspace(projectId) }.getOrElse {
            emitFailureOnce(sessionId, "Workspace khul nahi paya: ${it.message}")
            return
        }
        val before = runCatching { checkpoints.snapshot(workspace) }.getOrDefault(emptyMap())
        runCatching { checkpoints.createCheckpoint(projectId, workspace) }
        // The terminal event is emitted ONLY at the very end: the view model
        // drops every event that arrives after SessionCompleted/SessionFailed,
        // so changed files must be reported first (the Hermes bridge orders
        // it the same way).
        var failure: String? = null
        try {
            val endpoint = chatEndpoint(provider)
            if (endpoint == null) {
                failure = "Yeh provider (protocol ${provider.kind.protocol}) abhi built-in agent ke saath supported nahi hai. " +
                    "Settings me OpenAI-compatible provider chuno — FreeLLM API, OpenRouter, DeepSeek, Kimi ya NVIDIA."
            } else {
                val key = secretFor(provider)?.takeIf { it.isNotBlank() }
                    ?: localServerPlaceholderKey(profile = provider)
                val guestWorkspacePath = "/workspace/$projectSlug"
                val messages = buildMessages(prompt, history, guestWorkspacePath)
                var iterations = 0
                var forcedFinalUsed = false
                loop@ while (true) {
                    if (stopRequested) {
                        failure = "Stopped by user"
                        break@loop
                    }
                    iterations++
                    // The last allowed round runs without tools so the turn
                    // ends with a summary instead of a hard loop-limit cut.
                    val finalRound = iterations >= MAX_ITERATIONS
                    val outcome = streamCompletion(
                        sessionId, endpoint, key, provider, messages,
                        includeTools = !finalRound,
                    )
                    if (stopRequested) {
                        failure = "Stopped by user"
                        break@loop
                    }
                    val calls = outcome.toolCalls
                    if (calls.isEmpty()) break@loop
                    messages.add(assistantMessage(outcome.text, calls))
                    for (call in calls) {
                        if (stopRequested) {
                            failure = "Stopped by user"
                            break@loop
                        }
                        val result = executeTool(sessionId, workspace, guestWorkspacePath, call)
                        messages.add(
                            JSONObject()
                                .put("role", "tool")
                                .put("tool_call_id", call.id)
                                .put("content", result),
                        )
                    }
                    if (finalRound) {
                        if (forcedFinalUsed) break@loop
                        // The model ignored the tools-off final round and
                        // asked for tools anyway; they ran above — give it
                        // exactly one more text-only round to summarize.
                        forcedFinalUsed = true
                    }
                }
            }
        } catch (t: Throwable) {
            failure = if (stopRequested) "Stopped by user" else friendlyError(t)
        }
        runCatching {
            val changed = checkpoints.changedFiles(workspace, before)
                .filterNot { checkpoints.isInternalRuntimePath(it) }
            if (changed.isNotEmpty()) {
                checkpoints.saveChangedPaths(projectId, changed)
                eventBus.emit(
                    RuntimeEvent.FilesChanged(
                        sessionId,
                        checkpoints.buildChangeDetails(projectId, workspace, changed),
                    ),
                )
            }
        }
        if (activeSessionId == sessionId) {
            activeConnection = null
            activeProcess = null
        }
        if (failure != null) emitFailureOnce(sessionId, failure)
        else emitCompletedOnce(sessionId)
    }

    private fun buildMessages(prompt: String, history: List<ChatMessage>, guestWorkspacePath: String): MutableList<JSONObject> {
        val messages = mutableListOf<JSONObject>()
        messages.add(JSONObject().put("role", "system").put("content", systemPrompt(guestWorkspacePath)))
        // MainViewModel's history already ends with the current user message;
        // the (brief-augmented) prompt is appended separately below — drop
        // the duplicate, like the other bridges do.
        history.dropLast(1).recentWithinCharacterBudget(HISTORY_CHAR_BUDGET).forEach { message ->
            if (message.text.isBlank()) return@forEach
            messages.add(
                JSONObject()
                    .put("role", if (message.fromUser) "user" else "assistant")
                    .put("content", message.text),
            )
        }
        messages.add(JSONObject().put("role", "user").put("content", prompt))
        return messages
    }

    private fun assistantMessage(text: String, calls: List<ToolCallAcc>?) =
        JSONObject().apply {
            put("role", "assistant")
            put("content", if (text.isEmpty() && calls != null) JSONObject.NULL else text)
            if (!calls.isNullOrEmpty()) {
                put(
                    "tool_calls",
                    JSONArray().apply {
                        calls.forEach { call ->
                            put(
                                JSONObject()
                                    .put("id", call.id)
                                    .put("type", "function")
                                    .put(
                                        "function",
                                        JSONObject()
                                            .put("name", call.name)
                                            .put("arguments", call.arguments.ifBlank { "{}" }),
                                    ),
                            )
                        }
                    },
                )
            }
        }

    private fun friendlyError(t: Throwable): String = when {
        t is ProviderHttpException -> t.friendly()
        t is java.net.SocketTimeoutException ->
            "Provider server ne jawab nahi diya (timeout). FreeLLM jaise free servers ko pehli baar jaghne me ~1 minute lag sakta hai — phir se bhejo."
        t is java.net.UnknownHostException || t is java.net.ConnectException ->
            "Provider server se connect nahi ho paya. Internet ya server URL check karo."
        else -> "Agent ruk gaya: ${t.message?.take(300) ?: t.javaClass.simpleName}"
    }

    /** OpenAI-compatible chat endpoint for the profile, or null when the protocol has none. */
    private fun chatEndpoint(profile: ProviderProfile): String? {
        val base = profile.resolvedBaseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return null
        return when (profile.kind.protocol) {
            ProviderProtocol.OPENAI_CHAT -> "$base/chat/completions"
            ProviderProtocol.OPENROUTER -> "$base/v1/chat/completions"
            ProviderProtocol.ANTHROPIC -> "$base/v1/chat/completions"
            ProviderProtocol.ANTHROPIC_GATEWAY -> when {
                base.endsWith("/anthropic") -> base.removeSuffix("/anthropic") + "/v1/chat/completions"
                base.endsWith("/v1") -> "$base/chat/completions"
                else -> "$base/v1/chat/completions"
            }
            ProviderProtocol.CLAUDE_LOGIN, ProviderProtocol.OPENAI_RESPONSES -> null
        }
    }

    private class ToolCallAcc(var id: String, var name: String) {
        val arguments = StringBuilder()
    }

    private class StreamOutcome(val text: String, val toolCalls: List<ToolCallAcc>)

    /**
     * One streaming chat completion. Emits AssistantDelta events as text
     * arrives and accumulates tool calls; returns the full outcome.
     */
    private suspend fun streamCompletion(
        sessionId: String,
        endpoint: String,
        key: String,
        provider: ProviderProfile,
        messages: List<JSONObject>,
        includeTools: Boolean = true,
    ): StreamOutcome {
        val body = JSONObject()
            .put("model", provider.model.ifBlank { provider.kind.defaultModel })
            .put("messages", JSONArray(messages))
            .put("stream", true)
            .put("max_tokens", MAX_TOKENS)
        if (includeTools) body.put("tools", TOOL_SCHEMAS)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer $key")
            if (provider.kind.protocol == ProviderProtocol.OPENROUTER) {
                setRequestProperty("HTTP-Referer", "https://github.com/radhesolanki319-pixel/Hermes-Agent-Mobile-App")
                setRequestProperty("X-Title", "Hermes Agent (built-in)")
            }
        }
        activeConnection = connection
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val errorBody = runCatching {
                    connection.errorStream?.bufferedReader()?.readText()?.take(2000)
                }.getOrNull().orEmpty()
                throw ProviderHttpException(code, providerErrorMessage(errorBody) ?: errorBody.take(300))
            }
            val text = StringBuilder()
            val calls = sortedMapOf<Int, ToolCallAcc>()
            val reader = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
            reader.use { buffered ->
                while (true) {
                    if (stopRequested) break
                    val line = buffered.readLine() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    if (!trimmed.startsWith("data:")) {
                        // A few servers ignore `stream` and return one JSON
                        // document — accept it as a complete response.
                        if (trimmed.startsWith("{")) {
                            parseWholeResponse(trimmed, text, calls)?.let { delta ->
                                eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, delta))
                            }
                        }
                        continue
                    }
                    val data = trimmed.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val delta = parseChunk(data, text, calls) ?: continue
                    if (delta.isNotEmpty()) {
                        eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, delta))
                    }
                }
            }
            return StreamOutcome(text.toString(), calls.values.toList())
        } finally {
            if (activeConnection === connection) activeConnection = null
            runCatching { connection.disconnect() }
        }
    }

    /** Parses one SSE chunk; returns the text delta to emit (possibly empty). */
    private fun parseChunk(data: String, text: StringBuilder, calls: MutableMap<Int, ToolCallAcc>): String? {
        val chunk = runCatching { JSONObject(data) }.getOrNull() ?: return null
        val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: return null
        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: return null
        var emitted = ""
        val content = delta.optString("content", "")
        if (content.isNotEmpty()) {
            text.append(content)
            emitted = content
        }
        val toolCalls = delta.optJSONArray("tool_calls") ?: return emitted
        for (i in 0 until toolCalls.length()) {
            val entry = toolCalls.optJSONObject(i) ?: continue
            val fn = entry.optJSONObject("function")
            val entryId = entry.optString("id", "")
            // Providers that omit `index` send one call per chunk; when the
            // current accumulator already holds a different call id, this
            // chunk starts a NEW call instead of gluing arguments together.
            val index = if (entry.has("index")) {
                entry.optInt("index", i)
            } else {
                val last = calls.entries.lastOrNull()
                if (last != null && entryId.isNotBlank() && last.value.id != entryId) calls.size
                else last?.key ?: i
            }
            val acc = calls.getOrPut(index) {
                ToolCallAcc(entryId.ifBlank { "call_$index" }, fn?.optString("name", "").orEmpty())
            }
            // Adopt id/name from later chunks when the first chunk lacked
            // them (or carried only a placeholder).
            if (entryId.isNotBlank() && (acc.id.isBlank() || acc.id.startsWith("call_"))) acc.id = entryId
            val name = fn?.optString("name", "").orEmpty()
            if (name.isNotBlank()) acc.name = name
            val args = fn?.optString("arguments", "").orEmpty()
            if (args.isNotEmpty()) acc.arguments.append(args)
        }
        return emitted
    }

    /** Non-streaming fallback: a whole chat-completion JSON in one line. */
    private fun parseWholeResponse(json: String, text: StringBuilder, calls: MutableMap<Int, ToolCallAcc>): String? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val message = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: return null
        val content = message.optString("content", "")
        if (content.isNotEmpty()) text.append(content)
        val toolCalls = message.optJSONArray("tool_calls")
        if (toolCalls != null) {
            for (i in 0 until toolCalls.length()) {
                val entry = toolCalls.optJSONObject(i) ?: continue
                val fn = entry.optJSONObject("function")
                val acc = ToolCallAcc(
                    entry.optString("id").ifBlank { "call_$i" },
                    fn?.optString("name", "").orEmpty(),
                )
                acc.arguments.append(fn?.optString("arguments", "").orEmpty())
                calls[i] = acc
            }
        }
        return content.ifEmpty { null }
    }

    private fun providerErrorMessage(body: String): String? = runCatching {
        val json = JSONObject(body)
        json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: json.optString("message").takeIf { it.isNotBlank() }
    }.getOrNull()

    private class ProviderHttpException(val code: Int, val providerMessage: String) :
        Exception("HTTP $code: $providerMessage") {
        fun friendly(): String = when (code) {
            401, 403 -> "Provider ne API key reject ki (HTTP $code). Settings me apni key check karo. $providerMessage"
            404 -> "Provider endpoint ya model nahi mila (HTTP 404). Settings me model/URL check karo. $providerMessage"
            429 -> "Provider rate limit (HTTP 429) — thoda ruk kar phir bhejo. $providerMessage"
            else -> "Provider error (HTTP $code): $providerMessage"
        }
    }

    // ------------------------------------------------------------------
    // Tools
    // ------------------------------------------------------------------

    private suspend fun executeTool(sessionId: String, workspace: File, guestWorkspacePath: String, call: ToolCallAcc): String {
        val args = runCatching { JSONObject(call.arguments.toString().ifBlank { "{}" }) }
            .getOrElse {
                return "Error: tool arguments were not valid JSON (${it.message}). Retry with a JSON object."
            }
        val preview = when (call.name) {
            "shell" -> args.optString("command").take(120)
            "read_file", "write_file", "edit_file", "list_dir" -> args.optString("path")
            "search_files" -> args.optString("query")
            "install_package" -> args.optString("packages").take(120)
            "http_fetch" -> args.optString("url").take(120)
            "git_status" -> "git status"
            "git_diff" -> args.optString("path").take(120)
            else -> call.name
        }
        eventBus.emit(RuntimeEvent.ToolStarted(sessionId, call.name, preview))
        val result = runCatching {
            when (call.name) {
                "read_file" -> toolReadFile(workspace, args)
                "write_file" -> toolWriteFile(workspace, args)
                "edit_file" -> toolEditFile(workspace, args)
                "list_dir" -> toolListDir(workspace, args)
                "search_files" -> toolSearchFiles(workspace, args)
                "shell" -> toolShell(sessionId, workspace, guestWorkspacePath, args)
                "install_package" -> toolInstallPackage(args)
                "http_fetch" -> toolHttpFetch(args)
                "git_status" -> runGuestShell(workspace, guestWorkspacePath, "git status --short --branch", 30)
                "git_diff" -> toolGitDiff(workspace, guestWorkspacePath, args)
                else -> "Error: unknown tool '${call.name}'."
            }
        }.getOrElse { "Error: ${it.message?.take(500) ?: it.javaClass.simpleName}" }
        eventBus.emit(
            RuntimeEvent.ToolCompleted(
                sessionId,
                call.name,
                result.lineSequence().firstOrNull()?.take(120).orEmpty(),
            ),
        )
        return result.take(MAX_TOOL_RESULT_CHARS)
    }

    private fun toolReadFile(workspace: File, args: JSONObject): String {
        val path = args.optString("path")
        if (path.isBlank()) return "Error: 'path' is required."
        val file = checkpoints.safeWorkspaceFile(workspace, path)
        if (!file.isFile) return "Error: file not found: $path"
        if (file.length() > MAX_READ_BYTES) return "Error: file too large to read (${file.length()} bytes)."
        val lines = file.readText().lines()
        val offset = args.optInt("offset", 1).coerceAtLeast(1)
        val limit = args.optInt("limit", 400).coerceIn(1, 2000)
        if (offset > lines.size) return "(file has ${lines.size} lines; offset $offset is past the end)"
        val slice = lines.subList(offset - 1, minOf(lines.size, offset - 1 + limit))
        return slice.mapIndexed { i, line -> "${offset + i}\t$line" }.joinToString("\n")
    }

    private fun toolWriteFile(workspace: File, args: JSONObject): String {
        val path = args.optString("path")
        val content = args.optString("content")
        if (path.isBlank()) return "Error: 'path' is required."
        val file = checkpoints.safeWorkspaceFile(workspace, path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return "Wrote ${content.toByteArray(Charsets.UTF_8).size} bytes to $path"
    }

    private fun toolEditFile(workspace: File, args: JSONObject): String {
        val path = args.optString("path")
        val oldText = args.optString("old_text")
        val newText = args.optString("new_text")
        if (path.isBlank() || oldText.isEmpty()) return "Error: 'path' and 'old_text' are required."
        val file = checkpoints.safeWorkspaceFile(workspace, path)
        if (!file.isFile) return "Error: file not found: $path"
        val original = file.readText()
        val index = original.indexOf(oldText)
        if (index < 0) return "Error: old_text not found in $path. Read the file again and copy the exact text."
        file.writeText(original.replaceRange(index, index + oldText.length, newText))
        return "Edited $path"
    }

    private fun toolListDir(workspace: File, args: JSONObject): String {
        val path = args.optString("path", ".")
        val dir = checkpoints.safeWorkspaceFile(workspace, path)
        if (!dir.isDirectory) return "Error: not a directory: $path"
        val entries = dir.listFiles().orEmpty()
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name })
            .take(200)
        if (entries.isEmpty()) return "(empty directory)"
        return entries.joinToString("\n") { entry ->
            if (entry.isDirectory) "${entry.name}/" else "${entry.name} (${entry.length()} B)"
        }
    }

    private fun toolSearchFiles(workspace: File, args: JSONObject): String {
        val query = args.optString("query")
        if (query.isBlank()) return "Error: 'query' is required."
        val sub = args.optString("path", ".")
        val root = checkpoints.safeWorkspaceFile(workspace, sub)
        if (!root.exists()) return "Error: path not found: $sub"
        val hits = mutableListOf<String>()
        val needle = query.lowercase()
        root.walkTopDown()
            .onEnter { dir -> dir.name !in SEARCH_SKIP_DIRS }
            .filter { it.isFile && it.length() <= MAX_SEARCH_FILE_BYTES }
            .forEach { file ->
                if (hits.size >= MAX_SEARCH_HITS) return@forEach
                val relative = file.relativeTo(workspace).path
                runCatching {
                    file.readLines().forEachIndexed { index, line ->
                        if (hits.size < MAX_SEARCH_HITS && line.lowercase().contains(needle)) {
                            hits.add("$relative:${index + 1}: ${line.trim().take(200)}")
                        }
                    }
                }
            }
        return if (hits.isEmpty()) "No matches for \"$query\"." else hits.joinToString("\n")
    }

    /**
     * Phase 2 (ported from the VM Claude Code draft, hardened): fetch a URL
     * and return readable text. The body read is capped so a huge page can
     * not OOM the app, script/style blocks are stripped before tags, and
     * literal private/loopback hosts are refused (light SSRF guard — the
     * full per-hop validation is in the Phase 2 plan).
     */
    private suspend fun toolHttpFetch(args: JSONObject): String = withContext(Dispatchers.IO) {
        val url = args.optString("url")
        if (url.isBlank()) return@withContext "Error: 'url' is required."
        val parsed = runCatching { URL(url) }.getOrElse { return@withContext "Error: invalid URL." }
        if (parsed.protocol != "http" && parsed.protocol != "https") {
            return@withContext "Error: only http/https URLs are supported."
        }
        val host = parsed.host.orEmpty().lowercase()
        if (host == "localhost" || host == "127.0.0.1" || host == "::1" ||
            host.startsWith("192.168.") || host.startsWith("10.") ||
            Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(host)
        ) {
            return@withContext "Error: private/loopback addresses are not fetched."
        }
        val maxChars = args.optInt("max_chars", 12_000).coerceIn(500, 48_000)
        val connection = (parsed.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
            setRequestProperty("Accept", "text/html,application/json,text/plain,*/*")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) return@withContext "Error: HTTP $code"
            val raw = StringBuilder()
            connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(8192)
                while (raw.length < maxChars * 4) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    raw.append(buffer, 0, read)
                }
            }
            val isHtml = connection.contentType?.contains("html", ignoreCase = true) == true ||
                raw.startsWith("<!DOCTYPE", ignoreCase = true) || raw.startsWith("<html", ignoreCase = true)
            val text = if (isHtml) {
                raw.toString()
                    .replace(Regex("(?is)<script.*?</script>"), " ")
                    .replace(Regex("(?is)<style.*?</style>"), " ")
                    .replace(Regex("<[^>]*>"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            } else {
                raw.toString()
            }
            text.take(maxChars)
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private suspend fun toolGitDiff(workspace: File, guestWorkspacePath: String, args: JSONObject): String {
        val path = args.optString("path").trim()
        // The path lands inside a bash -lc string: whitelist its characters
        // and single-quote it so a model-supplied value can not inject
        // extra commands.
        val suffix = when {
            path.isBlank() -> ""
            path.contains("..") || !Regex("""^[A-Za-z0-9._/-]+$""").matches(path) ->
                return "Error: invalid path for git_diff: $path"
            else -> " -- '$path'"
        }
        return runGuestShell(
            workspace,
            guestWorkspacePath,
            "git diff --stat$suffix; echo ---; git diff$suffix",
            30,
        )
    }

    private suspend fun toolShell(sessionId: String, workspace: File, guestWorkspacePath: String, args: JSONObject): String {
        val command = args.optString("command")
        if (command.isBlank()) return "Error: 'command' is required."
        if (isDestructive(command)) {
            val approvalId = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<Boolean>()
            pendingApprovals[approvalId] = deferred
            eventBus.emit(
                RuntimeEvent.ToolRequested(
                    sessionId,
                    ToolRequest(
                        approvalId = approvalId,
                        sessionId = sessionId,
                        toolName = "shell",
                        explanation = "Yeh command kuch delete ya change kar sakta hai. Chalau?",
                        commandPreview = command.take(500),
                        risk = RiskLevel.HIGH,
                    ),
                ),
            )
            val approved = withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { deferred.await() } ?: false
            pendingApprovals.remove(approvalId)
            if (!approved) return "User ne yeh command approve nahi kiya. Command nahi chalaya gaya."
        }
        val timeoutSec = args.optInt("timeout_sec", DEFAULT_SHELL_TIMEOUT_SEC)
            .coerceIn(5, MAX_SHELL_TIMEOUT_SEC)
        return runGuestShell(workspace, guestWorkspacePath, command, timeoutSec)
    }

    /**
     * Installs Termux packages into the app's own environment — the agent's
     * equivalent of running `pkg install` in Termux. The app's installer
     * resolves the dependency closure from the official repository index
     * and SHA-256-verifies every .deb, exactly like the first-run setup.
     */
    private suspend fun toolInstallPackage(args: JSONObject): String {
        val raw = args.optString("packages")
        val names = raw.split(Regex("[,\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (names.isEmpty()) return "Error: 'packages' is required (one or more Termux package names)."
        if (names.size > 20) return "Error: too many packages at once (max 20)."
        val termux = TermuxEnvironment(context)
        if (!termux.isInstalled()) {
            return "Error: Termux environment abhi installed nahi hai — pehle app ka setup pura karo."
        }
        val already = names.filter { termux.installedContains(it) }
        val wanted = names.filter { it !in already }
        if (wanted.isEmpty()) return "Already installed: ${names.joinToString(", ")}"
        val result = termux.install(wanted)
        return result.fold(
            onSuccess = {
                "Installed: ${wanted.joinToString(", ")}" +
                    if (already.isNotEmpty()) " (already installed: ${already.joinToString(", ")})" else ""
            },
            onFailure = { "Error: install fail hua: ${it.message?.take(400) ?: it.javaClass.simpleName}" },
        )
    }

    private fun isDestructive(command: String): Boolean =
        DESTRUCTIVE_PATTERNS.any { it.containsMatchIn(command) }

    /** Runs a bash command inside the Ubuntu guest; the project folder is
     *  bind-mounted at [guestWorkspacePath] (/workspace/<slug>, the same
     *  path the environment brief and the other bridges use). */
    private suspend fun runGuestShell(workspace: File, guestWorkspacePath: String, command: String, timeoutSec: Int): String =
        withContext(Dispatchers.IO) {
            val runtime = runCatching { installer.installedRuntime() }.getOrElse {
                return@withContext "Error: Termux environment installed nahi hai — pehle app ka setup pura karo."
            }
            val process = runCatching {
                installer.process(
                    proot = runtime.proot,
                    rootfs = runtime.rootfs,
                    workspace = workspace,
                    environment = emptyMap(),
                    guestCommand = listOf("/usr/bin/env", "bash", "-lc", command),
                    guestWorkspacePath = guestWorkspacePath,
                )
            }.getOrElse { return@withContext "Error: shell start nahi ho paya: ${it.message}" }
            activeProcess = process
            val native = process as? NativeSpawnProcess
            val collected = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutSec * 1000L
            var offset = 0L
            fun drain() {
                val file = native?.outputFile ?: return
                if (file.length() > offset) {
                    RandomAccessFile(file, "r").use { input ->
                        input.seek(offset)
                        val available = (input.length() - offset).coerceAtMost(256 * 1024).toInt()
                        if (available > 0) {
                            val bytes = ByteArray(available)
                            input.readFully(bytes)
                            offset += available
                            collected.append(bytes.toString(Charsets.UTF_8).replace('\r', '\n'))
                            if (collected.length > MAX_COLLECTED_OUTPUT) {
                                collected.delete(0, collected.length - MAX_COLLECTED_OUTPUT)
                            }
                        }
                    }
                }
            }
            try {
                // Keep draining while the process runs OR the output file
                // still has unread bytes — the native pump can lag behind
                // process exit, and breaking on !isAlive alone loses the
                // tail of fast commands.
                while (true) {
                    drain()
                    if (!process.isAlive && (native?.outputFile?.length() ?: 0L) <= offset) break
                    if (stopRequested) {
                        process.destroy()
                        Thread.sleep(500)
                        if (process.isAlive) runCatching { process.destroyForcibly() }
                        drain()
                        return@withContext "Command stopped by user.\n$collected"
                    }
                    if (System.currentTimeMillis() > deadline) {
                        process.destroy()
                        Thread.sleep(500)
                        if (process.isAlive) runCatching { process.destroyForcibly() }
                        drain()
                        return@withContext "Error: command timed out after ${timeoutSec}s.\nPartial output:\n$collected"
                    }
                    Thread.sleep(120)
                }
                drain()
                val exit = runCatching { process.exitValue() }.getOrDefault(-1)
                val output = collected.toString().trimEnd()
                if (output.isBlank()) "(no output, exit code $exit)"
                else "exit code $exit\n$output"
            } finally {
                if (activeProcess === process) activeProcess = null
            }
        }

    companion object {
        private const val MAX_ITERATIONS = 25
        private const val MAX_TOKENS = 8192
        private const val HISTORY_CHAR_BUDGET = 48_000
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 240_000
        private const val APPROVAL_TIMEOUT_MS = 300_000L
        private const val DEFAULT_SHELL_TIMEOUT_SEC = 120
        private const val MAX_SHELL_TIMEOUT_SEC = 600
        private const val MAX_READ_BYTES = 512 * 1024L
        private const val MAX_SEARCH_FILE_BYTES = 512 * 1024L
        private const val MAX_SEARCH_HITS = 100
        private const val MAX_TOOL_RESULT_CHARS = 16_000
        private const val MAX_COLLECTED_OUTPUT = 24_000

        private val SEARCH_SKIP_DIRS = setOf(".git", "node_modules", "build", ".gradle", ".cxx", "dist")

        private val DESTRUCTIVE_PATTERNS = listOf(
            // Only RECURSIVE delete asks for approval (v76, user order: the
            // agent gets full control like Claude Code in Termux — a plain
            // `rm file` just runs; `rm -rf` still asks first).
            Regex("""\brm\s+[^|;&]*-[a-zA-Z]*r"""),
            Regex("""\bmkfs\b"""),
            Regex("""\bdd\s"""),
            Regex("""\breboot\b"""),
            Regex("""\bshutdown\b"""),
            Regex("""\bpoweroff\b"""),
            Regex("""pm\s+uninstall"""),
            Regex("""\bchmod\s+-[a-zA-Z]*R"""),
            Regex("""\bchown\s+-[a-zA-Z]*R"""),
            Regex(""">\s*/dev/"""),
        )

        private fun systemPrompt(guestWorkspacePath: String) = """
You are the Sovereign App Agent built into this Android app on Boss's iQOO Neo 10 — operating in the autonomous style of Antigravity and Claude Code. You live on the user's phone and you have FULL control of this app's own Linux environment: a real Termux environment (Android/bionic) with bash, python, nodejs, git and more. You are NOT isolated to any single directory: the entire Termux filesystem (${'$'}PREFIX, ~, /sdcard/Download) and the current workspace $guestWorkspacePath are under your full control.

CORE DIRECTIVES & USER IDENTITY (PERMANENT & MANDATORY):
1. USER: The user is Boss (Radhe Solanki). ALWAYS address the user as 'Boss'.
2. LANGUAGE: STRICT HINGLISH ONLY (natural conversational Hindi in English/Roman alphabet + standard English technical terms). Never output pure English conversational responses or explanations. Never use Devanagari script. Speak naturally like a trusted partner (e.g., 'Haan Boss, maine check kar liya hai', 'Yeh bug fix ho gaya hai Boss', 'Ab sab ready hai Boss').
3. AUTONOMOUS PROBLEM SOLVER: When Boss reports any issue or bug, DO NOT ask unnecessary questions or dump raw stack traces. Take full ownership: read the files, find the root cause, edit the code with edit_file/write_file, run shell checks, and tell Boss plainly in simple Hinglish what was wrong and how you solved it.
4. Keep replies short, clear, and direct. Explain everything in simple terms so Boss understands immediately.

Your powers — full control of this app's environment:
- The shell runs bash in the app's Termux environment, starting in the workspace. You can go anywhere in the environment (cd ~, cd ${'$'}PREFIX), run any installed program, use pip and npm, clone repos, and run builds.
- If a program or library is missing, install it yourself with install_package (any package from the official Termux repository — e.g. clang, rust, ffmpeg, openssh, vim) instead of telling the user it is missing.
- The file tools (read_file, write_file, edit_file, list_dir, search_files) work on paths relative to the workspace root; use the shell for anything outside the workspace.
- Read files before editing them, prefer edit_file for changes, and verify important changes by reading back or running a quick shell check.
- Only truly destructive shell commands (rm -rf, mkfs, dd, reboot, pm uninstall) ask the user for approval first — everything else just runs.
- If a tool returns an error, read it, adjust, and retry once or twice; then tell the user plainly what is blocked.
"""

        private val TOOL_SCHEMAS: JSONArray = JSONArray().apply {
            put(
                toolSchema(
                    "read_file",
                    "Read a text file from the workspace with line numbers.",
                    JSONObject()
                        .put("path", stringProp("File path relative to the workspace root"))
                        .put("offset", intProp("1-based line to start from (default 1)"))
                        .put("limit", intProp("Max lines to return (default 400)")),
                    arrayOf("path"),
                ),
            )
            put(
                toolSchema(
                    "write_file",
                    "Write a whole file (creates folders as needed, overwrites).",
                    JSONObject()
                        .put("path", stringProp("File path relative to the workspace root"))
                        .put("content", stringProp("Complete file content")),
                    arrayOf("path", "content"),
                ),
            )
            put(
                toolSchema(
                    "edit_file",
                    "Replace the first exact occurrence of old_text with new_text in a file.",
                    JSONObject()
                        .put("path", stringProp("File path relative to the workspace root"))
                        .put("old_text", stringProp("Exact text to replace"))
                        .put("new_text", stringProp("Replacement text")),
                    arrayOf("path", "old_text", "new_text"),
                ),
            )
            put(
                toolSchema(
                    "list_dir",
                    "List a directory in the workspace.",
                    JSONObject().put("path", stringProp("Directory path relative to the workspace root (default '.')")),
                    arrayOf(),
                ),
            )
            put(
                toolSchema(
                    "search_files",
                    "Search file contents in the workspace (case-insensitive substring).",
                    JSONObject()
                        .put("query", stringProp("Text to search for"))
                        .put("path", stringProp("Sub-directory to search in (default '.')")),
                    arrayOf("query"),
                ),
            )
            put(
                toolSchema(
                    "shell",
                    "Run a bash command in the app's Termux environment (working directory = the project workspace). You have the full environment: cd anywhere, the Termux home and prefix, every installed program. Only truly destructive commands ask the user first.",
                    JSONObject()
                        .put("command", stringProp("The bash command to run"))
                        .put("timeout_sec", intProp("Timeout in seconds (default 120, max 600)")),
                    arrayOf("command"),
                ),
            )
            put(
                toolSchema(
                    "install_package",
                    "Install packages from the official Termux repository into the app's environment, with their dependencies (e.g. 'ffmpeg', 'clang', 'rust', 'openssh', 'vim', 'imagemagick'). Use this whenever a needed program is missing instead of giving up. Already-installed packages are skipped.",
                    JSONObject()
                        .put("packages", stringProp("One Termux package name, or several separated by spaces or commas")),
                    arrayOf("packages"),
                ),
            )
            put(
                toolSchema(
                    "http_fetch",
                    "Fetch a URL over HTTP(S) and return readable text (HTML is stripped to text). Use for docs, changelogs, raw files. Private/loopback addresses are refused.",
                    JSONObject()
                        .put("url", stringProp("The URL to fetch (http or https)"))
                        .put("max_chars", intProp("Max characters to return (default 12000)")),
                    arrayOf("url"),
                ),
            )
            put(
                toolSchema(
                    "git_status",
                    "Show the git status of the workspace (branch + short status).",
                    JSONObject(),
                    arrayOf(),
                ),
            )
            put(
                toolSchema(
                    "git_diff",
                    "Show the git diff of the workspace: a stat summary followed by the full diff. Pass path to limit it to one file.",
                    JSONObject()
                        .put("path", stringProp("Optional file path to limit the diff to")),
                    arrayOf(),
                ),
            )
        }

        private fun stringProp(description: String) =
            JSONObject().put("type", "string").put("description", description)

        private fun intProp(description: String) =
            JSONObject().put("type", "integer").put("description", description)

        private fun toolSchema(name: String, description: String, properties: JSONObject, required: Array<String>) =
            JSONObject()
                .put("type", "function")
                .put(
                    "function",
                    JSONObject()
                        .put("name", name)
                        .put("description", description)
                        .put(
                            "parameters",
                            JSONObject()
                                .put("type", "object")
                                .put("properties", properties)
                                .put("required", JSONArray(required.toList())),
                        ),
                )
    }
}
