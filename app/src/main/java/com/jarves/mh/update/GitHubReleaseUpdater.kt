package com.jarves.mh.update

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * GitHub-Releases update channel (2026-11-18): delivered builds are published
 * as releases of the user's own repo, tagged `v<versionCode>` (e.g. `v12`)
 * with exactly one `.apk` asset. This replaces the upstream author's JSON
 * manifest as the production source of truth, so the app updates from the
 * user's builds — same signing key, so [AppUpdater.download]'s signature
 * verification passes — and the user never has to sideload from chat again.
 *
 * The parsing/comparison logic is pure and unit-tested; the client below is
 * a thin HttpURLConnection wrapper (no auth — the repo is public), matching
 * the networking idiom used across the app.
 */
object GitHubReleaseUpdate {

    const val RELEASES_API_URL =
        "https://api.github.com/repos/radhesolanki319-pixel/Hermes-Agent-Mobile-App/releases?per_page=30"

    /** Release tag → versionCode: "v12" → 12, "12" → 12, anything else → null. */
    fun parseVersionCode(tagName: String?): Long? =
        tagName?.trim()?.removePrefix("v")?.removePrefix("V")?.toLongOrNull()

    /** An update exists only when the release is strictly newer than the install. */
    fun isUpdateAvailable(installedVersionCode: Long, remoteVersionCode: Long): Boolean =
        remoteVersionCode > installedVersionCode

    /**
     * Parse a `/releases` LIST response body: among all published releases,
     * pick the highest `v<versionCode>` tag that is strictly newer than the
     * install. Runtime-bundle releases (`hermes-bundle-*`, `core-bundle-*`)
     * share this repo and their tags do not parse as version codes, so they
     * are skipped. Listing is deliberate: `/releases/latest` returns
     * whichever release was CREATED most recently, so a bundle release
     * published after an app release used to hide that update entirely
     * (v40, 2026-11-20 — the app reported "on the latest version" while
     * v40 was live, because core-bundle-2026.11.1 was created after it).
     */
    fun parseReleaseList(body: String, installedVersionCode: Long): AppUpdateInfo? {
        val releases = JSONArray(body)
        var best: AppUpdateInfo? = null
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
            val info = parseRelease(release, installedVersionCode) ?: continue
            if (best == null || info.versionCode > best.versionCode) best = info
        }
        return best
    }

    /** Parse a single-release response body (kept for compatibility). */
    fun parseLatestRelease(body: String, installedVersionCode: Long): AppUpdateInfo? =
        parseRelease(JSONObject(body), installedVersionCode)

    /**
     * Parse one release object into an [AppUpdateInfo], or null when the
     * release is not newer, has no `.apk` asset, or the asset URL is not
     * HTTPS. The release carries no checksum, so `sha256` stays blank and
     * [AppUpdater.download] skips its checksum step (its package /
     * version / signature verification still applies).
     */
    private fun parseRelease(root: JSONObject, installedVersionCode: Long): AppUpdateInfo? {
        val remoteVersionCode = parseVersionCode(root.optString("tag_name")) ?: return null
        if (!isUpdateAvailable(installedVersionCode, remoteVersionCode)) return null
        val assets = root.optJSONArray("assets") ?: return null

        // Collect all .apk assets first, then pick by priority:
        // 1. Name contains "online" (case-insensitive)
        // 2. Name matches "Hermes_Agent_v<N>.apk" pattern
        // 3. First .apk asset
        val apkAssets = mutableListOf<Pair<String, Long>>()
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                val url = asset.optString("browser_download_url")
                val size = asset.optLong("size", -1L)
                apkAssets.add(url to size)
            }
        }

        var apkUrl = ""
        var apkSize = -1L
        // Priority 1: contains "online"
        val onlineAsset = apkAssets.firstOrNull { (url, _) -> url.contains("online", ignoreCase = true) }
        if (onlineAsset != null) {
            apkUrl = onlineAsset.first
            apkSize = onlineAsset.second
        } else {
            // Priority 2: matches Hermes_Agent_v<N>.apk
            val versionAsset = apkAssets.firstOrNull { (url, _) ->
                Regex("Hermes_Agent_v\\d+\\.apk\$", RegexOption.IGNORE_CASE).containsMatchIn(url)
            }
            if (versionAsset != null) {
                apkUrl = versionAsset.first
                apkSize = versionAsset.second
            } else if (apkAssets.isNotEmpty()) {
                // Priority 3: first .apk asset
                apkUrl = apkAssets[0].first
                apkSize = apkAssets[0].second
            } else {
                return null
            }
        }

        if (!apkUrl.startsWith("https://")) return null
        return AppUpdateInfo(
            versionCode = remoteVersionCode,
            versionName = root.optString("name").ifBlank {
                root.optString("tag_name").removePrefix("v").removePrefix("V")
            },
            apkUrl = apkUrl,
            sha256 = "",
            sizeBytes = apkSize,
            notes = root.optString("body"),
        )
    }
}

/** One-shot check against the GitHub Releases API; null on any failure. */
class GitHubReleaseUpdater {

    fun check(installedVersionCode: Long): AppUpdateInfo? {
        val connection = URL(GitHubReleaseUpdate.RELEASES_API_URL).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub API requires a User-Agent header; without it, requests may be rejected with 403.
            connection.setRequestProperty("User-Agent", "Hermes-Agent-App-Updater")
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            GitHubReleaseUpdate.parseReleaseList(body, installedVersionCode)
        } finally {
            connection.disconnect()
        }
    }
}
