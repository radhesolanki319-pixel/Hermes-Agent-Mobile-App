package com.jarves.mh.genui

/**
 * Layer 7 — Event & Action Bridge (pure logic; `MainViewModel.handleGenUiAction` dispatches
 * through here and owns the side effects).
 *
 * The bridge turns an allowlisted action plus the message's form state into the follow-up
 * user message that re-enters the agent through `sendPrompt`. Returning `null` means "do not
 * send anything" — the ViewModel handles `open_url` / `copy_text` / a live runtime approval
 * natively instead. Payload content is only ever formatted as message text here; it is never
 * mapped to a shell command, an intent extra beyond the URL itself, or any other executable.
 */
object GenUiActionBridge {

    /** Effective value of every field in the tree: live state first, declared defaults next. */
    fun collectFormValues(payload: GenUiPayload, state: GenUiMessageState): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        fun walk(node: GenUiNode) {
            if (node.type in GenUiComponentTypes.FIELD_TYPES) {
                val id = node.id
                if (!id.isNullOrBlank()) values[id] = state.value(id, defaultValue(node))
            }
            node.children.forEach(::walk)
        }
        walk(payload.root)
        return values
    }

    /** The value a field contributes before the user touches it. */
    fun defaultValue(node: GenUiNode): String = when (node.type) {
        GenUiComponentTypes.INPUT, GenUiComponentTypes.TEXTAREA ->
            node.propText("value") ?: ""
        GenUiComponentTypes.RADIO_GROUP, GenUiComponentTypes.SELECT ->
            node.propText("selected") ?: node.propOptions("options").firstOrNull()?.first ?: ""
        GenUiComponentTypes.CHECKBOX ->
            (node.propBool("checked") ?: false).toString()
        GenUiComponentTypes.SLIDER ->
            formatGenUiNumber(node.propNumber("value") ?: node.propNumber("min") ?: 0.0)
        GenUiComponentTypes.TABS ->
            (node.propNumber("selected")?.toInt() ?: 0).toString()
        else -> ""
    }

    /**
     * Builds the follow-up message for the actions that re-enter the agent pipeline
     * (`submit_form`, `send_message`, `invoke_tool`). Returns null for every other type and
     * for actions missing their required payload (e.g. a `send_message` with no message).
     */
    fun formatFollowUp(
        payload: GenUiPayload,
        action: GenUiActionDef,
        state: GenUiMessageState,
    ): String? = when (action.type) {
        GenUiActionTypes.SUBMIT_FORM -> buildString {
            appendLine("[generative-ui response]")
            appendLine("message_id: ${payload.messageId ?: "unknown"}")
            appendLine("action: submit_form")
            val values = collectFormValues(payload, state)
            if (values.isEmpty()) {
                append("values: (none)")
            } else {
                appendLine("values:")
                values.entries.forEachIndexed { index, (id, value) ->
                    append("$id: $value")
                    if (index < values.size - 1) appendLine()
                }
            }
        }
        GenUiActionTypes.SEND_MESSAGE -> action.message?.trim()?.takeIf { it.isNotBlank() }
        GenUiActionTypes.INVOKE_TOOL -> {
            val tool = action.tool?.trim().orEmpty()
            if (tool.isBlank()) {
                null
            } else {
                buildString {
                    appendLine("[generative-ui response]")
                    appendLine("message_id: ${payload.messageId ?: "unknown"}")
                    appendLine("action: invoke_tool")
                    appendLine("tool: $tool")
                    if (action.params.isNotEmpty()) {
                        appendLine("params:")
                        action.params.entries.forEachIndexed { index, (key, value) ->
                            append("$key: ${renderValue(value)}")
                            if (index < action.params.size - 1) appendLine()
                        }
                    } else {
                        append("Please run this tool now.")
                    }
                }
            }
        }
        else -> null
    }

    /** Follow-up used when an approval card is answered but no runtime approval is pending. */
    fun formatApprovalFollowUp(
        payload: GenUiPayload,
        componentId: String,
        approved: Boolean,
    ): String = buildString {
        appendLine("[generative-ui response]")
        appendLine("message_id: ${payload.messageId ?: "unknown"}")
        appendLine("action: request_approval")
        appendLine("component: $componentId")
        append("decision: ${if (approved) "approved" else "rejected"}")
    }

    /** Only https URLs may leave the app from a payload (stricter than the audit's http+https). */
    fun isSafeHttpsUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.length > GenUiProtocol.MAX_STRING_LENGTH) return false
        val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return false
        return uri.scheme?.equals("https", ignoreCase = true) == true && !uri.host.isNullOrBlank()
    }

    private fun renderValue(value: GenUiValue): String = when (value) {
        is GenUiValue.Text -> value.value
        is GenUiValue.Number -> value.value.toString()
        is GenUiValue.Bool -> value.value.toString()
        is GenUiValue.TextList -> value.values.joinToString(", ")
        is GenUiValue.TextMatrix -> value.values.joinToString("; ") { row ->
            row.joinToString(", ")
        }
        is GenUiValue.ObjectList -> value.values.joinToString(", ") { entry ->
            entry.entries.joinToString(", ") { (key, item) -> "$key=${renderValue(item)}" }
        }
        is GenUiValue.Object -> value.values.entries.joinToString(", ") { (key, item) ->
            "$key=${renderValue(item)}"
        }
    }
}
