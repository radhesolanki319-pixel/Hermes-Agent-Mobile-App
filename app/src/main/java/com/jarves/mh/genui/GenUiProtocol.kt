package com.jarves.mh.genui

/**
 * Wire models and constants for the Dynamic Generative UI protocol (`generative-ui`, v1.0).
 *
 * An agent reply may end with a fenced block:
 *
 * ```generative-ui
 * {"protocol":"generative-ui","version":"1.0","ui":{...},"actions":[...]}
 * ```
 *
 * A node is `{"type":..., "id":..., "props":{...}, "children":[...]}`. Buttons reference an
 * entry of the top-level `actions` array through their `action` prop; form fields are the
 * components whose values are collected when a `submit_form` action fires. Everything in a
 * payload is data only — the app renders a closed allowlist of components and never executes
 * payload content as code, HTML or shell.
 */
object GenUiProtocol {
    const val PROTOCOL = "generative-ui"
    const val VERSION = "1.0"
    const val FENCE_LANGUAGE = "generative-ui"

    const val MAX_PAYLOAD_BYTES = 64 * 1024
    const val MAX_NODES = 100
    const val MAX_DEPTH = 8
    const val MAX_CHILDREN = 50
    const val MAX_ACTIONS = 20
    const val MAX_OPTIONS = 20
    const val MAX_LIST_ITEMS = 50
    const val MAX_STRING_LENGTH = 4096
    const val MAX_ID_LENGTH = 64

    /**
     * Appended to every runtime prompt in `MainViewModel.startPrompt` (HP4) so all three
     * agent bridges learn the same contract. Keep short and versioned.
     */
    val CAPABILITY_PROMPT_BLOCK: String = """
        <generative_ui>
        You can attach ONE interactive UI to a reply by ending the reply with a fenced block whose language is generative-ui and whose content is a single JSON object:
        {"protocol":"generative-ui","version":"1.0","ui":<node>,"actions":[<action>]}
        A node is {"type":...,"id":...,"props":{...},"children":[...]}. Supported types: card, column, row (containers with children), text (props.text, markdown), heading (props.text, props.level 1-3), divider, spinner (props.label), progress (props.value 0..1, props.label), code_block (props.code, props.language), input (props.label, props.placeholder, props.value), textarea (same props as input), radio_group (props.label, props.options as [{"value":...,"label":...}] or plain strings, props.selected), checkbox (props.label, props.checked), button (props.label, props.action = id of an entry in actions), approval_card (props.title, props.text, props.command).
        An action is {"id":...,"type":...} with type one of: submit_form, send_message (field "message"), request_approval, invoke_tool (field "tool"), open_url (field "url", https only), copy_text (field "text").
        Rules: emit the whole block as valid JSON inside ONE message (never split it around tool calls); at most ~100 nodes; ids unique within the block; give every input, textarea, radio_group, checkbox and approval_card an id. When the user submits a form or answers an approval card, their choices return to you as a user message starting with "[generative-ui response]". The app only renders the components above — never ask it to run code, shell commands, HTML or scripts.
        </generative_ui>
    """.trimIndent()
}

/** Component type strings supported by the MVP renderer (the validator's allowlist). */
object GenUiComponentTypes {
    const val TEXT = "text"
    const val HEADING = "heading"
    const val CARD = "card"
    const val ROW = "row"
    const val COLUMN = "column"
    const val BUTTON = "button"
    const val INPUT = "input"
    const val TEXTAREA = "textarea"
    const val RADIO_GROUP = "radio_group"
    const val CHECKBOX = "checkbox"
    const val APPROVAL_CARD = "approval_card"
    const val PROGRESS = "progress"
    const val CODE_BLOCK = "code_block"
    const val DIVIDER = "divider"
    const val SPINNER = "spinner"

    val ALL: Set<String> = setOf(
        TEXT, HEADING, CARD, ROW, COLUMN, BUTTON, INPUT, TEXTAREA, RADIO_GROUP, CHECKBOX,
        APPROVAL_CARD, PROGRESS, CODE_BLOCK, DIVIDER, SPINNER,
    )

    /** Types that may carry a `children` array. Children on any other type are ignored. */
    val CONTAINERS: Set<String> = setOf(CARD, ROW, COLUMN)

    /** Types whose current value is collected when a form is submitted. */
    val FIELD_TYPES: Set<String> = setOf(INPUT, TEXTAREA, RADIO_GROUP, CHECKBOX)

    /** Types that need a stable id even when the payload omits one (state is keyed by id). */
    val ID_TYPES: Set<String> = FIELD_TYPES + APPROVAL_CARD
}

/** Action type strings the Event & Action Bridge will ever dispatch (the action allowlist). */
object GenUiActionTypes {
    const val SUBMIT_FORM = "submit_form"
    const val SEND_MESSAGE = "send_message"
    const val REQUEST_APPROVAL = "request_approval"
    const val INVOKE_TOOL = "invoke_tool"
    const val OPEN_URL = "open_url"
    const val COPY_TEXT = "copy_text"

    val ALL: Set<String> = setOf(SUBMIT_FORM, SEND_MESSAGE, REQUEST_APPROVAL, INVOKE_TOOL, OPEN_URL, COPY_TEXT)
}

/** A single typed prop value. Payload JSON is converted into these by the validator. */
sealed interface GenUiValue {
    data class Text(val value: String) : GenUiValue
    data class Number(val value: Double) : GenUiValue
    data class Bool(val value: Boolean) : GenUiValue
    data class TextList(val values: List<String>) : GenUiValue
    data class ObjectList(val values: List<Map<String, GenUiValue>>) : GenUiValue
    data class Object(val values: Map<String, GenUiValue>) : GenUiValue
}

/** One validated component in the tree. */
data class GenUiNode(
    val type: String,
    val id: String?,
    val props: Map<String, GenUiValue> = emptyMap(),
    val children: List<GenUiNode> = emptyList(),
) {
    fun propText(name: String): String? = (props[name] as? GenUiValue.Text)?.value

    fun propNumber(name: String): Double? = (props[name] as? GenUiValue.Number)?.value

    fun propBool(name: String): Boolean? = (props[name] as? GenUiValue.Bool)?.value

    /** Options of a `radio_group`, normalized by the validator to (value, label) pairs. */
    fun propOptions(name: String): List<Pair<String, String>> = when (val value = props[name]) {
        is GenUiValue.ObjectList -> value.values.mapNotNull { entry ->
            val optionValue = (entry["value"] as? GenUiValue.Text)?.value ?: return@mapNotNull null
            val optionLabel = (entry["label"] as? GenUiValue.Text)?.value ?: optionValue
            optionValue to optionLabel
        }
        is GenUiValue.TextList -> value.values.map { it to it }
        else -> emptyList()
    }
}

/** One entry of the payload's top-level `actions` array, already allowlist-filtered. */
data class GenUiActionDef(
    val id: String,
    val type: String,
    val label: String? = null,
    val message: String? = null,
    val url: String? = null,
    val text: String? = null,
    val tool: String? = null,
    val params: Map<String, GenUiValue> = emptyMap(),
)

/** A fully validated payload, safe to hand to the renderer. */
data class GenUiPayload(
    val messageId: String?,
    val root: GenUiNode,
    val actions: List<GenUiActionDef> = emptyList(),
) {
    fun actionById(id: String?): GenUiActionDef? =
        if (id.isNullOrBlank()) null else actions.firstOrNull { it.id == id }
}

/**
 * Interaction events emitted by rendered components. Field and selection changes travel as
 * [FieldChanged] (radio selections and checkbox states are plain string values, `"true"` /
 * `"false"` for checkboxes); buttons fire [ActionInvoked]; approval cards fire
 * [ApprovalDecision]. `MainViewModel.handleGenUiAction` is the single entry point for all of
 * them.
 */
sealed interface GenUiEvent {
    data class FieldChanged(val componentId: String, val value: String) : GenUiEvent
    data class ActionInvoked(val action: GenUiActionDef, val sourceComponentId: String?) : GenUiEvent
    data class ApprovalDecision(val componentId: String, val approved: Boolean) : GenUiEvent
}
