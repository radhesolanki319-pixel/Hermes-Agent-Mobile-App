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
            val declaredId = obj.optString("id").trim().take(GenUiProtocol.MAX_ID_LENGTH)
            val id = when {
                declaredId.isNotEmpty() -> declaredId
                type in GenUiComponentTypes.ID_TYPES -> "auto_$nodeCount"
                else -> null
            }
            return GenUiNode(type = type, id = id, props = props, children = children)
        }

        /** Per-type prop checks; normalizes radio options into (value, label) objects. */
        private fun checkProps(type: String, props: MutableMap<String, GenUiValue>): Boolean {
            when (type) {
                GenUiComponentTypes.RADIO_GROUP -> {
                    val options = when (val raw = props["options"]) {
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
                    if (options.isEmpty() || options.size > GenUiProtocol.MAX_OPTIONS) {
                        failure = "radio_group needs 1..${GenUiProtocol.MAX_OPTIONS} options"
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
        return if (first is JSONObject) {
            val objects = mutableListOf<Map<String, GenUiValue>>()
            for (index in 0 until count) {
                val obj = array.optJSONObject(index) ?: continue
                objects += convertObject(obj)
            }
            GenUiValue.ObjectList(objects)
        } else {
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

    private fun JSONObject.optTrimmedString(name: String): String? =
        optString(name).trim().take(GenUiProtocol.MAX_STRING_LENGTH).takeIf { it.isNotBlank() }

    private val TOP_LEVEL_PROPS = listOf(
        "text", "code", "label", "title", "value", "placeholder", "language",
        "options", "selected", "checked", "action",
    )
}
