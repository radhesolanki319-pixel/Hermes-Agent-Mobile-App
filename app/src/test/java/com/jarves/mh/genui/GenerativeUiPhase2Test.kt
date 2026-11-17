package com.jarves.mh.genui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 components: validator accept/reject + normalization per component, Action Bridge
 * defaults and form collection for the new field types, and state persistence.
 */
class GenerativeUiPhase2Test {

    private fun validate(json: String) = GenerativeUiValidator.validate(json)

    private fun payloadWithUi(ui: JSONObject): String = JSONObject()
        .put("protocol", "generative-ui")
        .put("version", "1.0")
        .put("ui", ui)
        .toString()

    private fun validPayload(json: String): GenUiPayload {
        val result = validate(json)
        assertTrue(
            "expected Valid, got $result",
            result is GenerativeUiValidator.ValidationResult.Valid,
        )
        return (result as GenerativeUiValidator.ValidationResult.Valid).payload
    }

    private fun assertInvalid(json: String) {
        assertTrue(validate(json) is GenerativeUiValidator.ValidationResult.Invalid)
    }

    private fun node(type: String, props: JSONObject? = null): JSONObject {
        val result = JSONObject().put("type", type)
        if (props != null) result.put("props", props)
        return result
    }

    // -- select ---------------------------------------------------------------

    @Test
    fun `select validates and normalizes string options`() {
        val ui = node(
            "select",
            JSONObject()
                .put("label", "Theme")
                .put("options", JSONArray().put("Light").put("Dark")),
        ).put("id", "theme")
        val payload = validPayload(payloadWithUi(ui))
        assertEquals(
            listOf("Light" to "Light", "Dark" to "Dark"),
            payload.root.propOptions("options"),
        )
    }

    @Test
    fun `select without options is rejected`() {
        assertInvalid(payloadWithUi(node("select").put("id", "theme")))
    }

    // -- slider ---------------------------------------------------------------

    @Test
    fun `slider with numeric range validates`() {
        val ui = node(
            "slider",
            JSONObject().put("min", 0).put("max", 10).put("step", 2).put("value", 4),
        ).put("id", "level")
        validPayload(payloadWithUi(ui))
    }

    @Test
    fun `slider with max not greater than min is rejected`() {
        val ui = node(
            "slider",
            JSONObject().put("min", 10).put("max", 10),
        ).put("id", "level")
        assertInvalid(payloadWithUi(ui))
    }

    @Test
    fun `slider with non numeric min is rejected`() {
        val ui = node(
            "slider",
            JSONObject().put("min", "zero").put("max", 10),
        ).put("id", "level")
        assertInvalid(payloadWithUi(ui))
    }

    @Test
    fun `slider with non positive step is rejected`() {
        val ui = node(
            "slider",
            JSONObject().put("min", 0).put("max", 10).put("step", 0),
        ).put("id", "level")
        assertInvalid(payloadWithUi(ui))
    }

    // -- tabs -----------------------------------------------------------------

    private fun tabsUi(labelCount: Int, childCount: Int): JSONObject {
        val labels = JSONArray()
        repeat(labelCount) { labels.put("Tab ${it + 1}") }
        val children = JSONArray()
        repeat(childCount) {
            children.put(node("text", JSONObject().put("text", "content $it")))
        }
        return node("tabs", JSONObject().put("labels", labels))
            .put("id", "sections")
            .put("children", children)
    }

    @Test
    fun `tabs with one child per label validates`() {
        val payload = validPayload(payloadWithUi(tabsUi(2, 2)))
        assertEquals(listOf("Tab 1", "Tab 2"), payload.root.propTextList("labels"))
        assertEquals(2, payload.root.children.size)
    }

    @Test
    fun `tabs with mismatched children is rejected`() {
        assertInvalid(payloadWithUi(tabsUi(2, 1)))
        assertInvalid(payloadWithUi(tabsUi(1, 2)))
    }

    @Test
    fun `tabs without labels is rejected`() {
        val ui = node("tabs")
            .put("id", "sections")
            .put(
                "children",
                JSONArray().put(node("text", JSONObject().put("text", "x"))),
            )
        assertInvalid(payloadWithUi(ui))
    }

    // -- image ----------------------------------------------------------------

    @Test
    fun `image with https url validates`() {
        val ui = node(
            "image",
            JSONObject()
                .put("url", "https://example.com/chart.png")
                .put("alt", "Chart")
                .put("caption", "Weekly chart"),
        )
        val payload = validPayload(payloadWithUi(ui))
        assertEquals("https://example.com/chart.png", payload.root.propText("url"))
    }

    @Test
    fun `image with http url is rejected`() {
        val ui = node("image", JSONObject().put("url", "http://example.com/chart.png"))
        assertInvalid(payloadWithUi(ui))
    }

    @Test
    fun `image with non web scheme or missing url is rejected`() {
        assertInvalid(
            payloadWithUi(node("image", JSONObject().put("url", "file:///etc/passwd"))),
        )
        assertInvalid(
            payloadWithUi(node("image", JSONObject().put("url", "javascript:alert(1)"))),
        )
        assertInvalid(payloadWithUi(node("image", JSONObject().put("alt", "no url"))))
    }

    // -- badge / alert tone normalization --------------------------------------

    @Test
    fun `badge with unknown tone normalizes to neutral`() {
        val ui = node(
            "badge",
            JSONObject().put("text", "Beta").put("tone", "purple"),
        )
        val payload = validPayload(payloadWithUi(ui))
        assertEquals("neutral", payload.root.propText("tone"))
    }

    @Test
    fun `badge with known tone keeps it`() {
        val ui = node(
            "badge",
            JSONObject().put("text", "Live").put("tone", "success"),
        )
        val payload = validPayload(payloadWithUi(ui))
        assertEquals("success", payload.root.propText("tone"))
    }

    @Test
    fun `alert with unknown tone normalizes to info`() {
        val ui = node(
            "alert",
            JSONObject().put("text", "Heads up").put("tone", "fatal"),
        )
        val payload = validPayload(payloadWithUi(ui))
        assertEquals("info", payload.root.propText("tone"))
    }

    // -- table ------------------------------------------------------------------

    private fun tableUi(headers: JSONArray, rows: JSONArray?): JSONObject {
        val props = JSONObject().put("headers", headers)
        if (rows != null) props.put("rows", rows)
        return node("table", props)
    }

    @Test
    fun `table validates and pads short rows`() {
        val ui = tableUi(
            JSONArray().put("Name").put("Age"),
            JSONArray()
                .put(JSONArray().put("Ada").put("36"))
                .put(JSONArray().put("Grace")),
        )
        val payload = validPayload(payloadWithUi(ui))
        assertEquals(listOf("Name", "Age"), payload.root.propTextList("headers"))
        assertEquals(
            listOf(listOf("Ada", "36"), listOf("Grace", "")),
            payload.root.propMatrix("rows"),
        )
    }

    @Test
    fun `table row wider than headers is rejected`() {
        val ui = tableUi(
            JSONArray().put("Name"),
            JSONArray().put(JSONArray().put("Ada").put("36")),
        )
        assertInvalid(payloadWithUi(ui))
    }

    @Test
    fun `table without headers is rejected`() {
        val ui = tableUi(
            JSONArray(),
            JSONArray().put(JSONArray().put("Ada")),
        )
        assertInvalid(payloadWithUi(ui))
    }

    @Test
    fun `table with flat rows is rejected`() {
        val ui = tableUi(
            JSONArray().put("Name"),
            JSONArray().put("Ada"),
        )
        assertInvalid(payloadWithUi(ui))
    }

    @Test
    fun `table with too many columns is rejected`() {
        val headers = JSONArray()
        repeat(GenUiProtocol.MAX_TABLE_COLUMNS + 1) { headers.put("Col $it") }
        assertInvalid(payloadWithUi(tableUi(headers, null)))
    }

    // -- tool_call_card / file_preview ------------------------------------------

    @Test
    fun `tool call card normalizes unknown status to running`() {
        val ui = node(
            "tool_call_card",
            JSONObject().put("tool", "read_file").put("status", "pending"),
        )
        val payload = validPayload(payloadWithUi(ui))
        assertEquals("running", payload.root.propText("status"))
    }

    @Test
    fun `tool call card without a tool is rejected`() {
        val ui = node("tool_call_card", JSONObject().put("status", "done"))
        assertInvalid(payloadWithUi(ui))
    }

    @Test
    fun `file preview requires a name`() {
        assertInvalid(
            payloadWithUi(node("file_preview", JSONObject().put("size", "4 KB"))),
        )
        validPayload(
            payloadWithUi(
                node(
                    "file_preview",
                    JSONObject().put("name", "Main.kt").put("size", "4 KB"),
                ),
            ),
        )
    }

    // -- timeline -----------------------------------------------------------------

    @Test
    fun `timeline normalizes statuses and drops untitled steps`() {
        val steps = JSONArray()
            .put(JSONObject().put("title", "Plan").put("status", "done"))
            .put(JSONObject().put("title", "Build").put("status", "weird"))
            .put(JSONObject().put("status", "current"))
        val ui = node("timeline", JSONObject().put("steps", steps))
        val payload = validPayload(payloadWithUi(ui))
        val normalized = payload.root.propObjectList("steps")
        assertEquals(2, normalized.size)
        assertEquals("done", (normalized[0]["status"] as GenUiValue.Text).value)
        assertEquals("upcoming", (normalized[1]["status"] as GenUiValue.Text).value)
    }

    @Test
    fun `timeline without titled steps is rejected`() {
        val steps = JSONArray().put(JSONObject().put("status", "done"))
        assertInvalid(
            payloadWithUi(node("timeline", JSONObject().put("steps", steps))),
        )
        assertInvalid(payloadWithUi(node("timeline", JSONObject())))
    }

    // -- bridge defaults, form collection, state -----------------------------------

    private fun formPayload(): GenUiPayload {
        val select = node(
            "select",
            JSONObject()
                .put("options", JSONArray().put("a").put("b"))
                .put("selected", "b"),
        ).put("id", "choice")
        val slider = node(
            "slider",
            JSONObject().put("min", 0).put("max", 100).put("value", 25),
        ).put("id", "volume")
        val ui = node("column").put("children", JSONArray().put(select).put(slider))
        return validPayload(payloadWithUi(ui))
    }

    @Test
    fun `select and slider defaults come from props`() {
        val values = GenUiActionBridge.collectFormValues(formPayload(), GenUiMessageState())
        assertEquals("b", values["choice"])
        assertEquals("25", values["volume"])
    }

    @Test
    fun `select and slider values are collected on submit`() {
        val state = GenUiMessageState()
            .withValue("choice", "a")
            .withValue("volume", "80")
        val values = GenUiActionBridge.collectFormValues(formPayload(), state)
        assertEquals(mapOf("choice" to "a", "volume" to "80"), values)
    }

    @Test
    fun `slider default formats integral and fractional values`() {
        fun sliderWith(props: Map<String, GenUiValue>) = GenUiNode(
            type = GenUiComponentTypes.SLIDER,
            id = "s",
            props = props,
        )
        assertEquals(
            "25",
            GenUiActionBridge.defaultValue(
                sliderWith(mapOf("value" to GenUiValue.Number(25.0))),
            ),
        )
        assertEquals(
            "2.5",
            GenUiActionBridge.defaultValue(
                sliderWith(mapOf("value" to GenUiValue.Number(2.5))),
            ),
        )
        assertEquals(
            "5",
            GenUiActionBridge.defaultValue(
                sliderWith(mapOf("min" to GenUiValue.Number(5.0))),
            ),
        )
    }

    @Test
    fun `tabs default selection is the selected prop or zero`() {
        val plain = GenUiNode(type = GenUiComponentTypes.TABS, id = "t")
        assertEquals("0", GenUiActionBridge.defaultValue(plain))
        val preselected = GenUiNode(
            type = GenUiComponentTypes.TABS,
            id = "t",
            props = mapOf("selected" to GenUiValue.Number(2.0)),
        )
        assertEquals("2", GenUiActionBridge.defaultValue(preselected))
    }

    @Test
    fun `select and tabs state survives the state codec`() {
        val state = GenUiMessageState()
            .withValue("choice", "b")
            .withValue("sections", "1")
        val restored = GenUiStateCodec.fromJson(GenUiStateCodec.toJson(state))
        assertEquals("b", restored.value("choice", ""))
        assertEquals("1", restored.value("sections", ""))
    }

    // -- end to end -----------------------------------------------------------------

    @Test
    fun `phase 2 payload parses end to end from a fenced block`() {
        val json = """
            {
              "protocol": "generative-ui",
              "version": "1.0",
              "ui": {
                "type": "card",
                "children": [
                  { "type": "select", "id": "theme", "options": ["Light", "Dark"] },
                  { "type": "slider", "id": "size", "min": 1, "max": 5, "value": 3 },
                  { "type": "badge", "props": { "text": "New", "tone": "info" } },
                  { "type": "timeline", "props": { "steps": [
                      { "title": "Plan", "status": "done" },
                      { "title": "Build", "status": "current" }
                  ] } }
                ]
              }
            }
        """.trimIndent()
        val parts = parseGenUiMessage("Here you go:\n\n```generative-ui\n$json\n```")
        assertEquals("Here you go:", parts.prose)
        assertTrue(parts.payload != null)
        assertEquals(
            listOf("select", "slider", "badge", "timeline"),
            parts.payload!!.root.children.map { it.type },
        )
    }
}
