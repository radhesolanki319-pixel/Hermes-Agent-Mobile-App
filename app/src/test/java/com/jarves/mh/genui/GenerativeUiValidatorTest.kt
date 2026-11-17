package com.jarves.mh.genui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerativeUiValidatorTest {

    private val validPayload = """
        {
          "protocol": "generative-ui",
          "version": "1.0",
          "message_id": "msg-1",
          "ui": {
            "type": "card",
            "title": "Pick a style",
            "children": [
              { "type": "radio_group", "id": "style", "label": "Style", "options": ["Minimal", "Bold"] },
              { "type": "input", "id": "name", "label": "Page name" },
              { "type": "button", "label": "Submit", "props": { "action": "submit" } }
            ]
          },
          "actions": [
            { "id": "submit", "type": "submit_form", "label": "Submit" }
          ]
        }
    """.trimIndent()

    private fun validate(json: String) = GenerativeUiValidator.validate(json)

    @Test
    fun `valid payload parses`() {
        val result = validate(validPayload)
        assertTrue(result is GenerativeUiValidator.ValidationResult.Valid)
        val payload = (result as GenerativeUiValidator.ValidationResult.Valid).payload
        assertEquals("msg-1", payload.messageId)
        assertEquals(GenUiComponentTypes.CARD, payload.root.type)
        assertEquals(3, payload.root.children.size)
        assertEquals(1, payload.actions.size)
        assertEquals(GenUiActionTypes.SUBMIT_FORM, payload.actionById("submit")?.type)
        val radio = payload.root.children[0]
        assertEquals(
            listOf("Minimal" to "Minimal", "Bold" to "Bold"),
            radio.propOptions("options"),
        )
    }

    @Test
    fun `unknown component is rejected safely`() {
        val bad = validPayload.replace("\"card\"", "\"webview\"")
        assertTrue(validate(bad) is GenerativeUiValidator.ValidationResult.Invalid)
    }

    @Test
    fun `malformed json is rejected`() {
        assertTrue(validate("{not json") is GenerativeUiValidator.ValidationResult.Invalid)
        assertTrue(validate("") is GenerativeUiValidator.ValidationResult.Invalid)
    }

    @Test
    fun `version mismatch is rejected`() {
        val bad = validPayload.replace("\"1.0\"", "\"2.0\"")
        assertTrue(validate(bad) is GenerativeUiValidator.ValidationResult.Invalid)
    }

    @Test
    fun `depth cap is enforced`() {
        assertTrue(validate(nestedCards(12)) is GenerativeUiValidator.ValidationResult.Invalid)
        assertTrue(validate(nestedCards(3)) is GenerativeUiValidator.ValidationResult.Valid)
    }

    @Test
    fun `node cap is enforced`() {
        val children = JSONArray()
        repeat(101) {
            children.put(
                JSONObject()
                    .put("type", "text")
                    .put("props", JSONObject().put("text", "x")),
            )
        }
        val payload = JSONObject()
            .put("protocol", "generative-ui")
            .put("version", "1.0")
            .put("ui", JSONObject().put("type", "column").put("children", children))
            .toString()
        assertTrue(validate(payload) is GenerativeUiValidator.ValidationResult.Invalid)
    }

    @Test
    fun `non allowlisted action is dropped but payload stays valid`() {
        val withBadAction = validPayload.replace("\"submit_form\"", "\"run_shell\"")
        val result = validate(withBadAction)
        assertTrue(result is GenerativeUiValidator.ValidationResult.Valid)
        val payload = (result as GenerativeUiValidator.ValidationResult.Valid).payload
        assertTrue(payload.actions.isEmpty())
    }

    @Test
    fun `radio group without options is rejected`() {
        val payload = JSONObject()
            .put("protocol", "generative-ui")
            .put("version", "1.0")
            .put("ui", JSONObject().put("type", "radio_group").put("id", "choice"))
            .toString()
        assertTrue(validate(payload) is GenerativeUiValidator.ValidationResult.Invalid)
    }

    @Test
    fun `field without id gets an automatic id`() {
        val payload = JSONObject()
            .put("protocol", "generative-ui")
            .put("version", "1.0")
            .put(
                "ui",
                JSONObject()
                    .put("type", "input")
                    .put("props", JSONObject().put("label", "Name")),
            )
            .toString()
        val result = validate(payload)
        assertTrue(result is GenerativeUiValidator.ValidationResult.Valid)
        val root = (result as GenerativeUiValidator.ValidationResult.Valid).payload.root
        assertTrue(root.id?.startsWith("auto_") == true)
    }

    private fun nestedCards(depth: Int): String {
        var node = JSONObject()
            .put("type", "text")
            .put("props", JSONObject().put("text", "leaf"))
        repeat(depth) {
            node = JSONObject()
                .put("type", "card")
                .put("children", JSONArray().put(node))
        }
        return JSONObject()
            .put("protocol", "generative-ui")
            .put("version", "1.0")
            .put("ui", node)
            .toString()
    }
}
