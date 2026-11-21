package com.jarves.mh.termux

// Ported from AndroidHarness (Sanuu7/AndroidHarness, MIT).

data class PkgMeta(
    val name: String,
    val version: String,
    val depends: List<String>,
    val filename: String,
    val size: Long,
    val sha256: String,
    val description: String = "",
    val installedSize: Long = 0L,
)

/** Parses the Debian Packages index served by the Termux repository. */
object PackageIndex {
    fun parse(text: String): Map<String, PkgMeta> {
        val out = mutableMapOf<String, PkgMeta>()
        text.split(Regex("\\n\\n")).forEach { block ->
            val fields = mutableMapOf<String, String>()
            var current: String? = null
            block.lines().forEach { line ->
                when {
                    line.isEmpty() -> Unit
                    line.startsWith(" ") -> {
                        val key = current ?: return@forEach
                        fields[key] = (fields[key] ?: "") + "\n" + line.trim()
                    }
                    else -> {
                        val i = line.indexOf(':')
                        if (i > 0) {
                            current = line.substring(0, i)
                            fields[current!!] = line.substring(i + 1).trim()
                        }
                    }
                }
            }
            val name = fields["Package"] ?: return@forEach
            val filename = fields["Filename"] ?: return@forEach
            val depends = (fields["Depends"] ?: "")
                .split(',')
                .map { it.trim().substringBefore('(').trim().split('|').first().trim() }
                .filter { it.isNotBlank() }
            val desc = fields["Description"]?.lines()?.firstOrNull()?.trim().orEmpty()
            out[name] = PkgMeta(
                name = name,
                version = fields["Version"] ?: "",
                depends = depends,
                filename = filename,
                size = fields["Size"]?.toLongOrNull() ?: 0L,
                sha256 = fields["SHA256"] ?: "",
                description = desc,
                installedSize = fields["Installed-Size"]?.toLongOrNull() ?: 0L,
            )
        }
        return out
    }
}

/**
 * Termux packages ship absolute symlinks into their own build prefix
 * (/data/data/com.termux/files/usr/...); inside this app's prefix those
 * dangle (bzcmp, bzless, and many man-page links). Rewrites them as paths
 * relative to the link's directory, pointing at the same file inside our
 * prefix, which also keeps the installed tree relocatable for the
 * shell-user re-deploy.
 */
internal object TermuxLinkRewrite {
    private const val TERMUX_USR = "/data/data/com.termux/files/usr/"

    /**
     * @param linkName raw symlink target from the package archive
     * @param linkPath where the symlink itself lives inside the prefix (e.g. "bin/bzcmp")
     * @return rewritten relative target, or null when nothing needs rewriting
     */
    fun relativeTarget(linkName: String, linkPath: String): String? {
        if (!linkName.startsWith(TERMUX_USR)) return null
        val inside = linkName.removePrefix(TERMUX_USR)
        val depth = linkPath.trim('/').split('/').dropLast(1).count { it.isNotEmpty() }
        return "../".repeat(depth) + inside
    }
}

/**
 * Termux packages ship scripts whose shebang points into the Termux build
 * prefix (/data/data/com.termux/files/usr/...). Inside this app those paths
 * never exist, so the scripts have to be fixed at extraction:
 *
 *  - the Android-bridge wrapper commands (termux-tools: pm, cmd, am, settings,
 *    …) are dead weight here, they shadow the real /system binaries and die
 *    with exit 126, so they are dropped;
 *  - language tooling (bin/pip3, lib/node_modules/npm/bin/npm-cli.js, git's
 *    libexec helper scripts, …) is genuinely needed, and the SHELL-uid tier
 *    execs scripts directly (no linker shims exist there, so the kernel reads
 *    the shebang). Their first line is rewritten to point into the deployed
 *    prefix at /data/local/tmp, where the interpreter ELF is directly
 *    executable. The app-uid tier never consults shebangs (W^X exec is
 *    blocked and every command goes through the linker shims), so the same
 *    file works in both tiers.
 */
internal object TermuxShebangs {
    private const val TERMUX_USR = "/data/data/com.termux/files/usr/"

    /** Match marker for any Termux-prefixed shebang (even outside files/usr). */
    const val TERMUX_PREFIX_MARK = "#!/data/data/com.termux/"

    /** Android-bridge wrapper commands shipped by termux-tools. */
    val SYSTEM_WRAPPERS = setOf(
        "am", "bmgr", "bu", "cmd", "content", "device_config", "dpm", "dumpsys",
        "getprop", "ime", "input", "log", "logcat", "media", "mount", "notify",
        "pm", "settings", "setprop", "sm", "svc", "uiautomator", "umount",
        "wm", "start", "stop",
    )

    // cmd is also shipped as an ELF bridge; termux-am needs Termux's socket
    // service, which Harness does not run. Use Android's system commands.
    fun isUnsupportedBridge(name: String): Boolean = name in setOf("cmd", "termux-am", "termux-am-socket")

    /**
     * True when [name] is a termux-tools Android-bridge wrapper. The wrappers
     * are POSIX shell scripts, they all carry a Termux bin/sh shebang, so a
     * non-shell script that happens to share a name (pm, log, …) is tooling
     * that should be rewritten, not dropped.
     */
    fun isWrapperScript(name: String, firstLine: String): Boolean =
        firstLine.startsWith("#!${TERMUX_USR}bin/sh") && name in SYSTEM_WRAPPERS

    /**
     * Rewrites a Termux-absolute shebang into [deployedPrefix] (this build's
     * deployed copy); null when the line cannot be repaired (not under
     * files/usr, or still references the Termux prefix after rewriting).
     * Env-style launchers use the bundled coreutils env in bin/env. Keeping
     * it in the same prefix also preserves library paths for Node subprocesses.
     * Repair older rewrites that assumed busybox supplied bin/applets/env.
     */
    fun rewrittenFirstLine(firstLine: String, deployedPrefix: String): String? {
        var line = when {
            firstLine.startsWith("#!$TERMUX_USR") ->
                "#!$deployedPrefix/" + firstLine.removePrefix("#!$TERMUX_USR")
            firstLine.startsWith("#!$deployedPrefix/bin/applets/env ") -> firstLine
            else -> return null
        }
        line = line.replaceFirst("#!$deployedPrefix/bin/applets/env ", "#!$deployedPrefix/bin/env ")
        return if (line.contains("/data/data/com.termux")) null else line
    }
}

/**
 * Termux's git embeds its SHELL_PATH (/data/data/com.termux/files/usr/bin/sh)
 * in the ELF: git runs helpers, hooks and aliases through `sh -c` using that
 * literal, and outside Termux the path never exists, so every spawn dies with
 * "cannot exec" (gh repo clone's credential helper, for example). No env var
 * can redirect it (unlike GIT_EXEC_PATH/GIT_TEMPLATE_DIR), so the string is
 * patched in place at extraction: a same-length replacement keeps the ELF's
 * layout intact and NUL padding terminates the shorter path. /system/bin/sh
 * is exec-able from both shell tiers.
 */
internal object TermuxShellPath {
    private const val TERMUX_SHELL = "/data/data/com.termux/files/usr/bin/sh"
    private const val NEUTRAL_SHELL = "/system/bin/sh"
    private val NEEDLE = TERMUX_SHELL.toByteArray(Charsets.UTF_8)
    private val REPLACEMENT = NEUTRAL_SHELL.toByteArray(Charsets.UTF_8)

    /** True when [relPath] is git territory whose ELFs may embed SHELL_PATH. */
    fun appliesTo(relPath: String): Boolean =
        relPath.startsWith("bin/git") || relPath.startsWith("libexec/git-core/")

    /** Replaces every occurrence in [bytes] in place; returns how many. */
    fun patchBytes(bytes: ByteArray): Int {
        var count = 0
        var i = 0
        val last = bytes.size - NEEDLE.size
        while (i <= last) {
            var j = 0
            while (j < NEEDLE.size && bytes[i + j] == NEEDLE[j]) j++
            if (j == NEEDLE.size) {
                REPLACEMENT.copyInto(bytes, i)
                for (k in REPLACEMENT.size until NEEDLE.size) bytes[i + k] = 0
                count++
                i += NEEDLE.size
            } else {
                i++
            }
        }
        return count
    }

    /**
     * Patches [file] in place; returns the number of occurrences rewritten.
     * ELF-gated: git's scripts under libexec/ are handled by the shebang
     * rewriter, and a text file containing the literal must not be touched.
     */
    fun patch(file: File): Int = runCatching {
        val bytes = file.readBytes()
        if (bytes.size < 4 || bytes[0] != 0x7f.toByte() || bytes[1] != 'E'.code.toByte() ||
            bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
        ) {
            return 0
        }
        val n = patchBytes(bytes)
        if (n > 0) file.writeBytes(bytes)
        n
    }.getOrDefault(0)
}
