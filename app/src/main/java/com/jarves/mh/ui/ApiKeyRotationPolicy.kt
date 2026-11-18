package com.jarves.mh.ui

import com.jarves.mh.model.AgentKind

/**
 * The pure decision behind provider-key rotation (extracted in v29 so the
 * gating is unit-testable; [MainViewModel.retryWithNextApiKey] keeps the
 * session-state checks and the actual key swap).
 *
 * Rotation swaps the active key in the provider's unified pool for the
 * next one and re-runs the turn — sound only when the failure actually
 * indicts a key the PHONE holds. Two kinds of turn must never enter that
 * path:
 *
 *  - Antigravity: it authenticates with Google sign-in, not pool keys
 *    (this guard predates the extraction and is unchanged).
 *  - A remote Hermes turn: the turn ran on the Hermes gateway host, and
 *    the gateway relays the HOST provider's error text verbatim
 *    ("The Hermes host reported an error: …"). A host-side FreeLLM
 *    rate-limit, quota or key error therefore contains the very
 *    substrings [isApiKeyFailure] keys on — but the failing key lives on
 *    the host, so rotating the phone's pool, showing the "key switched"
 *    toast and re-running the remote turn would only repeat the same
 *    failure until the phone's pool is exhausted. The failure surfaces
 *    as-is instead, through the remote host messages.
 */
internal object ApiKeyRotationPolicy {

    /**
     * Failure-text substrings that mark a provider-key failure. Verbatim
     * from the pre-v29 MainViewModel check — the remote host messages are
     * deliberately worded to avoid every one of these.
     */
    fun isApiKeyFailure(reason: String): Boolean {
        val value = reason.lowercase()
        return "api key" in value || "authentication" in value || "user not found" in value ||
            "http 401" in value || "http 403" in value || "http 429" in value ||
            "expired" in value || "quota" in value || "rate limit" in value
    }

    /**
     * Whether a failed turn may rotate the phone's provider key and
     * re-run: only a non-Antigravity, non-remote turn whose failure text
     * indicts the key. [remoteHermesTurn] is the caller's
     * isRemoteHermesTurn decision for the failed turn's agent.
     */
    fun shouldRotate(agentKind: AgentKind, remoteHermesTurn: Boolean, reason: String): Boolean {
        if (agentKind == AgentKind.ANTIGRAVITY) return false
        if (remoteHermesTurn) return false
        return isApiKeyFailure(reason)
    }
}
