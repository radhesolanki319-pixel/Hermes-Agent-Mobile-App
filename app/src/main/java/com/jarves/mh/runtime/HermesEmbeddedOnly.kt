package com.jarves.mh.runtime

/**
 * v31: Hermes is EMBEDDED ONLY. At the user's order ("Woh render wala
 * computer hata do" — remove the Render computer), the remote gateway
 * route is gone: the full in-app Hermes Agent is the only Hermes the app
 * talks to, and this file is the seam that pins that invariant.
 *
 * What used to live here (in the deleted HermesRemoteClient.kt): the
 * v26 gateway pre-connect resolution, the remote chat client, and the
 * host prefs they read. All of it is removed. What remains is the pure,
 * testable core of the new world:
 *
 *  - [HermesEmbeddedOnly.chatHostConfig] — the chat host resolution.
 *    Whatever an install has stored (a host-card-era remote mode, URL or
 *    vault key, a stale foreign URL) and whatever a build once baked
 *    in, the chat route resolves EMBEDDED. Stored gateway prefs are
 *    inert data now: left in place, never read for routing.
 *  - [HermesEmbeddedOnly.shouldMigrateEmbeddedOnly] /
 *    [HermesEmbeddedOnly.migrateHostPrefs] — the one-time migration
 *    that flips an existing install's stored host mode to embedded on
 *    first v31 launch, preserving everything else byte-for-byte.
 */

/** Where Hermes runs for a chat turn. Since v31 the answer is always this phone. */
data class HermesHostConfig(
    val remote: Boolean,
    val baseUrl: String,
    val apiKey: String,
)

internal object HermesEmbeddedOnly {

    /** The one chat host config v31 resolves: the embedded, in-app Hermes. */
    val EMBEDDED: HermesHostConfig = HermesHostConfig(remote = false, baseUrl = "", apiKey = "")

    /**
     * The chat host resolution for every install — fresh, migrated, or
     * carrying any stored remote prefs from the host-card / pre-connect
     * era. The stored values are accepted (so call sites and tests can
     * present the full matrix of what an install may hold) and are
     * deliberately inert: the result is always [EMBEDDED].
     */
    fun chatHostConfig(
        storedRemote: Boolean,
        storedUrl: String,
    ): HermesHostConfig = EMBEDDED

    /** The embedded-only migration runs exactly once per install. */
    fun shouldMigrateEmbeddedOnly(flagConsumed: Boolean): Boolean = !flagConsumed

    /** The stored host prefs the migration reasons about. */
    data class HostPrefs(
        val remote: Boolean,
        val url: String,
        val configured: Boolean,
    )

    /**
     * The migration's write set: the stored host mode flips to embedded
     * (false); the stored gateway URL and the configured flag pass
     * through untouched — they are the user's data, left in place but
     * unused, and no provider profile, model, or key anywhere is part of
     * this snapshot because the migration never goes near them.
     */
    fun migrateHostPrefs(stored: HostPrefs): HostPrefs = stored.copy(remote = false)
}
