package com.jarves.mh.genui

import org.json.JSONArray
import org.json.JSONObject

/**
 * Layers 3 + 4 — Schema Validator and Component Registry.
 *
 * Converts raw payload JSON into a typed [GenUiPayload] tree, enforcing the component
 * allowlist ([GenUiComponentTypes]), the action allowlist ([GenUiActionTypes]) and the size
 * caps from [GenUiProtocol]. Non-allowlisted *actions* are dropped silently (an agent asking
 * for an unknown action must not break the whole UI); anything structurally wrong with the
 * component tree rejects the payload as a whole and the caller falls back to plain text.
 *
 * This function never throws and never interprets payload content as code — the renderer it
 * feeds dispatches through a closed `when`, so arbitrary JS/Python/HTML/shell in a payload
 * can only ever appear as inert text.
 */
object GenerativeUiValidator {

    sealed interface ValidationResult {
        data class Valid(val payload: GenUiPayload) : ValidationResult
        data class Invalid(val reason: String) : ValidationResult
    }

    fun validate(payloadJson: String): ValidationResult {
        if (payloadJson.toByteArray(Charsets.UTF_8).size > GenUiProtocol.MAX_PAYLOAD_BYTES) {
            return ValidationResult.Invalid("payload too large")
        }
        val root = runCatching { JSONObject(payloadJson) }.getOrNull()
            ?: return ValidationResult.Invalid("payload is not a JSON object")
        if (root.optString("protocol") != GenUiProtocol.PROTOCOL) {
            return ValidationResult.Invalid("protocol mismatch")
        }
        val version = root.optString("version")
        if (version != GenUiProtocol.VERSION && !version.startsWith("1.")) {
            return ValidationResult.Invalid("unsupported version: $version")
        }
        val ui = root.optJSONObject("ui")
            ?: return ValidationResult.Invalid("missing ui tree")
        val builder = TreeBuilder()
        val tree = builder.build(ui, depth = 1)
            ?: return ValidationResult.Invalid("invalid component tree: ${builder.failure ?: "unknown"}")
        return ValidationResult.Valid(
            GenUiPayload(
                messageId = root.optString("message_id").takeIf { it.isNotBlank() }
                    ?.take(GenUiProtocol.MAX_STRING_LENGTH),
                root = tree,
                actions = parseActions(root.optJSONArray("actions")),
            ),
        )
    }

    private fun parseActions(array: JSONArray?): List<GenUiActionDef> {
        if (array == null) return emptyList()
        val actions = mutableListOf<GenUiActionDef>()
        val count = minOf(array.length(), GenUiProtocol.MAX_ACTIONS)
        for (index in 0 until count) {
            val obj = array.optJSONObject(index) ?: continue
            val type = obj.optString("type").trim()
            if (type !in GenUiActionTypes.ALL) continue // dropped, not fatal
            actions += GenUiActionDef(
                id = obj.optString("id").ifBlank { "action_$index" }.take(GenUiProtocol.MAX_ID_LENGTH),
                type = type,
                label = obj.optTrimmedString("label"),
                message = obj.optTrimmedString("message"),
                url = obj.optTrimmedString("url"),
                text = obj.optTrimmedString("text"),
                tool = obj.optTrimmedString("tool"),
                params = obj.optJSONObject("params")?.let { convertObject(it) } ?: emptyMap(),
            )
        }
        return actions
    }

    private class TreeBuilder {
        var nodeCount = 0
            private set
        var failure: String? = null
            private set

        fun build(obj: JSONObject, depth: Int): GenUiNode? {
            if (failure != null) return null
            if (depth > GenUiProtocol.MAX_DEPTH) {
                failure = "tree deeper than ${GenUiProtocol.MAX_DEPTH}"
                return null
            }
            nodeCount++
            if (nodeCount > GenUiProtocol.MAX_NODES) {
                failure = "more than ${GenUiProtocol.MAX_NODES} nodes"
                return null
            }
            val type = obj.optString("type").trim()
            if (type !in GenUiComponentTypes.ALL) {
                failure = "unknown component type: $type"
                return null
            }
            val props = mutableMapOf<String, GenUiValue>()
            obj.optJSONObject("props")?.let { propsJson ->
                propsJson.keys().forEach { key ->
                    convertValue(propsJson.opt(key), depth = 0)?.let { props[key] = it }
                }
            }
            // Lenient wire format: a few well-known props may also sit at node top level.
            for (key in TOP_LEVEL_PROPS) {
                if (key !in props && obj.has(key)) {
                    convertValue(obj.opt(key), depth = 0)?.let { props[key] = it }
                }
            }
            if (!checkProps(type, props)) return null
            val children = mutableListOf<GenUiNode>()
            if (type in GenUiComponentTypes.CONTAINERS) {

                val childrenJson = obj.optJSONArray("children")
                if (childrenJson != null) {
                    // Reject rather than truncate: silently dropping children could
                    // remove a form's submit button or an approval card.
                    if (childrenJson.length() > GenUiProtocol.MAX_CHILDREN) {
                        failure = "more than ${GenUiProtocol.MAX_CHILDREN} children on $type"
                        return null
                    }
                    val count = childrenJson.length()
                    for (index in 0 until count) {
                        val childObj = childrenJson.optJSONObject(index)
                        if (childObj == null) {
                            failure = "child $index of $type is not an object"
                            return null
                        }
                        val child = build(childObj, depth + 1) ?: return null
                        children += child
                    }
                }
            }
            if (type == GenUiComponentTypes.TABS) {
                // Structure: exactly one child subtree per declared tab label.
                val labelCount = (props["labels"] as? GenUiValue.TextList)?.values?.size ?: 0
                if (children.size != labelCount) {
                    failure = "tabs has $labelCount labels but ${children.size} children"
                    return null
                }
            }
            val declaredId = obj.optString("id").trim().take(GenUiProtocol.MAX_ID_LENGTH)
            val id = when {
                declaredId.isNotEmpty() -> declaredId
                type in GenUiComponentTypes.ID_TYPES -> "auto_$nodeCount"
                else -> null
            }
            return GenUiNode(type = type, id = id, props = props, children = children)
        }

        /**
         * Options of a `radio_group` / `select`, normalized to (value, label) objects.
         * Entries without a text `value` are dropped; a wrong-typed prop yields no options.
         */
        private fun normalizeOptions(raw: GenUiValue?): List<Map<String, GenUiValue>> =
            when (raw) {
                is GenUiValue.TextList -> raw.values.map { value ->
                    mapOf(
                        "value" to GenUiValue.Text(value),
                        "label" to GenUiValue.Text(value),
                    )
                }
                is GenUiValue.ObjectList -> raw.values.filter { entry ->
                    entry["value"] is GenUiValue.Text
                }.map { entry ->
                    val value = (entry["value"] as GenUiValue.Text).value
                    val label = (entry["label"] as? GenUiValue.Text)?.value ?: value
                    mapOf(
                        "value" to GenUiValue.Text(value),
                        "label" to GenUiValue.Text(label),
                    )
                }
                else -> emptyList()
            }

        /** Rewrites a tone/status prop to its allowlisted value, or [fallback] when invalid. */
        private fun normalizeEnumProp(
            props: MutableMap<String, GenUiValue>,
            key: String,
            allowed: Set<String>,
            fallback: String,
        ) {
            val raw = (props[key] as? GenUiValue.Text)?.value?.trim()?.lowercase()
            props[key] = GenUiValue.Text(raw?.takeIf { it in allowed } ?: fallback)
        }

        /** Per-type prop checks; normalizes options, enums and table rows for the renderer. */
        private fun checkProps(type: String, props: MutableMap<String, GenUiValue>): Boolean {
            when (type) {
                GenUiComponentTypes.RADIO_GROUP, GenUiComponentTypes.SELECT -> {
                    val options = normalizeOptions(props["options"])
                    if (options.isEmpty() || options.size > GenUiProtocol.MAX_OPTIONS) {
                        failure = "$type needs 1..${GenUiProtocol.MAX_OPTIONS} options"
                        return false
                    }
                    props["options"] = GenUiValue.ObjectList(options)
                }
                GenUiComponentTypes.PROGRESS -> {
                    for (key in listOf("value", "percent")) {
                        val value = props[key]
                        if (value != null && value !is GenUiValue.Number) {
                            failure = "progress.$key must be a number"
                            return false
                        }
                    }
                }
                GenUiComponentTypes.SLIDER -> {
                    for (key in listOf("min", "max", "step", "value")) {
                        val value = props[key]
                        if (value != null && value !is GenUiValue.Number) {
                            failure = "slider.$key must be a number"
                            return false
                        }
                    }
                    val min = (props["min"] as? GenUiValue.Number)?.value ?: 0.0
                    val max = (props["max"] as? GenUiValue.Number)?.value ?: 100.0
                    if (max <= min) {
                        failure = "slider max must be greater than min"
                        return false
                    }
                    val step = (props["step"] as? GenUiValue.Number)?.value
                    if (step != null && step <= 0.0) {
                        failure = "slider.step must be positive"
                        return false
                    }
                }
                GenUiComponentTypes.TABS -> {
                    val labels = props["labels"] as? GenUiValue.TextList
                    if (labels == null || labels.values.isEmpty() ||
                        labels.values.size > GenUiProtocol.MAX_TABS
                    ) {
                        failure = "tabs needs 1..${GenUiProtocol.MAX_TABS} labels"
                        return false
                    }
                }
                GenUiComponentTypes.IMAGE -> {
                    // Same rule as the open_url action: https with a real host, or no image.
                    val url = (props["url"] as? GenUiValue.Text)?.value
                    if (url.isNullOrBlank()) {
                        failure = "image needs a url"
                        return false
                    }
                    if (!GenUiActionBridge.isSafeHttpsUrl(url)) {
                        failure = "image url must be https"
                        return false
                    }
                }
                GenUiComponentTypes.BADGE ->
                    normalizeEnumProp(props, "tone", GenUiPropValues.BADGE_TONES, "neutral")
                GenUiComponentTypes.ALERT ->
                    normalizeEnumProp(props, "tone", GenUiPropValues.ALERT_TONES, "info")
                GenUiComponentTypes.TABLE -> {
                    val headers = (props["headers"] as? GenUiValue.TextList)?.values
                    if (headers.isNullOrEmpty()) {
                        failure = "table needs headers"
                        return false
                    }
                    if (headers.size > GenUiProtocol.MAX_TABLE_COLUMNS) {
                        failure = "table has more than ${GenUiProtocol.MAX_TABLE_COLUMNS} columns"
                        return false
                    }
                    val rows = props["rows"]
                    if (rows != null) {
                        if (rows !is GenUiValue.TextMatrix) {
                            failure = "table rows must be a list of string lists"
                            return false
                        }
                        if (rows.values.size > GenUiProtocol.MAX_LIST_ITEMS) {
                            failure = "table has more than ${GenUiProtocol.MAX_LIST_ITEMS} rows"
                            return false
                        }
                        // A row wider than the header would silently lose cells — reject.
                        // Shorter rows are padded with blanks (lossless).
                        if (rows.values.any { it.size > headers.size }) {
                            failure = "table row wider than its headers"
                            return false
                        }
                        props["rows"] = GenUiValue.TextMatrix(
                            rows.values.map { row -> row + List(headers.size - row.size) { "" } },
                        )
                    }
                }
                GenUiComponentTypes.TOOL_CALL_CARD -> {
                    val tool = (props["tool"] as? GenUiValue.Text)?.value
                    if (tool.isNullOrBlank()) {
                        failure = "tool_call_card needs a tool name"
                        return false
                    }
                    normalizeEnumProp(props, "status", GenUiPropValues.TOOL_STATUSES, "running")
                }
                GenUiComponentTypes.FILE_PREVIEW -> {
                    val name = (props["name"] as? GenUiValue.Text)?.value
                    if (name.isNullOrBlank()) {
                        failure = "file_preview needs a name"
                        return false
                    }
                }
                GenUiComponentTypes.TIMELINE -> {
                    val steps = (props["steps"] as? GenUiValue.ObjectList)?.values
                    if (steps == null) {
                        failure = "timeline needs steps"
                        return false
                    }
                    val normalized = steps.mapNotNull { entry ->
                        val title = (entry["title"] as? GenUiValue.Text)?.value
                            ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val status = (entry["status"] as? GenUiValue.Text)?.value
                            ?.trim()?.lowercase()
                        val step = mutableMapOf<String, GenUiValue>()
                        step["title"] = GenUiValue.Text(title)
                        (entry["text"] as? GenUiValue.Text)?.let { step["text"] = it }
                        step["status"] = GenUiValue.Text(
                            status?.takeIf { it in GenUiPropValues.TIMELINE_STATUSES }
                                ?: "upcoming",
                        )
                        step
                    }
                    if (normalized.isEmpty() || normalized.size > GenUiProtocol.MAX_LIST_ITEMS) {
                        failure = "timeline needs 1..${GenUiProtocol.MAX_LIST_ITEMS} titled steps"
                        return false
                    }
                    props["steps"] = GenUiValue.ObjectList(normalized)
                }
            }
            return true
        }
    }

    private fun convertObject(obj: JSONObject): Map<String, GenUiValue> {
        val result = mutableMapOf<String, GenUiValue>()
        obj.keys().forEach { key -> convertValue(obj.opt(key), depth = 1)?.let { result[key] = it } }
        return result
    }

    /** Converts one JSON value; returns null for values the protocol does not carry. */
    private fun convertValue(value: Any?, depth: Int): GenUiValue? = when (value) {
        null, JSONObject.NULL -> null
        is String -> GenUiValue.Text(value.take(GenUiProtocol.MAX_STRING_LENGTH))
        is Boolean -> GenUiValue.Bool(value)
        is Number -> GenUiValue.Number(value.toDouble())
        is JSONArray -> convertArray(value)
        is JSONObject -> if (depth < 1) GenUiValue.Object(convertObject(value)) else null
        else -> null
    }

    private fun convertArray(array: JSONArray): GenUiValue? {
        val count = minOf(array.length(), GenUiProtocol.MAX_LIST_ITEMS)
        if (count == 0) return GenUiValue.TextList(emptyList())
        val first = array.opt(0)
        return when {
            first is JSONObject -> {
                val objects = mutableListOf<Map<String, GenUiValue>>()
                for (index in 0 until count) {
                    val obj = array.optJSONObject(index) ?: continue
                    objects += convertObject(obj)
                }
                GenUiValue.ObjectList(objects)
            }
            first is JSONArray -> {
                // List of string lists (table rows). Non-scalar cells are dropped.
                val rows = mutableListOf<List<String>>()
                for (index in 0 until count) {
                    val rowArray = array.optJSONArray(index) ?: continue
                    val row = mutableListOf<String>()
                    val rowCount = minOf(rowArray.length(), GenUiProtocol.MAX_LIST_ITEMS)
                    for (cell in 0 until rowCount) {
                        when (val item = rowArray.opt(cell)) {
                            is String -> row += item.take(GenUiProtocol.MAX_STRING_LENGTH)
                            is Number, is Boolean -> row += item.toString()
                        }
                    }
                    rows += row
                }
                GenUiValue.TextMatrix(rows)
            }
            else -> {
                val strings = mutableListOf<String>()
                for (index in 0 until count) {
                    when (val item = array.opt(index)) {
                        is String -> strings += item.take(GenUiProtocol.MAX_STRING_LENGTH)
                        is Number, is Boolean -> strings += item.toString()
                    }
                }
                GenUiValue.TextList(strings)
            }
        }
    }

    private fun JSONObject.optTrimmedString(name: String): String? =
        optString(name).trim().take(GenUiProtocol.MAX_STRING_LENGTH).takeIf { it.isNotBlank() }

    private val TOP_LEVEL_PROPS = listOf(
        "text", "code", "label", "title", "value", "placeholder", "language",
        "options", "selected", "checked", "action",
        "url", "alt", "caption", "tone", "min", "max", "step",
        "labels", "headers", "rows", "steps", "tool", "status", "summary",
        "name", "size",
    )
}
