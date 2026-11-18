package com.jarves.mh.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesRemoteClientTest {

    // ── URL handling ──────────────────────────────────────────────────────

    @Test
    fun normalizeBaseUrlHandlesPastedShapes() {
        assertEquals("", HermesGatewayUrls.normalizeBaseUrl(""))
        assertEquals("", HermesGatewayUrls.normalizeBaseUrl("   "))
        assertEquals("https://hermes.example.com", HermesGatewayUrls.normalizeBaseUrl("hermes.example.com"))
        assertEquals("https://hermes.example.com", HermesGatewayUrls.normalizeBaseUrl("  https://hermes.example.com  "))
        assertEquals("https://hermes.example.com", HermesGatewayUrls.normalizeBaseUrl("https://hermes.example.com/"))
        assertEquals("https://hermes.example.com", HermesGatewayUrls.normalizeBaseUrl("https://hermes.example.com/v1"))
        assertEquals("https://hermes.example.com", HermesGatewayUrls.normalizeBaseUrl("https://hermes.example.com/v1/"))
        assertEquals("http://192.168.1.10:8642", HermesGatewayUrls.normalizeBaseUrl("http://192.168.1.10:8642"))
        assertEquals("https://hermes.example.com/p/work", HermesGatewayUrls.normalizeBaseUrl("https://hermes.example.com/p/work/"))
    }

    @Test
    fun endpointBuildersAppendGatewayPaths() {
        val base = HermesGatewayUrls.normalizeBaseUrl("https://hermes.example.com/v1")
        assertEquals("https://hermes.example.com/v1/chat/completions", HermesGatewayUrls.chatCompletionsUrl(base))
        assertEquals("https://hermes.example.com/v1/models", HermesGatewayUrls.modelsUrl(base))
        assertEquals("https://hermes.example.com/health", HermesGatewayUrls.healthUrl(base))
    }

    // ── Request body ──────────────────────────────────────────────────────

    @Test
    fun chatCompletionBodyCarriesPromptAsSingleUserMessage() {
        val prompt = "List the files\n\n<generative-ui-capability>block</generative-ui-capability>"
        val body = HermesGatewayRequests.chatCompletionBody("claude-sonnet-5.5", prompt)
        assertEquals("claude-sonnet-5.5", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())
        val message = messages.getJSONObject(0)
        assertEquals("user", message.getString("role"))
        // The runtime prompt (GenUI capability block included) travels verbatim.
        assertEquals(prompt, message.getString("content"))
    }

    @Test
    fun chatCompletionBodyOmitsTheModelFieldWhenUnset() {
        // v24: profile model UNSET → no `model` field at all, so the
        // gateway's own HERMES_MODEL governs (an empty string would be
        // a value the gateway must reject or guess at).
        val body = HermesGatewayRequests.chatCompletionBody("", "hello")
        assertFalse(body.has("model"))
        assertTrue(body.getBoolean("stream"))
        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("hello", messages.getJSONObject(0).getString("content"))
        // Whitespace-only counts as unset too.
        assertFalse(HermesGatewayRequests.chatCompletionBody("  ", "hello").has("model"))
    }

    // ── SSE parsing ───────────────────────────────────────────────────────

    private fun feedAll(parser: HermesGatewaySseParser, rawStream: String): List<HermesGatewayEvent> {
        val events = mutableListOf<HermesGatewayEvent>()
        rawStream.split("\n").forEach { line -> events += parser.feed(line) }
        events += parser.endOfStream()
        return events
    }

    private fun chunk(deltaJson: String, finishJson: String = "null"): String =
        """{"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":$deltaJson,"finish_reason":$finishJson}]}"""

    @Test
    fun roleOnlyChunkProducesNoEvents() {
        val events = feedAll(HermesGatewaySseParser(), "data: ${chunk("""{"role":"assistant"}""")}\n\n")
        assertTrue(events.isEmpty())
    }

    @Test
    fun contentDeltasStreamInOrder() {
        val raw = buildString {
            appendLine("data: ${chunk("""{"role":"assistant"}""")}")
            appendLine()
            appendLine("data: ${chunk("""{"content":"Hello "}""")}")
            appendLine()
            appendLine("data: ${chunk("""{"content":"world"}""")}")
            appendLine()
        }
        val events = feedAll(HermesGatewaySseParser(), raw)
        assertEquals(
            listOf(HermesGatewayEvent.TextDelta("Hello "), HermesGatewayEvent.TextDelta("world")),
            events,
        )
    }

    @Test
    fun reasoningDeltasAreKeptSeparateFromContent() {
        val events = feedAll(
            HermesGatewaySseParser(),
            "data: ${chunk("""{"reasoning_content":"thinking"}""")}\n\n",
        )
        assertEquals(listOf(HermesGatewayEvent.ReasoningDelta("thinking")), events)
    }

    @Test
    fun toolProgressEventCarriesToolLabelAndStatus() {
        val raw = buildString {
            appendLine("event: hermes.tool.progress")
            appendLine("""data: {"tool":"terminal","emoji":"💻","label":"echo hi","toolCallId":"call_1","status":"running"}""")
            appendLine()
            appendLine("event: hermes.tool.progress")
            appendLine("""data: {"tool":"terminal","toolCallId":"call_1","status":"completed"}""")
            appendLine()
        }
        val events = feedAll(HermesGatewaySseParser(), raw)
        assertEquals(
            listOf(
                HermesGatewayEvent.ToolProgress("terminal", "echo hi", "running", "call_1"),
                HermesGatewayEvent.ToolProgress("terminal", "", "completed", "call_1"),
            ),
            events,
        )
    }

    @Test
    fun statusEventCarriesKindAndText() {
        val raw = "event: hermes.status\ndata: {\"kind\":\"provider_wait\",\"text\":\"Waiting for the provider\"}\n\n"
        val events = feedAll(HermesGatewaySseParser(), raw)
        assertEquals(listOf(HermesGatewayEvent.Status("provider_wait", "Waiting for the provider")), events)
    }

    @Test
    fun approvalEventIsSurfaced() {
        val raw = "event: approval.request\ndata: {\"id\":\"run_1\"}\n\n"
        val events = feedAll(HermesGatewaySseParser(), raw)
        assertEquals(listOf(HermesGatewayEvent.ApprovalRequested), events)
    }

    @Test
    fun finishChunkReportsReasonAndDoneEndsTheStream() {
        val raw = buildString {
            appendLine("data: ${chunk("""{"content":"done"}""")}")
            appendLine()
            appendLine("data: ${chunk("""{}""", "\"stop\"")}")
            appendLine()
            appendLine("data: [DONE]")
            appendLine()
        }
        val events = feedAll(HermesGatewaySseParser(), raw)
        assertEquals(
            listOf(
                HermesGatewayEvent.TextDelta("done"),
                HermesGatewayEvent.Finished("stop", null),
                HermesGatewayEvent.StreamEnded,
            ),
            events,
        )
    }

    @Test
    fun finishChunkSurfacesGatewayErrorMessage() {
        val body = JSONObject(chunk("""{}""", "\"error\""))
            .put("error", JSONObject().put("message", "Agent run did not produce a response."))
            .toString()
        val events = feedAll(HermesGatewaySseParser(), "data: $body\n\n")
        assertEquals(
            listOf(HermesGatewayEvent.Finished("error", "Agent run did not produce a response.")),
            events,
        )
    }

    @Test
    fun finishChunkFallsBackToHermesExtrasError() {
        val body = JSONObject(chunk("""{}""", "\"length\""))
            .put("hermes", JSONObject().put("error", "Token budget exhausted"))
            .toString()
        val events = feedAll(HermesGatewaySseParser(), "data: $body\n\n")
        assertEquals(
            listOf(HermesGatewayEvent.Finished("length", "Token budget exhausted")),
            events,
        )
    }

    @Test
    fun commentsIdsAndMalformedFramesAreIgnored() {
        val raw = buildString {
            appendLine(": keep-alive")
            appendLine("id: 42")
            appendLine()
            appendLine("data: {not json")
            appendLine()
            appendLine("data: ${chunk("""{"content":"ok"}""")}")
            appendLine()
        }
        val events = feedAll(HermesGatewaySseParser(), raw)
        assertEquals(listOf(HermesGatewayEvent.TextDelta("ok")), events)
    }

    @Test
    fun trailingFrameWithoutBlankLineIsFlushedAtEndOfStream() {
        val parser = HermesGatewaySseParser()
        val events = mutableListOf<HermesGatewayEvent>()
        events += parser.feed("data: ${chunk("""{"content":"tail"}""")}")
        assertTrue(events.isEmpty()) // Nothing dispatches before the frame terminates.
        events += parser.endOfStream()
        assertEquals(listOf(HermesGatewayEvent.TextDelta("tail")), events)
    }

    @Test
    fun fullTurnSequenceParsesEndToEnd() {
        val raw = buildString {
            appendLine("data: ${chunk("""{"role":"assistant"}""")}")
            appendLine()
            appendLine("event: hermes.tool.progress")
            appendLine("""data: {"tool":"terminal","label":"ls","toolCallId":"c1","status":"running"}""")
            appendLine()
            appendLine("event: hermes.tool.progress")
            appendLine("""data: {"tool":"terminal","toolCallId":"c1","status":"completed"}""")
            appendLine()
            appendLine("data: ${chunk("""{"content":"There are 3 files."}""")}")
            appendLine()
            appendLine("data: ${chunk("""{}""", "\"stop\"")}")
            appendLine()
            appendLine("data: [DONE]")
            appendLine()
        }
        val events = feedAll(HermesGatewaySseParser(), raw)
        assertEquals(
            listOf(
                HermesGatewayEvent.ToolProgress("terminal", "ls", "running", "c1"),
                HermesGatewayEvent.ToolProgress("terminal", "", "completed", "c1"),
                HermesGatewayEvent.TextDelta("There are 3 files."),
                HermesGatewayEvent.Finished("stop", null),
                HermesGatewayEvent.StreamEnded,
            ),
            events,
        )
    }

    // ── Error envelopes and status classification ─────────────────────────

    @Test
    fun parseErrorMessageReadsOpenAiEnvelope() {
        assertEquals(
            "Invalid gateway API key (API_SERVER_KEY)",
            HermesGatewayErrors.parseErrorMessage(
                """{"error":{"message":"Invalid gateway API key (API_SERVER_KEY)","type":"gateway_auth_error","code":"gateway_auth_failed"}}""",
            ),
        )
        assertEquals("plain failure", HermesGatewayErrors.parseErrorMessage("""{"error":"plain failure"}"""))
        assertNull(HermesGatewayErrors.parseErrorMessage("<html><body>502 Bad Gateway</body></html>"))
        assertNull(HermesGatewayErrors.parseErrorMessage(""))
        assertNull(HermesGatewayErrors.parseErrorMessage("""{"ok":true}"""))
    }

    @Test
    fun statusClassificationMatchesGatewayBehaviour() {
        assertTrue(HermesGatewayErrors.isRetryableStatus(502))
        assertTrue(HermesGatewayErrors.isRetryableStatus(503))
        assertTrue(HermesGatewayErrors.isRetryableStatus(504))
        assertFalse(HermesGatewayErrors.isRetryableStatus(500))
        assertFalse(HermesGatewayErrors.isRetryableStatus(404))
        assertTrue(HermesGatewayErrors.isAuthStatus(401))
        assertTrue(HermesGatewayErrors.isAuthStatus(403))
        assertFalse(HermesGatewayErrors.isAuthStatus(404))
    }

    // ── Baked gateway pre-connect (v26) ───────────────────────────────

    private val bakedGatewayUrl = "https://hermes-gateway-l14d.onrender.com"
    private val bakedGatewayKey = "baked-gateway-key"

    private fun preconnectResolve(
        configured: Boolean = false,
        storedRemote: Boolean = false,
        storedUrl: String = "",
        storedKey: String = "",
        bakedKey: String = bakedGatewayKey,
    ): HermesHostConfig = HermesGatewayPreconnect.resolve(
        configured = configured,
        storedRemote = storedRemote,
        storedUrl = storedUrl,
        storedKey = storedKey,
        bakedUrl = bakedGatewayUrl,
        bakedKey = bakedKey,
    )

    @Test
    fun preconnectExplicitChoiceDetectionMatrix() {
        // Nothing stored, no flag → no explicit choice (fresh install or
        // an update whose host prefs were never touched).
        assertFalse(HermesGatewayPreconnect.hasExplicitChoice(false, false, "", ""))
        // Each single trace of a host-card save counts as a choice.
        assertTrue(HermesGatewayPreconnect.hasExplicitChoice(true, false, "", ""))
        assertTrue(HermesGatewayPreconnect.hasExplicitChoice(false, true, "", ""))
        assertTrue(HermesGatewayPreconnect.hasExplicitChoice(false, false, "https://h.example.com", ""))
        assertTrue(HermesGatewayPreconnect.hasExplicitChoice(false, false, "", "user-key"))
        // Whitespace-only stored values are not a choice.
        assertFalse(HermesGatewayPreconnect.hasExplicitChoice(false, false, "  ", " "))
    }

    @Test
    fun preconnectDefaultsUnconfiguredInstallToTheBakedGateway() {
        val config = preconnectResolve()
        assertTrue(config.remote)
        assertEquals(bakedGatewayUrl, config.baseUrl)
        assertEquals(bakedGatewayKey, config.apiKey)
        assertTrue(
            HermesGatewayPreconnect.isActive(
                configured = false, storedRemote = false, storedUrl = "", storedKey = "",
                bakedKey = bakedGatewayKey,
            ),
        )
    }

    @Test
    fun preconnectStaysInactiveWhenTheBuildCarriesNoKey() {
        // Empty GATEWAY_KEY → exactly the pre-v26 manual flow: the stored
        // (here untouched) values come back verbatim.
        val config = preconnectResolve(bakedKey = "")
        assertFalse(config.remote)
        assertEquals("", config.baseUrl)
        assertEquals("", config.apiKey)
        assertFalse(
            HermesGatewayPreconnect.isActive(
                configured = false, storedRemote = false, storedUrl = "", storedKey = "",
                bakedKey = "",
            ),
        )
    }

    @Test
    fun preconnectRespectsAnExplicitLocalChoiceForever() {
        // "On this phone" saved on the host card (flag set, remote off):
        // the baked gateway must never override it.
        val config = preconnectResolve(configured = true, storedRemote = false)
        assertFalse(config.remote)
        assertEquals("", config.baseUrl)
        // A later remote pick is likewise the user's, down to the URL.
        val remote = preconnectResolve(configured = true, storedRemote = true, storedUrl = "https://my.host.example")
        assertTrue(remote.remote)
        assertEquals("https://my.host.example", remote.baseUrl)
    }

    @Test
    fun preconnectRespectsChoicesStoredBeforeTheFlagExisted() {
        // Pre-v26 installs have no configured flag; a stored remote mode
        // or URL is the trace of the user's choice and stands verbatim.
        val config = preconnectResolve(storedRemote = true, storedUrl = "https://old.host.example/")
        assertTrue(config.remote)
        assertEquals("https://old.host.example/", config.baseUrl)
        val urlOnly = preconnectResolve(storedUrl = "https://old.host.example")
        assertFalse(urlOnly.remote)
        assertEquals("https://old.host.example", urlOnly.baseUrl)
    }

    @Test
    fun preconnectUserEnteredKeyAlwaysWinsOverTheBakedKey() {
        // A stored vault key is itself an explicit trace, and it — never
        // the baked key — is what the client sends.
        val config = preconnectResolve(storedRemote = true, storedUrl = "https://my.host.example", storedKey = "user-key")
        assertEquals("user-key", config.apiKey)
        // With no stored key, the baked key only fills the blank, so a
        // configured gateway still authenticates.
        val filled = preconnectResolve(configured = true, storedRemote = true, storedUrl = "https://my.host.example")
        assertEquals(bakedGatewayKey, filled.apiKey)
    }

    @Test
    fun failureMessagesAvoidProviderKeyRotationTriggers() {
        // MainViewModel.retryWithNextApiKey rotates the *provider* key pool
        // when a failure reason contains one of these substrings; a remote
        // host failure must never trip it (the provider key lives on the host).
        val triggers = listOf("api key", "authentication", "user not found", "http 401", "http 403", "http 429", "expired", "quota", "rate limit")
        val messages = listOf(
            HermesRemoteMessages.KEY_REJECTED,
            HermesRemoteMessages.NO_URL,
            HermesRemoteMessages.NO_KEY,
            HermesRemoteMessages.UNREACHABLE,
            HermesRemoteMessages.STILL_WAKING,
            HermesRemoteMessages.DROPPED,
            HermesRemoteMessages.httpStatus(500),
        )
        messages.forEach { message ->
            val lower = message.lowercase()
            triggers.forEach { trigger ->
                assertFalse("'$message' must not contain '$trigger'", lower.contains(trigger))
            }
        }
    }
}
