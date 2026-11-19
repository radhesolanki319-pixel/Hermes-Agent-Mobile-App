package com.jarves.mh.update

import java.net.HttpURLConnection
import java.net.URL
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

    const val LATEST_RELEASE_API_URL =
        "https://api.github.com/repos/radhesolanki319-pixel/Mobile-Harness/releases/latest"

    /** Release tag → versionCode: "v12" → 12, "12" → 12, anything else → null. */
    fun parseVersionCode(tagName: String?): Long? =
        tagName?.trim()?.removePrefix("v")?.removePrefix("V")?.toLongOrNull()

    /** An update exists only when the release is strictly newer than the install. */
    fun isUpdateAvailable(installedVersionCode: Long, remoteVersionCode: Long): Boolean =
        remoteVersionCode > installedVersionCode

    /**
     * Parse a `/releases/latest` response body into an [AppUpdateInfo], or
     * null when the release is not newer, has no `.apk` asset, or the asset
     * URL is not HTTPS. The release carries no checksum, so `sha256` stays
     * blank and [AppUpdater.download] skips its checksum step (its package /
     * version / signature verification still applies).
     */
    fun parseLatestRelease(body: String, installedVersionCode: Long): AppUpdateInfo? {
        val root = JSONObject(body)
        val remoteVersionCode = parseVersionCode(root.optString("tag_name")) ?: return null
        if (!isUpdateAvailable(installedVersionCode, remoteVersionCode)) return null
        val assets = root.optJSONArray("assets") ?: return null
        var apkUrl = ""
        var apkSize = -1L
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                apkUrl = asset.optString("browser_download_url")
                apkSize = asset.optLong("size", -1L)
                break
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
        val connection = URL(GitHubReleaseUpdate.LATEST_RELEASE_API_URL).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub API requires a User-Agent header; without it, requests may be rejected with 403.
            connection.setRequestProperty("User-Agent", "Hermes-Agent-App-Updater")
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            GitHubReleaseUpdate.parseLatestRelease(body, installedVersionCode)
        } finally {
            connection.disconnect()
        }
    }
}
