package com.jarves.mh.genui

import org.json.JSONObject

/**
 * Layer 6 — per-message UI state for one rendered generative-UI payload.
 *
 * State is keyed by chat `messageId` (in `AppUiState.genUiStates`) and, inside a message, by
 * component `id`, so one message's form can never leak into another's. Field values are plain
 * strings: text for inputs, the selected option value for radio groups, `"true"` / `"false"`
 * for checkboxes. A submitted form stays [submitted] forever (it renders disabled with its
 * values, including after a restart — the state is persisted with the transcript).
 */
data class GenUiMessageState(
    val values: Map<String, String> = emptyMap(),
    val submitted: Boolean = false,
    val loading: Boolean = false,
    val approvalDecisions: Map<String, Boolean> = emptyMap(),
) {
    /** Current value of a field, or [default] (usually the payload's declared default). */
    fun value(componentId: String, default: String): String = values[componentId] ?: default

    fun withValue(componentId: String, value: String): GenUiMessageState =
        copy(values = values + (componentId to value))

    fun withDecision(componentId: String, approved: Boolean): GenUiMessageState =
        copy(approvalDecisions = approvalDecisions + (componentId to approved))
}

/** org.json serialization for [GenUiMessageState], used by transcript persistence. */
object GenUiStateCodec {

    fun toJson(state: GenUiMessageState): JSONObject = JSONObject().apply {
        put("values", JSONObject(state.values))
        put("submitted", state.submitted)
        put("loading", state.loading)
        put("approvalDecisions", JSONObject().apply {
            state.approvalDecisions.forEach { (id, approved) -> put(id, approved) }
        })
    }

    /** Tolerant reader in the codebase's `opt*` style; missing keys mean defaults. */
    fun fromJson(obj: JSONObject?): GenUiMessageState {
        if (obj == null) return GenUiMessageState()
        val values = mutableMapOf<String, String>()
        obj.optJSONObject("values")?.let { json ->
            json.keys().forEach { key -> values[key] = json.optString(key) }
        }
        val decisions = mutableMapOf<String, Boolean>()
        obj.optJSONObject("approvalDecisions")?.let { json ->
            json.keys().forEach { key -> decisions[key] = json.optBoolean(key, false) }
        }
        return GenUiMessageState(
            values = values,
            submitted = obj.optBoolean("submitted", false),
            loading = obj.optBoolean("loading", false),
            approvalDecisions = decisions,
        )
    }
}
