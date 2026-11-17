package com.jarves.mh.runtime

private val HERMES_PROJECT_VERSION_LINE =
    Regex("""version\s*=\s*["']([^"']+)["']\s*(?:#.*)?""")

private val HERMES_RELEASE_VERSION_PATTERN =
    Regex("[0-9]+\\.[0-9]+\\.[0-9]+[0-9A-Za-z.+-]*")

/**
 * Extracts the `[project]` version from a pyproject.toml document, or null
 * when the section or the key is absent. Only the project table is read:
 * dependency pins and tool tables further down the file also carry
 * `version = ...` lines and must not leak into the result. A raw Hermes
 * source checkout reports the placeholder version `0.0.0` here; that is the
 * honest value to record for it (see the source-install route in
 * [RuntimeInstaller]).
 */
internal fun parseHermesProjectVersion(pyprojectText: String): String? {
    var inProjectSection = false
    for (rawLine in pyprojectText.lineSequence()) {
        val line = rawLine.trim()
        if (line.startsWith("[")) {
            inProjectSection = line == "[project]"
            continue
        }
        if (!inProjectSection) continue
        val match = HERMES_PROJECT_VERSION_LINE.matchEntire(line)
        if (match != null) return match.groupValues[1]
    }
    return null
}

/**
 * Whether [version] is a plain numeric release version (optionally carrying
 * a pre-release or build suffix such as `rc1` or `+src`). Update installs
 * interpolate the version into the guest shell command
 * (`hermes-agent[acp]==<version>`), so anything that is not shaped like a
 * release version is refused before it can reach the shell.
 */
internal fun isHermesReleaseVersion(version: String): Boolean =
    version.matches(HERMES_RELEASE_VERSION_PATTERN)
