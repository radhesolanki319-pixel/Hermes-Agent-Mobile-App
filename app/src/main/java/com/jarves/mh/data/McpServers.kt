package com.jarves.mh.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP Server configuration stored as JSON in SharedPreferences.
 * Each entry has: name, enabled, and either stdio (command + args + env) or remote (url + headers).
 *
 * Hermes config.yaml format for `mcp_servers` (unchanged in v0.23.2):
 *   mcp_servers:
 *     server-name:
 *       command: "npx"
 *       args: ["-y", "@modelcontextprotocol/server-github"]
 *       env:
 *         GITHUB_TOKEN: "${MCP_SERVERNAME_API_KEY}"
 *       enabled: true
 *       tools:
 *         include: ["tool1", "tool2"]   # or exclude
 *     another-server:
 *       url: "https://example.com/mcp"
 *       headers:
 *         Authorization: "Bearer ${MCP_ANOTHER_API_KEY}"
 *       enabled: true
 *       auth: "oauth"
 *       oauth:
 *         flow: "browser"
 *         client_id: "..."
 *         client_secret: "..."
 *
 * Claude Code expects .mcp.json at project root with same shape.
 */
data class McpServerConfig(
    val name: String,
    val enabled: Boolean = true,
    // stdio transport
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    // remote/http transport
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val auth: String? = null,        // "oauth" or null
    val oauth: Map<String, Any>? = null,
    // tool filtering (same as Hermes)
    val tools: Map<String, Any>? = null,
    // connection timeout
    val connectTimeout: Int? = null,
) {
    /** True if this is a stdio server (has command). */
    val isStdio: Boolean get() = command != null

    /** True if this is a remote/http server (has url). */
    val isRemote: Boolean get() = url != null

    /** Validate: must have either command or url, not both. */
    fun isValid(): Boolean {
        if (name.trim().isEmpty()) return false
        return (isStdio && !isRemote) || (isRemote && !isStdio)
    }

    /** Convert to JSON object for storage. */
    fun toJson(): JSONObject {
        val obj = JSONObject().apply {
            put("name", name)
            put("enabled", enabled)
            command?.let { put("command", it) }
            if (args.isNotEmpty()) put("args", JSONArray(args))
            if (env.isNotEmpty()) put("env", JSONObject(env))
            url?.let { put("url", it) }
            if (headers.isNotEmpty()) put("headers", JSONObject(headers))
            auth?.let { put("auth", it) }
            oauth?.let { put("oauth", JSONObject(it)) }
            tools?.let { put("tools", JSONObject(it)) }
            connectTimeout?.let { put("connect_timeout", it) }
        }
        return obj
    }

    companion object {
        /** Parse from JSON object. */
        fun fromJson(obj: JSONObject): McpServerConfig? {
            val name = obj.optString("name", "").trim()
            if (name.isEmpty()) return null
            return McpServerConfig(
                name = name,
                enabled = obj.optBoolean("enabled", true),
                command = obj.optString("command").takeIf { it.isNotBlank() },
                args = obj.optJSONArray("args")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf(String::isNotBlank) }
                } ?: emptyList(),
                env = if (obj.has("env") && !obj.isNull("env")) {
                    obj.getJSONObject("env").toMap().mapValues { it.value as String }
                } else emptyMap(),
                url = obj.optString("url").takeIf { it.isNotBlank() },
                headers = if (obj.has("headers") && !obj.isNull("headers")) {
                    obj.getJSONObject("headers").toMap().mapValues { it.value as String }
                } else emptyMap(),
                auth = obj.optString("auth").takeIf { it.isNotBlank() },
                oauth = if (obj.has("oauth") && !obj.isNull("oauth")) {
                    obj.getJSONObject("oauth").toMap()
                } else null,
                tools = if (obj.has("tools") && !obj.isNull("tools")) {
                    obj.getJSONObject("tools").toMap()
                } else null,
                connectTimeout = if (obj.has("connect_timeout") && !obj.isNull("connect_timeout")) {
                    obj.getInt("connect_timeout")
                } else null,
            )
        }
    }
}

/**
 * Preferences extension for MCP servers list.
 * Stored as a JSON array under key "mcp_servers_json".
 */
class McpServersPrefs(private val context: Context) {
    private val preferences = context.getSharedPreferences("pocket_preferences", Context.MODE_PRIVATE)

    companion object {
        private const val KEY = "mcp_servers_json"
    }

    /** Get all configured MCP servers. */
    fun getAll(): List<McpServerConfig> {
        val raw = preferences.getString(KEY, "[]") ?: "[]"
        return try {
            JSONArray(raw).toList().mapNotNull {
                if (it is JSONObject) McpServerConfig.fromJson(it) else null
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Save the entire list (replaces). */
    fun setAll(servers: List<McpServerConfig>) {
        val arr = JSONArray()
        servers.forEach { arr.put(it.toJson()) }
        preferences.edit().putString(KEY, arr.toString()).apply()
    }

    /** Add or update one server by name. */
    fun upsert(server: McpServerConfig) {
        val list = getAll().toMutableList()
        val idx = list.indexOfFirst { it.name == server.name }
        if (idx >= 0) list[idx] = server else list.add(server)
        setAll(list)
    }

    /** Remove a server by name. */
    fun remove(name: String) {
        val list = getAll().filter { it.name != name }
        setAll(list)
    }

    /** Toggle enabled flag. */
    fun toggle(name: String) {
        val list = getAll().toMutableList()
        val idx = list.indexOfFirst { it.name == name }
        if (idx >= 0) {
            val s = list[idx]
            list[idx] = s.copy(enabled = !s.enabled)
            setAll(list)
        }
    }

    /** Get only enabled servers. */
    fun getEnabled(): List<McpServerConfig> = getAll().filter { it.enabled && it.isValid() }


    /** Export for backup (as JSON string). */
    fun exportJson(): String = JSONArray(getAll().map { it.toJson() }).toString()

    /** Import from backup JSON string. */
    fun importJson(json: String) {
        try {
            val arr = JSONArray(json)
            val list = arr.toList().mapNotNull {
                if (it is JSONObject) McpServerConfig.fromJson(it) else null
            }
            setAll(list)
        } catch (e: Exception) {
            // ignore invalid backup
        }
    }
}

/** org.json array → Kotlin list (JSONObject.NULL becomes null). */
internal fun JSONArray.toList(): List<Any?> {
    val out = ArrayList<Any?>(length())
    for (i in 0 until length()) {
        val value = get(i)
        out += if (value === JSONObject.NULL) null else value
    }
    return out
}

/** org.json object → Kotlin map (nested objects/arrays converted recursively). */
internal fun JSONObject.toMap(): Map<String, Any> {
    val out = LinkedHashMap<String, Any>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = get(key)
        out[key] = when (value) {
            is JSONObject -> value.toMap()
            is JSONArray -> value.toList().filterNotNull()
            else -> value
        }
    }
    return out
}