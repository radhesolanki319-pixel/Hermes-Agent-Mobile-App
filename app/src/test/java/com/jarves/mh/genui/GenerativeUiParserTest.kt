package com.jarves.mh.genui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerativeUiParserTest {

    private val payloadJson = """
        {
          "protocol": "generative-ui",
          "version": "1.0",
          "message_id": "msg-1",
          "ui": { "type": "text", "props": { "text": "Hello" } },
          "actions": []
        }
    """.trimIndent()

    @Test
    fun `extracts fenced payload and splits prose`() {
        val text = "Here is a form:\n\n```generative-ui\n$payloadJson\n```\n\nLet me know."
        val result = GenerativeUiParser.extract(text)
        assertTrue(result is GenerativeUiParser.ExtractResult.Found)
        val found = result as GenerativeUiParser.ExtractResult.Found
        assertEquals("Here is a form:\n\nLet me know.", found.prose)
        assertTrue(found.payloadJson.contains("\"generative-ui\""))
    }

    @Test
    fun `plain text has no payload`() {
        val result = GenerativeUiParser.extract("Just a normal answer, no UI here.")
        assertTrue(result is GenerativeUiParser.ExtractResult.None)
    }

    @Test
    fun `unterminated fence is incomplete`() {
        val text = "Working on it:\n\n```generative-ui\n{\"protocol\": \"generative-ui\", \"version\":"
        val result = GenerativeUiParser.extract(text)
        assertTrue(result is GenerativeUiParser.ExtractResult.Incomplete)
        assertEquals("Working on it:", (result as GenerativeUiParser.ExtractResult.Incomplete).prose)
    }

    @Test
    fun `unrelated code fences are skipped`() {
        val text = "Code first:\n\n```kotlin\nval x = 1\n```\n\n```generative-ui\n$payloadJson\n```"
        val result = GenerativeUiParser.extract(text)
        assertTrue(result is GenerativeUiParser.ExtractResult.Found)
        val found = result as GenerativeUiParser.ExtractResult.Found
        assertEquals("Code first:\n\n```kotlin\nval x = 1\n```", found.prose)
    }

    @Test
    fun `raw json object payload is extracted`() {
        val text = "Intro\n\n$payloadJson"
        val result = GenerativeUiParser.extract(text)
        assertTrue(result is GenerativeUiParser.ExtractResult.Found)
        assertEquals("Intro", (result as GenerativeUiParser.ExtractResult.Found).prose)
    }

    @Test
    fun `parseGenUiMessage validates an extracted payload`() {
        val parts = parseGenUiMessage("Pick one:\n\n```generative-ui\n$payloadJson\n```")
        assertEquals("Pick one:", parts.prose)
        assertTrue(parts.payload != null)
        assertEquals("msg-1", parts.payload?.messageId)
    }

    @Test
    fun `parseGenUiMessage falls back to original text for invalid payload`() {
        val bad = payloadJson.replace("\"text\"", "\"webview\"")
        val text = "Intro\n\n```generative-ui\n$bad\n```"
        val parts = parseGenUiMessage(text)
        assertNull(parts.payload)
        assertTrue(parts.payloadInvalid)
        assertEquals(text, parts.prose)
    }
}
