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
    const val MAX_TABS = 10
    const val MAX_TABLE_COLUMNS = 10
    const val MAX_LIST_ITEMS = 50
    const val MAX_STRING_LENGTH = 4096
    const val MAX_ID_LENGTH = 64

    /**
     * Appended to every runtime prompt in `MainViewModel.startPrompt` (HP4) so all three
     * agent bridges learn the same contract. Keep short and versioned.
     *
     * v32 prepends a plain-text-default rule: the agents were attaching
     * UI (and narrating the mechanism) to ordinary replies, which the
     * user experiences as junk next to a plain CLI-style answer. The
     * protocol, caps and component vocabulary below are unchanged.
     */
    val CAPABILITY_PROMPT_BLOCK: String = """
        <generative_ui>
        DEFAULT IS PLAIN TEXT. Reply in plain text unless a component below genuinely helps THIS reply — a real list or comparison, choices the user must pick from, a form worth filling, progress worth showing, or data that reads better as a table. Never attach UI as decoration, never for a simple answer, and never mention or explain this mechanism in your reply. Write the text part like a person talking — short, natural sentences in the user's own language; no canned greetings, no menu of your services.
        You can attach ONE interactive UI to a reply by ending the reply with a fenced block whose language is generative-ui and whose content is a single JSON object:
        {"protocol":"generative-ui","version":"1.0","ui":<node>,"actions":[<action>]}
        A node is {"type":...,"id":...,"props":{...},"children":[...]}. Supported types: card, column, row (containers with children), text (props.text, markdown), heading (props.text, props.level 1-3), divider, spinner (props.label), progress (props.value 0..1, props.label), code_block (props.code, props.language), input (props.label, props.placeholder, props.value), textarea (same props as input), radio_group (props.label, props.options as [{"value":...,"label":...}] or plain strings, props.selected), checkbox (props.label, props.checked), button (props.label, props.action = id of an entry in actions), approval_card (props.title, props.text, props.command).
        More types: select (a dropdown; same props as radio_group), slider (props.label, props.min, props.max, props.step, props.value — all numbers), tabs (props.labels as a list of strings with exactly ONE child node per label — wrap several components in a column; props.selected = index of the first tab), image (props.url — https only, props.alt, props.caption), badge (props.text, props.tone one of neutral, success, warning, danger, info), alert (props.tone one of info, success, warning, danger; props.title, props.text markdown), table (props.headers as a list of strings, props.rows as a list of lists of strings, one cell per header), tool_call_card (props.tool, props.status one of running, done, error; props.summary), file_preview (props.name, props.size, props.type — display only), timeline (props.steps as [{"title":...,"text":...,"status": one of done, current, upcoming}]).
        An action is {"id":...,"type":...} with type one of: submit_form, send_message (field "message"), request_approval, invoke_tool (field "tool"), open_url (field "url", https only), copy_text (field "text").
        Rules: emit the whole block as valid JSON inside ONE message (never split it around tool calls); at most ~100 nodes; ids unique within the block; give every input, textarea, radio_group, select, slider, tabs, checkbox and approval_card an id. When the user submits a form or answers an approval card, their choices return to you as a user message starting with "[generative-ui response]". The app only renders the components above — never ask it to run code, shell commands, HTML or scripts.
        </generative_ui>
    """.trimIndent()
}

/** Component type strings supported by the renderer (the validator's allowlist). */
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
    const val SELECT = "select"
    const val SLIDER = "slider"
    const val TABS = "tabs"
    const val IMAGE = "image"
    const val BADGE = "badge"
    const val ALERT = "alert"
    const val TABLE = "table"
    const val TOOL_CALL_CARD = "tool_call_card"
    const val FILE_PREVIEW = "file_preview"
    const val TIMELINE = "timeline"

    val ALL: Set<String> = setOf(
        TEXT, HEADING, CARD, ROW, COLUMN, BUTTON, INPUT, TEXTAREA, RADIO_GROUP, CHECKBOX,
        APPROVAL_CARD, PROGRESS, CODE_BLOCK, DIVIDER, SPINNER,
        SELECT, SLIDER, TABS, IMAGE, BADGE, ALERT, TABLE, TOOL_CALL_CARD, FILE_PREVIEW, TIMELINE,
    )

    /** Types that may carry a `children` array. Children on any other type are ignored. */
    val CONTAINERS: Set<String> = setOf(CARD, ROW, COLUMN, TABS)

    /** Types whose current value is collected when a form is submitted. */
    val FIELD_TYPES: Set<String> = setOf(INPUT, TEXTAREA, RADIO_GROUP, CHECKBOX, SELECT, SLIDER)

    /** Types that need a stable id even when the payload omits one (state is keyed by id). */
    val ID_TYPES: Set<String> = FIELD_TYPES + APPROVAL_CARD + TABS
}

/** Fixed vocabularies for the Phase 2 tone/status props; anything else is normalized away. */
object GenUiPropValues {
    val BADGE_TONES: Set<String> = setOf("neutral", "success", "warning", "danger", "info")
    val ALERT_TONES: Set<String> = setOf("info", "success", "warning", "danger")
    val TOOL_STATUSES: Set<String> = setOf("running", "done", "error")
    val TIMELINE_STATUSES: Set<String> = setOf("done", "current", "upcoming")
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
    data class TextMatrix(val values: List<List<String>>) : GenUiValue
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

    /** A list-of-strings prop (tab labels, table headers), or empty when absent/mistyped. */
    fun propTextList(name: String): List<String> =
        (props[name] as? GenUiValue.TextList)?.values ?: emptyList()

    /** A list-of-objects prop (timeline steps), or empty when absent/mistyped. */
    fun propObjectList(name: String): List<Map<String, GenUiValue>> =
        (props[name] as? GenUiValue.ObjectList)?.values ?: emptyList()

    /** A list-of-string-lists prop (table rows), or empty when absent/mistyped. */
    fun propMatrix(name: String): List<List<String>> =
        (props[name] as? GenUiValue.TextMatrix)?.values ?: emptyList()
}

/**
 * Canonical string form of a numeric field value (slider positions, tab defaults): integral
 * values render without a decimal point, fractional values are rounded to 4 decimal places.
 * Shared by the Action Bridge (defaults) and the renderer (live slider updates) so a value
 * means the same string on both sides.
 */
internal fun formatGenUiNumber(value: Double): String {
    val rounded = kotlin.math.round(value * 10000.0) / 10000.0
    return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
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
