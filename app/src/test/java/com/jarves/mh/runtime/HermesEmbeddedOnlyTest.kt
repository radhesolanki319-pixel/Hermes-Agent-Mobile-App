package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v31: Hermes is embedded only. These tests replace the v26 pre-connect
 * and v28 stale-URL-guard suites: where those pinned WHICH remote config
 * wins, these pin that there is no remote config in chat at all — the
 * chat route resolves embedded for every install, whatever it has
 * stored — plus the one-time migration that flips an existing install's
 * stored host mode while preserving everything else.
 */
class HermesEmbeddedOnlyTest {

    // ── Chat route: always embedded ───────────────────────────────────

    @Test
    fun freshInstallResolvesEmbedded() {
        val config = HermesEmbeddedOnly.chatHostConfig(storedRemote = false, storedUrl = "")
        assertFalse(config.remote)
        assertEquals(HermesEmbeddedOnly.EMBEDDED, config)
    }

    @Test
    fun storedRemoteChoiceResolvesEmbedded() {
        // Host-card era: the user once picked remote and saved a URL.
        val config = HermesEmbeddedOnly.chatHostConfig(
            storedRemote = true,
            storedUrl = "https://hermes-gateway-l14d.onrender.com",
        )
        assertFalse(config.remote)
        assertEquals("", config.baseUrl)
        assertEquals("", config.apiKey)
    }

    @Test
    fun staleForeignUrlResolvesEmbedded() {
        // The v28 guard's old battleground: a stored URL on a foreign
        // host. It no longer matters — no stored URL routes anywhere.
        val config = HermesEmbeddedOnly.chatHostConfig(
            storedRemote = true,
            storedUrl = "https://typo-114d.example.com",
        )
        assertFalse(config.remote)
    }

    @Test
    fun everyStoredCombinationResolvesEmbedded() {
        for (remote in listOf(false, true)) {
            for (url in listOf("", "https://hermes-gateway-l14d.onrender.com", "http://192.168.1.10:8642")) {
                assertEquals(
                    HermesEmbeddedOnly.EMBEDDED,
                    HermesEmbeddedOnly.chatHostConfig(storedRemote = remote, storedUrl = url),
                )
            }
        }
    }

    // ── One-time migration ────────────────────────────────────────────

    @Test
    fun migrationFiresExactlyOnce() {
        assertTrue(HermesEmbeddedOnly.shouldMigrateEmbeddedOnly(flagConsumed = false))
        assertFalse(HermesEmbeddedOnly.shouldMigrateEmbeddedOnly(flagConsumed = true))
    }

    @Test
    fun migrationFlipsRemoteModeAndPreservesEverythingElse() {
        val stored = HermesEmbeddedOnly.HostPrefs(
            remote = true,
            url = "https://hermes-gateway-l14d.onrender.com",
            configured = true,
        )
        val migrated = HermesEmbeddedOnly.migrateHostPrefs(stored)
        assertFalse(migrated.remote)
        // The stored gateway URL and the configured flag are the user's
        // data: preserved byte-for-byte, simply unused from now on.
        assertEquals(stored.url, migrated.url)
        assertEquals(stored.configured, migrated.configured)
    }

    @Test
    fun migrationLeavesAnEmbeddedInstallUntouched() {
        val stored = HermesEmbeddedOnly.HostPrefs(remote = false, url = "", configured = false)
        assertEquals(stored, HermesEmbeddedOnly.migrateHostPrefs(stored))
    }

    @Test
    fun migratedInstallResolvesEmbedded() {
        // End to end at the seam: an install that stored remote mode is
        // migrated, and its chat resolution is embedded before and after
        // (the migration makes the stored record honest; the resolution
        // never depended on it).
        val stored = HermesEmbeddedOnly.HostPrefs(remote = true, url = "https://host.example", configured = true)
        assertFalse(HermesEmbeddedOnly.chatHostConfig(stored.remote, stored.url).remote)
        val migrated = HermesEmbeddedOnly.migrateHostPrefs(stored)
        assertFalse(HermesEmbeddedOnly.chatHostConfig(migrated.remote, migrated.url).remote)
    }
}
