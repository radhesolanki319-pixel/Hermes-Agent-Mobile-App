package com.jarves.mh.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.system.Os
import android.util.Log
import com.jarves.mh.BuildConfig
import com.jarves.mh.R
import com.jarves.mh.data.AppPreferences
import com.jarves.mh.data.McpServerConfig
import com.jarves.mh.data.McpServersPrefs
import com.jarves.mh.ui.AppStrings
import com.jarves.mh.ui.localizedLabel
import com.jarves.mh.ui.localizedTitle
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import com.jarves.mh.model.DevStack
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.json.JSONObject
import com.jarves.mh.termux.TermuxEnvironment

data class InstalledRuntime(
    val proot: File,
    val rootfs: File,
)

data class RuntimeInstallProgress(
    val message: String,
    val fraction: Float,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val terminalLine: String? = null,
    val indeterminate: Boolean = false,
    val event: RuntimeInstallEvent = RuntimeInstallEvent.STAGE,
)

data class AgentUpdateInfo(
    val installedVersion: String,
    val latestVersion: String,
)

enum class RuntimeInstallEvent { STAGE, COMMAND, OUTPUT, DOWNLOAD, COMMAND_COMPLETED, COMPLETED }

private data class RuntimeBundle(
    val label: String,
    val fileName: String,
    val sha256: String,
    val compressedBytes: Long,
    // Per-bundle download source for the network path, when the bundle is not
    // published under BuildConfig.RUNTIME_RELEASE_BASE_URL (Hermes lives in
    // the user's own repo — see HERMES_BUNDLE). Null = the shared base URL.
    val releaseBaseUrl: String? = null,
)

class RuntimeInstaller(private val context: Context) {
    private val runtimeDir = File(context.filesDir, "runtime")
    private val rootfs = File(runtimeDir, "ubuntu")
    private val downloads = File(context.cacheDir, "runtime-downloads")
    private val coreReadyMarker = File(rootfs, ".pocket-runtime-ready")
    private val claudeMarker = File(rootfs, ".pocket-claude-version")
    // Read only for migration from Core bundles that embedded Claude Code.
    private val bundledClaudeMarker = File(rootfs, ".pocket-bundled-claude-version")
    private val rootfsMarker = File(rootfs, ".pocket-rootfs-version")
    private val languageToolsMarker = File(rootfs, ".pocket-language-tools-version")
    private val coreToolsMarker = File(rootfs, ".pocket-core-tools-version")
    private val systemUpgradeMarker = File(rootfs, ".pocket-system-upgrade-version")
    private val devStacksFile = File(rootfs, ".pocket-dev-stacks.json")
    private val agyMarker = File(rootfs, ".pocket-agy-version")
    private val hermesMarker = File(rootfs, ".pocket-hermes-version")
    private val uvMarker = File(rootfs, ".pocket-uv-version")
    private val githubCliMarker = File(rootfs, ".pocket-github-cli-version")
    private val macosMetadataRepairMarker = File(rootfs, ".pocket-macos-metadata-repair")

    /** Resolves a user-visible installer label in the language picked inside the app. */
    private fun str(@androidx.annotation.StringRes id: Int, vararg args: Any): String =
        AppStrings.get(context, id, *args)

    fun isInstalled(): Boolean {
        // Stage E: Termux is the only runtime.
        return TermuxEnvironment(context).isInstalled()
    }

    /**
     * One-shot cleanup for devices that already extracted a tarball built on
     * macOS without the `--no-mac-metadata` flag. Such bundles contain
     * `._filename` AppleDouble metadata files that crash Python 3.8 when it
     * reads every `.pth` file in site-packages. After the first successful
     * cleanup we write a marker so we never walk the entire rootfs again.
     */
    private fun repairLegacyMacosMetadata() {
        if (macosMetadataRepairMarker.isFile) return
        if (!rootfs.isDirectory) return
        stripMacosMetadataArtifacts(rootfs)
        macosMetadataRepairMarker.parentFile?.mkdirs()
        macosMetadataRepairMarker.writeText("1")
    }

    /** Returns the already verified runtime without performing network or update checks. */
    fun installedRuntime(): InstalledRuntime {
        check(isInstalled()) { str(R.string.rt_install_incomplete) }
        return InstalledRuntime(
            proot = File(context.applicationInfo.nativeLibraryDir, "libproot.so"),
            rootfs = rootfs,
        )
    }

    /** Removes only scaffolding written automatically by earlier PocketDev alpha builds. */
    fun cleanupLegacyWorkspaceScaffolding() {
        val workspaces = File(context.filesDir, "workspaces")
        workspaces.listFiles { file -> file.isDirectory }.orEmpty().forEach { workspace ->
            File(workspace, "README.md").deleteIfExact(LEGACY_README)
            File(workspace, "index.html").deleteIfExact(LEGACY_INDEX)

            listOf(
                File(workspace, ".claude/settings.json"),
                File(workspace, ".claude.json"),
            ).forEach { settings ->
                if (settings.isFile && settings.readTextOrNull()?.contains("/opt/pocket/permission-hook.sh") == true) {
                    settings.delete()
                }
            }
            File(workspace, ".claude").takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete()
        }
    }

    private fun File.deleteIfExact(expected: String) {
        if (isFile && runCatching { readText() }.getOrNull() == expected) delete()
    }

    suspend fun ensureInstalled(
        selectedStacks: Set<DevStack> = emptySet(),
        agent: com.jarves.mh.model.AgentKind? = null,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ): InstalledRuntime {
        require(
            supportsArm64Runtime(
                android.os.Build.SUPPORTED_ABIS,
                System.getProperty("os.arch"),
            ),
        ) { str(R.string.rt_install_unsupported_arch) }
        // Termux setup (Stage D): the whole first-run setup is now the
        // Termux package install + Hermes — the user should never have to
        // install pieces by hand from Settings/Agent screens.
        val termux = TermuxEnvironment(context)
        if (!termux.isFullyInstalled() || !isAgentInstalled(com.jarves.mh.model.AgentKind.HERMES)) {
            if (bundledAssetPresent("hermes-bionic.bin")) {
                installHermesFromApkBundle(termux, onProgress)
            }
        }
        if (!termux.isFullyInstalled()) {
            termux.install(TermuxEnvironment.fullPackages) { line ->
                onProgress(RuntimeInstallProgress(line, 0.4f))
            }.exceptionOrNull()?.let { throw it }
        } else {
            termux.repairIfNeeded()
        }
        if (!isAgentInstalled(com.jarves.mh.model.AgentKind.HERMES)) {
            installHermesOnTermux(termux, onProgress)
        }
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_setup_complete), 1f))
        return InstalledRuntime(termux.prefix, termux.prefix)
    }

    /**
     * Installs one coding agent on demand. Safe to call again: an already-installed
     * agent returns immediately without network access. Every coding agent is a
     * separate overlay and is fetched or loaded only when selected.
     */
    /**
     * v57: force a FRESH Hermes install of the pinned bundle, replacing
     * whatever version is currently in the guest. A stale pre-installed
     * Hermes (e.g. 0.23.2 left in the rootfs from before v48) otherwise
     * satisfies [isAgentInstalled] forever and can never be replaced from
     * the UI — and its gateway control-socket peer check
     * (unsafe_control_peer) cannot pass under PRoot, so its turns fail.
     * Dropping the marker makes [ensureHermesInstalled] take the clean
     * install path, which wipes /root/.pocket-hermes first; the Hermes
     * data dir (/root/.hermes) is preserved.
     */
    suspend fun reinstallHermes(onProgress: suspend (RuntimeInstallProgress) -> Unit) {
        // Termux: wipe the clone + marker and reinstall from the bundle or repo.
        val termux = TermuxEnvironment(context)
        if (termux.isInstalled()) {
            termuxHermesMarker(termux).delete()
            File(termux.prefix, "home/hermes").deleteRecursively()
            if (bundledAssetPresent("hermes-bionic.bin") || bundledAssetPresent("hermes.bin")) {
                installHermesFromApkBundle(termux, onProgress)
            } else {
                installHermesOnTermux(termux, onProgress)
            }
            onProgress(RuntimeInstallProgress(str(R.string.rt_install_ready, com.jarves.mh.model.AgentKind.HERMES.localizedTitle(context)), 1f))
            return
        }
        val runtime = installedRuntime()
        hermesMarker.delete()
        ensureHermesInstalled(runtime.proot, 0.05f, onProgress)
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_ready, com.jarves.mh.model.AgentKind.HERMES.localizedTitle(context)), 1f))
    }

    suspend fun reinstallClaude(onProgress: suspend (RuntimeInstallProgress) -> Unit) {
        if (CLAUDE_DISABLED) {
            onProgress(RuntimeInstallProgress("Claude Code abhi band hai — pehle Hermes pakka ho raha hai", 1f))
            return
        }
        // Termux: wipe the wrapper + binary + marker + caches and reinstall.
        val termux = TermuxEnvironment(context)
        if (termux.isInstalled()) {
            purgeClaudeFiles(termux)
            installClaudeOnTermux(termux, onProgress)
            return
        }
        val runtime = installedRuntime()
        claudeMarker.delete()
        File(rootfs, CLAUDE_GUEST_PATH.removePrefix("/")).delete()
        ensureAgentInstalled(com.jarves.mh.model.AgentKind.CLAUDE_CODE, onProgress)
    }

    /** Fully wipes all Claude Code binaries, wrappers, markers, and glibc runner leftovers. */
    fun purgeClaudeFiles(termux: TermuxEnvironment) {
        runCatching {
            termuxClaudeMarker(termux).delete()
            File(termux.prefix, "bin/claude").delete()
            File(termux.prefix, "bin/claude-real").delete()
            File(termux.prefix, "bin/grun").delete()
            File(termux.prefix, "glibc/bin/grun").delete()
            File(termux.prefix, "home/.claude").deleteRecursively()
            File(termux.prefix, "home/.claude.json").delete()
            File(downloads, "claude-real.bin").delete()
            File(downloads, "glibc-runtime.bin").delete()
        }
    }

    /** Where Termux-side Hermes records its install (content = HERMES_VERSION). */
    private fun termuxHermesMarker(termux: TermuxEnvironment): File =
        File(termux.prefix, ".hermes-installed")

    private fun termuxHermesBinary(termux: TermuxEnvironment): File =
        File(termux.prefix, "home/hermes/.venv/bin/hermes")

    /** Where Termux-side Claude Code records its installed version. */
    private fun termuxClaudeMarker(termux: TermuxEnvironment): File =
        File(termux.prefix, ".claude-installed")

    suspend fun ensureAgentInstalled(
        agent: com.jarves.mh.model.AgentKind,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        // Termux: Hermes installs from the user's own GitHub repo via
        // git clone + uv sync — no bundle, no PRoot.
        val termux = TermuxEnvironment(context)
        if (agent == com.jarves.mh.model.AgentKind.HERMES && termux.isInstalled()) {
            if (!isAgentInstalled(agent)) installHermesOnTermux(termux, onProgress)
            return
        }
        if (agent == com.jarves.mh.model.AgentKind.CLAUDE_CODE && termux.isInstalled()) {
            if (CLAUDE_DISABLED) {
                onProgress(RuntimeInstallProgress("Claude Code abhi band hai — pehle Hermes pakka ho raha hai", 1f))
                return
            }
            if (!isAgentInstalled(agent)) installClaudeOnTermux(termux, onProgress)
            return
        }
        val runtime = installedRuntime()
        when (agent) {
            // Claude Code installs the latest release live from npm/Anthropic,
            // with guaranteed fallback to the prebuilt bundle if network fails.
            com.jarves.mh.model.AgentKind.CLAUDE_CODE -> {
                val latestOk = runCatching {
                    installClaudeLatest(runtime, onProgress)
                    true
                }.onFailure {
                    Log.w("RuntimeInstaller", "Claude latest live download failed, falling back to bundle", it)
                }.getOrDefault(false)

                if (!latestOk && !isAgentInstalled(com.jarves.mh.model.AgentKind.CLAUDE_CODE)) {
                    ensureClaudeInstalled(runtime.proot, 0.05f, onProgress)
                }
            }
            com.jarves.mh.model.AgentKind.ANTIGRAVITY -> ensureAgyInstalled(runtime.proot, 0.05f, onProgress)
            com.jarves.mh.model.AgentKind.HERMES -> ensureHermesInstalled(runtime.proot, 0.05f, onProgress)
            // The built-in agent ships inside the app — nothing to install.
            com.jarves.mh.model.AgentKind.NATIVE -> Unit
        }
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_ready, agent.localizedTitle(context)), 1f))
    }

    fun isAgentInstalled(agent: com.jarves.mh.model.AgentKind): Boolean {
        return when (agent) {
            com.jarves.mh.model.AgentKind.CLAUDE_CODE -> {
                // Termux: Claude lives at <prefix>/bin/claude (direct glibc loader wrapper).
                // Rootfs state from the PRoot era no longer counts.
                val termux = TermuxEnvironment(context)
                if (termux.isInstalled()) {
                    File(termux.prefix, "bin/claude").canExecute() &&
                        File(termux.prefix, "bin/claude-real").canExecute() &&
                        File(termux.prefix, "glibc/lib/ld-linux-aarch64.so.1").exists() &&
                        !termuxClaudeMarker(termux).readTextOrNull().isNullOrBlank()
                } else {
                    migrateLegacyClaudeMarker()
                    isInstalled() && File(rootfs, CLAUDE_GUEST_PATH.removePrefix("/")).canExecute() &&
                        !claudeMarker.readTextOrNull().isNullOrBlank()
                }
            }
            com.jarves.mh.model.AgentKind.ANTIGRAVITY -> isInstalled() &&
                File(rootfs, AGY_GUEST_PATH.removePrefix("/")).canExecute() &&
                !agyMarker.readTextOrNull().isNullOrBlank()
            com.jarves.mh.model.AgentKind.HERMES -> {
                // Termux: Hermes lives in the uv-sync venv of the repo cloned
                // at <prefix>/home/hermes (see installHermesOnTermux).
                val termux = TermuxEnvironment(context)
                if (termux.isInstalled()) {
                    val hasBin = termuxHermesBinary(termux).canExecute()
                    val hasAgent = File(termux.prefix, "home/hermes/Hermes-agent-").isDirectory
                    val hasMarker = !termuxHermesMarker(termux).readTextOrNull().isNullOrBlank()
                    hasBin && hasAgent && hasMarker
                } else {
                    isInstalled() &&
                        File(rootfs, HERMES_GUEST_PATH.removePrefix("/")).canExecute() &&
                        !hermesMarker.readTextOrNull().isNullOrBlank()
                }
            }
            // Built into the app: available whenever the core runtime is.
            com.jarves.mh.model.AgentKind.NATIVE -> isInstalled()
        }
    }

    val claudeVersion: String get() {
        migrateLegacyClaudeMarker()
        return claudeMarker.readTextOrNull().orEmpty()
    }

    val agyVersion: String get() = agyMarker.readTextOrNull().orEmpty()

    val hermesVersion: String get() = hermesMarker.readTextOrNull().orEmpty()

    val githubCliVersion: String get() = githubCliMarker.readTextOrNull().orEmpty()

    fun isGitHubCliInstalled(): Boolean = isInstalled() &&
        File(rootfs, GITHUB_CLI_GUEST_PATH.removePrefix("/")).canExecute() &&
        githubCliMarker.readTextOrNull() == GITHUB_CLI_VERSION

    /** Installs GitHub's official ARM64 CLI on demand; it is not bundled in the APK. */
    suspend fun ensureGitHubCliInstalled(onProgress: suspend (RuntimeInstallProgress) -> Unit) {
        if (isGitHubCliInstalled()) return
        check(!BuildConfig.OFFLINE_RUNTIME_BUNDLES) {
            str(R.string.rt_install_github_needs_online)
        }
        writeResolver()
        downloads.mkdirs()
        val downloaded = File(downloads, "gh-$GITHUB_CLI_VERSION-linux-arm64.tar.gz")
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_downloading_github_cli), 0.05f))
        val githubDownloadLabel = str(R.string.rt_install_downloading_version, "GitHub CLI", GITHUB_CLI_VERSION)
        downloadVerified(GITHUB_CLI_RELEASE_URL, downloaded, GITHUB_CLI_RELEASE_SHA256) { bytes, total ->
            val ratio = if (total > 0L) bytes.toFloat() / total else 0f
            onProgress(
                RuntimeInstallProgress(
                    message = githubDownloadLabel,
                    fraction = 0.05f + ratio * 0.75f,
                    downloadedBytes = bytes,
                    totalBytes = total.takeIf { it > 0L },
                    event = RuntimeInstallEvent.DOWNLOAD,
                ),
            )
        }
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_installing_version, "GitHub CLI", GITHUB_CLI_VERSION), 0.85f, indeterminate = true))
        val destination = File(rootfs, GITHUB_CLI_GUEST_PATH.removePrefix("/"))
        destination.parentFile?.mkdirs()
        var found = false
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(downloaded.inputStream()))).use { archive ->
            var entry = archive.nextEntry
            while (entry != null) {
                if (entry.isFile && entry.name.removePrefix("./").endsWith("/bin/gh")) {
                    val staged = File(destination.parentFile, ".gh-$GITHUB_CLI_VERSION.installing")
                    FileOutputStream(staged).use { archive.copyTo(it) }
                    Os.chmod(staged.absolutePath, 0b111101101)
                    Os.rename(staged.absolutePath, destination.absolutePath)
                    found = true
                    break
                }
                entry = archive.nextEntry
            }
        }
        check(found) { "Official GitHub CLI archive did not contain the expected binary" }
        downloaded.delete()
        verifyGuest(proot = installedRuntime().proot, command = "$GITHUB_CLI_GUEST_PATH --version", failureMessage = str(R.string.rt_install_verify_failed, "GitHub CLI"))
        githubCliMarker.writeText(GITHUB_CLI_VERSION)
        check(isGitHubCliInstalled()) { "GitHub CLI installation is incomplete" }
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_ready, "GitHub CLI"), 1f))
    }

    /**
     * Versions recorded after each real agent binary has been installed and verified.
     * This intentionally reports what is present in PRoot, even when a newer app build
     * would subsequently offer an agent update.
     */
    fun installedAgentVersions(): Map<com.jarves.mh.model.AgentKind, String> = buildMap {
        val termuxClaudeEnv = TermuxEnvironment(context)
        val claudeMarkerFile =
            if (termuxClaudeEnv.isInstalled()) termuxClaudeMarker(termuxClaudeEnv) else claudeMarker
        if (!termuxClaudeEnv.isInstalled()) migrateLegacyClaudeMarker()
        claudeMarkerFile.readTextOrNull()
            ?.trim()
            ?.takeIf { isAgentInstalled(com.jarves.mh.model.AgentKind.CLAUDE_CODE) && it.matches(CLAUDE_VERSION_PATTERN) }
            ?.let { put(com.jarves.mh.model.AgentKind.CLAUDE_CODE, it) }

        agyMarker.readTextOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() && File(rootfs, AGY_GUEST_PATH.removePrefix("/")).canExecute() }
            ?.let { put(com.jarves.mh.model.AgentKind.ANTIGRAVITY, it) }

        val termuxEnv = TermuxEnvironment(context)
        val hermesMarkerFile =
            if (termuxEnv.isInstalled()) termuxHermesMarker(termuxEnv) else hermesMarker
        hermesMarkerFile.readTextOrNull()
            ?.trim()
            ?.takeIf { isAgentInstalled(com.jarves.mh.model.AgentKind.HERMES) }
            ?.let { put(com.jarves.mh.model.AgentKind.HERMES, it) }
    }

    /** Checks each installed agent against its own authoritative release source. */
    suspend fun checkAgentUpdates(): Map<com.jarves.mh.model.AgentKind, AgentUpdateInfo> {
        val installed = installedAgentVersions()
        return buildMap {
            installed[com.jarves.mh.model.AgentKind.CLAUDE_CODE]?.let { current ->
                runCatching {
                    JSONObject(fetchText("https://registry.npmjs.org/@anthropic-ai/claude-code/latest")).getString("version")
                }.getOrNull()?.takeIf { isVersionNewer(it, current) }?.let { latest ->
                    put(com.jarves.mh.model.AgentKind.CLAUDE_CODE, AgentUpdateInfo(current, latest))
                }
            }
            installed[com.jarves.mh.model.AgentKind.ANTIGRAVITY]?.let { current ->
                runCatching { fetchAgyManifest().getString("version") }.getOrNull()
                    ?.takeIf { isVersionNewer(it, current) }?.let { latest ->
                        put(com.jarves.mh.model.AgentKind.ANTIGRAVITY, AgentUpdateInfo(current, latest))
                    }
            }
            installed[com.jarves.mh.model.AgentKind.HERMES]?.takeIf {
                // Termux Hermes updates come from its own repo (git pull +
                // uv sync on reinstall), never from bundle releases.
                !TermuxEnvironment(context).isInstalled()
            }?.let { current ->
                // Upgrades are unlocked (user decision, 2026-11-17): advertise
                // the newest stable release, not just the pinned first-install
                // version. Since v0.23.2 Hermes publishes on GitHub only —
                // PyPI is frozen at 0.19.0 — so the check reads the latest
                // GitHub release tag (canary tags carry a "+" suffix and are
                // never offered). A source-checkout install records
                // "<checkout version>+src" as its version; the numeric part
                // is the checkout's placeholder version, so a stable release
                // is normally offered, and accepting it replaces the checkout
                // install with the released package.
                runCatching {
                    JSONObject(fetchText("https://api.github.com/repos/NousResearch/hermes-agent/releases/latest"))
                        .getString("tag_name")
                        .removePrefix("v")
                }.getOrNull()?.takeIf { !it.contains("+") && isVersionNewer(it, current) }?.let { latest ->
                    put(com.jarves.mh.model.AgentKind.HERMES, AgentUpdateInfo(current, latest))
                }
            }
        }
    }

    suspend fun updateAgent(
        agent: com.jarves.mh.model.AgentKind,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val runtime = installedRuntime()
        when (agent) {
            com.jarves.mh.model.AgentKind.CLAUDE_CODE -> {
                val termux = TermuxEnvironment(context)
                if (termux.isInstalled()) installClaudeOnTermux(termux, onProgress)
                else updateClaude(runtime, expectedVersion, onProgress)
            }
            com.jarves.mh.model.AgentKind.ANTIGRAVITY -> updateAgy(runtime, expectedVersion, onProgress)
            com.jarves.mh.model.AgentKind.HERMES -> updateHermes(runtime, expectedVersion, onProgress)
            com.jarves.mh.model.AgentKind.NATIVE -> Unit
        }
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_version_ready, agent.localizedTitle(context), expectedVersion), 1f, event = RuntimeInstallEvent.COMPLETED))
    }

    /** First install of Claude Code at the newest released version (v54). */
    private suspend fun installClaudeLatest(
        runtime: InstalledRuntime,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (isAgentInstalled(com.jarves.mh.model.AgentKind.CLAUDE_CODE)) return
        val latest = JSONObject(
            fetchText("https://registry.npmjs.org/@anthropic-ai/claude-code/latest"),
        ).getString("version")
        updateClaude(runtime, latest, onProgress)
    }

    private suspend fun updateClaude(
        runtime: InstalledRuntime,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val latest = JSONObject(fetchText("https://registry.npmjs.org/@anthropic-ai/claude-code/latest")).getString("version")
        check(latest == expectedVersion) { str(R.string.rt_install_newer_release, "Claude Code") }
        val base = "https://downloads.claude.ai/claude-code-releases/$latest"
        val manifest = JSONObject(fetchText("$base/manifest.json"))
        val checksum = manifest.getJSONObject("platforms").getJSONObject("linux-arm64").getString("checksum")
        val downloaded = File(downloads, "claude-$latest")
        val claudeDownloadLabel = str(R.string.rt_install_downloading_version, "Claude Code", latest)
        downloadVerified("$base/linux-arm64/claude", downloaded, checksum) { bytes, total ->
            val ratio = if (total > 0L) bytes.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress(claudeDownloadLabel, ratio * 0.9f, bytes, total.takeIf { it > 0L }, event = RuntimeInstallEvent.DOWNLOAD))
        }
        val claude = File(rootfs, CLAUDE_GUEST_PATH.removePrefix("/"))
        claude.parentFile?.mkdirs()
        val staged = File(claude.parentFile, ".claude-$latest.installing")
        downloaded.copyTo(staged, overwrite = true)
        Os.chmod(staged.absolutePath, 0b111101101)
        Os.rename(staged.absolutePath, claude.absolutePath)
        downloaded.delete()
        verifyGuest(runtime.proot, "$CLAUDE_GUEST_PATH --version", str(R.string.rt_install_update_verify_failed, "Claude Code"))
        claudeMarker.writeText(latest)
    }

    private suspend fun updateAgy(
        runtime: InstalledRuntime,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val manifest = fetchAgyManifest()
        val latest = manifest.getString("version")
        check(latest == expectedVersion) { str(R.string.rt_install_newer_release, "Antigravity") }
        val downloaded = File(downloads, "antigravity-$latest-linux-arm64.tar.gz")
        val agyDownloadLabel = str(R.string.rt_install_downloading_version, "Antigravity CLI", latest)
        downloadVerified(manifest.getString("url"), downloaded, manifest.getString("sha512"), algorithm = "SHA-512") { bytes, total ->
            val ratio = if (total > 0L) bytes.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress(agyDownloadLabel, ratio * 0.9f, bytes, total.takeIf { it > 0L }, event = RuntimeInstallEvent.DOWNLOAD))
        }
        val destination = File(rootfs, AGY_GUEST_PATH.removePrefix("/"))
        var found = false
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(downloaded.inputStream()))).use { archive ->
            var entry = archive.nextEntry
            while (entry != null) {
                if (entry.isFile && entry.name.removePrefix("./") == "antigravity") {
                    val staged = File(destination.parentFile, ".agy-$latest.installing")
                    FileOutputStream(staged).use { archive.copyTo(it) }
                    Os.chmod(staged.absolutePath, 0b111101101)
                    Os.rename(staged.absolutePath, destination.absolutePath)
                    found = true
                    break
                }
                entry = archive.nextEntry
            }
        }
        downloaded.delete()
        check(found) { "Antigravity update archive is incomplete" }
        verifyGuest(runtime.proot, "$AGY_GUEST_PATH --version", str(R.string.rt_install_update_verify_failed, "Antigravity CLI"))
        agyMarker.writeText(latest)
    }

    /**
     * Hermes updates install the exact GitHub release the user approved in
     * the update prompt (upgrades unlocked 2026-11-17; only the first
     * install is pinned). The reinstall rebuilds the venv from scratch; the
     * uv-managed CPython and the agent's HERMES_HOME data (memory, skills,
     * sessions) stay. The version marker is rewritten only after the new
     * install verifies, so a failed update never claims a version that is
     * not there.
     */
    private suspend fun updateHermes(
        runtime: InstalledRuntime,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        require(isHermesReleaseVersion(expectedVersion)) {
            "Refusing to install an unrecognised Hermes release: $expectedVersion"
        }
        installHermesFromGitHubRelease(runtime.proot, expectedVersion, 0.05f, onProgress)
    }

    private fun fetchAgyManifest(): JSONObject = JSONObject(
        fetchText("https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/linux_arm64.json"),
    )

    private fun isVersionNewer(candidate: String, current: String): Boolean {
        fun parts(value: String) = Regex("\\d+").findAll(value).map { it.value.toIntOrNull() ?: 0 }.toList()
        val left = parts(candidate)
        val right = parts(current)
        repeat(maxOf(left.size, right.size)) { index ->
            val comparison = (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 })
            if (comparison != 0) return comparison > 0
        }
        return candidate != current && !candidate.contains("alpha", true) && !candidate.contains("rc", true)
    }

    /**
     * Older Core bundles stored Claude Code and its version in Core-owned markers.
     * Preserve that verified installation when upgrading the app, while all fresh
     * installs use the independent Claude overlay and marker.
     */
    private fun migrateLegacyClaudeMarker() {
        if (claudeMarker.isFile) return
        val claude = File(rootfs, CLAUDE_GUEST_PATH.removePrefix("/"))
        if (!claude.isFile) return
        val legacyVersion = sequenceOf(
            coreReadyMarker.readTextOrNull(),
            bundledClaudeMarker.readTextOrNull(),
        ).mapNotNull { it?.trim() }.firstOrNull { it.matches(CLAUDE_VERSION_PATTERN) } ?: return
        claudeMarker.writeText(legacyVersion)
    }

    private fun isSupportedCoreToolsVersion(): Boolean = coreToolsMarker.readTextOrNull() in setOf(
        CORE_TOOLS_VERSION,
        LEGACY_CORE_TOOLS_VERSION,
    )

    private suspend fun ensureClaudeInstalled(
        proot: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        migrateLegacyClaudeMarker()
        if (isAgentInstalled(com.jarves.mh.model.AgentKind.CLAUDE_CODE)) return
        installRuntimeOverlay(
            bundle = CLAUDE_BUNDLE,
            message = str(R.string.rt_install_installing_version, "Claude Code", CLAUDE_BUNDLED_VERSION),
            from = fraction,
            to = 0.995f,
            onProgress = onProgress,
        )
        verifyGuest(proot, "$CLAUDE_GUEST_PATH --version", str(R.string.rt_install_verify_failed, "Claude Code"))
        require(claudeMarker.readTextOrNull() == CLAUDE_BUNDLED_VERSION) {
            "The Claude Code runtime bundle is incomplete"
        }
    }

    private suspend fun ensureAgyInstalled(
        proot: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (isAgentInstalled(com.jarves.mh.model.AgentKind.ANTIGRAVITY)) return
        installRuntimeOverlay(
            bundle = AGY_BUNDLE,
            message = str(R.string.rt_install_installing_version, "Antigravity CLI", AGY_VERSION),
            from = fraction,
            to = 0.995f,
            onProgress = onProgress,
            forceEmbedded = true,
        )
        verifyGuest(proot, "$AGY_GUEST_PATH --version", str(R.string.rt_install_verify_failed, "Antigravity CLI"))
        agyMarker.writeText(AGY_VERSION)
        require(isAgentInstalled(com.jarves.mh.model.AgentKind.ANTIGRAVITY)) {
            "Antigravity CLI installation is incomplete"
        }
    }

    private fun isUvInstalled(): Boolean = isInstalled() &&
        File(rootfs, UV_GUEST_PATH.removePrefix("/")).canExecute() &&
        uvMarker.readTextOrNull() == UV_VERSION

    /**
     * Stages the pinned uv binary the same way the GitHub CLI is staged: a
     * checksum-verified host-side download, one binary extracted from the
     * release tarball, then an in-guest `--version` check. The pin (version,
     * URL and SHA-256) is the one Hermes's own installer locks in `pm/lock.json`.
     */
    private suspend fun ensureUvInstalled(
        proot: File,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (isUvInstalled()) return
        writeResolver()
        downloads.mkdirs()
        val downloaded = File(downloads, "uv-$UV_VERSION-linux-arm64.tar.gz")
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_downloading_version, "uv", UV_VERSION), from))
        val uvDownloadLabel = str(R.string.rt_install_downloading_version, "uv", UV_VERSION)
        downloadVerified(UV_RELEASE_URL, downloaded, UV_RELEASE_SHA256) { bytes, total ->
            val ratio = if (total > 0L) bytes.toFloat() / total else 0f
            onProgress(
                RuntimeInstallProgress(
                    message = uvDownloadLabel,
                    fraction = from + ratio * (to - from),
                    downloadedBytes = bytes,
                    totalBytes = total.takeIf { it > 0L },
                    event = RuntimeInstallEvent.DOWNLOAD,
                ),
            )
        }
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_installing_version, "uv", UV_VERSION), to, indeterminate = true))
        val destination = File(rootfs, UV_GUEST_PATH.removePrefix("/"))
        destination.parentFile?.mkdirs()
        var found = false
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(downloaded.inputStream()))).use { archive ->
            var entry = archive.nextEntry
            while (entry != null) {
                if (entry.isFile && entry.name.removePrefix("./").endsWith("/uv")) {
                    val staged = File(destination.parentFile, ".uv-$UV_VERSION.installing")
                    FileOutputStream(staged).use { archive.copyTo(it) }
                    Os.chmod(staged.absolutePath, 0b111101101)
                    Os.rename(staged.absolutePath, destination.absolutePath)
                    found = true
                    break
                }
                entry = archive.nextEntry
            }
        }
        check(found) { "Official uv archive did not contain the expected binary" }
        downloaded.delete()
        verifyGuest(proot = proot, command = "$UV_GUEST_PATH --version", failureMessage = str(R.string.rt_install_verify_failed, "uv"))
        uvMarker.writeText(UV_VERSION)
        check(isUvInstalled()) { "uv installation is incomplete" }
    }

    /**
     * Hermes install. All routes end in the same venv under
     * /root/.pocket-hermes (HERMES_GUEST_PATH):
     *
     * - First, the prebuilt bundle — see [installHermesFromBundle]. In the
     *   offline flavor it ships inside the APK and is extracted locally; in
     *   the online flavor the same archive is downloaded once from the
     *   Hermes release URL (SHA-256 verified) during the regular setup
     *   sequence. Either way there is no separate install step for the user.
     * - On any bundle failure, the Phase 1 network routes run unchanged:
     *   if a Hermes source checkout exists in the guest at
     *   [HERMES_SOURCE_GUEST_PATH] (recognised by its `pyproject.toml` and
     *   `hermes_cli` package), it is installed from source — see
     *   [installHermesFromSource]. This is the modification-unlocked route:
     *   the venv resolves its packages from the checkout itself, so edits
     *   there take effect without a reinstall. Otherwise the pinned GitHub
     *   release is installed — see [installHermesFromGitHubRelease].
     *
     * An already-installed Hermes is left untouched whatever version its
     * marker records — in particular an install that was upgraded past the
     * pin is never "repaired" back down to it.
     */
    private suspend fun ensureHermesInstalled(
        proot: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (isAgentInstalled(com.jarves.mh.model.AgentKind.HERMES)) return
        // The version marker is the source of truth, and it lives outside
        // /root — so after a core-rootfs swap (which preserves /root) a
        // previous Hermes venv can outlive its marker. Every install route
        // extracts or syncs OVER an existing tree, and a merged venv (two
        // dist-infos, stale modules) is worse than a clean one: wipe any
        // marker-less leftover first. Hermes's own data lives in
        // /root/.hermes, which this never touches.
        File(rootfs, HERMES_PREFIX_GUEST_PATH.removePrefix("/")).deleteRecursively()
        val bundled = try {
            installHermesFromBundle(proot, fraction, onProgress)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e("RuntimeInstaller", "Hermes bundle installation failed, falling back to network routes", error)
            false
        }
        if (bundled) return
        val sourceCheckout = hermesSourceCheckout()
        if (sourceCheckout != null) {
            installHermesFromSource(proot, sourceCheckout, fraction, onProgress)
        } else {
            installHermesFromGitHubRelease(proot, HERMES_VERSION, fraction, onProgress)
        }
    }

    /**
     * Bundle route (Phase 2): the prebuilt Hermes overlay — the standalone
     * CPython 3.14 tree and the pinned release's site-packages — is extracted
     * into /root/.pocket-hermes, followed by the same in-guest verification
     * and marker discipline as the PyPI route (the marker records the pinned
     * version only after the installed binary verifies). The archive comes
     * from the APK's assets in the offline flavor; the online flavor does
     * NOT embed it (it would add ~52 MB to every app update), so
     * [obtainRuntimeBundle] downloads it from [HERMES_BUNDLE]'s release URL
     * instead — the flavor decides via [installRuntimeOverlay], exactly like
     * the other stack bundles. Throws on any problem — a missing embedded
     * asset (CI builds made without the bundle file), a failed or
     * checksum-mismatching download, or a binary that does not run — and the
     * caller falls back to the network routes.
     */
    /**
     * Termux Hermes install: clone the user's Hermes repo into the Termux
     * home and `uv sync` it — the route proven on Muse's VM (2026-11-21).
     * The bridge's guest path /root/.pocket-hermes/venv translates to
     * <home>/hermes/.venv (see termuxProcess), so no bridge change is
     * needed; Hermes's own data lands in <home>/.hermes via HOME.
     */
    /** glibc-runner (grun) must exist before any glibc binary can run. */
    private suspend fun ensureGlibcRunner(
        termux: TermuxEnvironment,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (File(termux.prefix, "bin/grun").exists() || File(termux.prefix, "glibc/bin/grun").exists()) return
        onProgress(RuntimeInstallProgress("glibc runtime install ho raha hai…", 0.06f))
        termux.install(listOf("glibc-runner")) { line ->
            onProgress(RuntimeInstallProgress(line, 0.08f))
        }.exceptionOrNull()?.let { throw it }
    }

    private fun grunPath(termux: TermuxEnvironment): String =
        if (File(termux.prefix, "bin/grun").exists()) {
            File(termux.prefix, "bin/grun").absolutePath
        } else {
            File(termux.prefix, "glibc/bin/grun").absolutePath
        }

    // ------------------------------------------------------------------
    // Bundled agents (v77) — the user's standing order: Hermes + Claude
    // Code PRE-INSTALLED inside the app, the way Termux itself is. The
    // APK carries complete prebuilt environments as assets/agents/*.tar.gz
    // (fetched at build time from the agents-bundle-v1 release; assembled
    // from the official Claude binary, the termux-glibc repo, a
    // python-build-standalone CPython 3.14 and his Hermes source + locked
    // wheels, then run-verified under QEMU on the exact device paths:
    // `claude --version` 2.1.330 OK, `hermes --version` OK, every core
    // dependency imports under the bundled glibc). Installing an agent
    // is then pure local extraction — no network, no on-device package
    // downloads, no source builds. The network routes below stay as
    // fallback when the bundle is absent or its verify fails.
    // ------------------------------------------------------------------

    private fun bundledAssetPresent(name: String): Boolean =
        runCatching { context.assets.open("agents/$name").close() }.isSuccess

    private fun extractBundledAsset(name: String, destDir: File) {
        context.assets.open("agents/$name").use { input ->
            extractTarGzStream(BufferedInputStream(input), destDir)
        }
    }

    /** Extracts the bundled glibc runtime into the prefix, once. */
    private fun ensureBundledGlibc(termux: TermuxEnvironment) {
        val flag = File(termux.prefix, ".bundled-glibc-v1")
        if (flag.exists() && File(termux.prefix, "bin/grun").exists()) return
        extractBundledAsset("glibc.bin", termux.prefix)
        flag.writeText("agents-bundle-v1")
    }

    /** Extracts the bundled glibc CPython 3.14 into the prefix, once. */
    private fun ensureBundledPython(termux: TermuxEnvironment) {
        val flag = File(termux.prefix, ".bundled-python-v1")
        if (flag.exists() && File(termux.prefix, "opt/python-glibc/bin/python3.14").exists()) return
        extractBundledAsset("python-glibc.bin", termux.prefix)
        flag.writeText("agents-bundle-v1")
    }

    /** Why the bundled Hermes route last failed (shown if the fallback also fails). */
    private var lastHermesBundleFailure: String? = null

    /**
     * Bundled Hermes install: extract the prebuilt environment, then hold
     * it to the same bar as every route — the marker is written only
     * after `hermes --version` really runs on the device.
     */
    private suspend fun installHermesFromApkBundle(
        termux: TermuxEnvironment,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ): Boolean {
        val assetName = when {
            bundledAssetPresent("hermes-bionic.bin") -> "hermes-bionic.bin"
            bundledAssetPresent("hermes.bin") -> "hermes.bin"
            else -> return false
        }
        val isBionic = assetName == "hermes-bionic.bin"
        return runCatching {
            onProgress(RuntimeInstallProgress("Termux Bionic + Hermes unpack ho raha hai…", 0.15f))
            if (!isBionic) {
                ensureBundledGlibc(termux)
                ensureBundledPython(termux)
            }
            extractBundledAsset(assetName, termux.prefix)
            val binDir = File(termux.prefix, "bin").apply { mkdirs() }
            val py311 = File(binDir, "python3.11")
            val pyTarget = if (py311.exists()) "python3.11" else "python3.14"
            runCatching {
                val pLink = File(binDir, "python")
                pLink.delete()
                Os.symlink(pyTarget, pLink.absolutePath)
            }
            runCatching {
                val p3Link = File(binDir, "python3")
                p3Link.delete()
                Os.symlink(pyTarget, p3Link.absolutePath)
            }
            val pyBin = File(binDir, pyTarget)
            val launcher = termuxHermesBinary(termux)
            runCatching { Os.chmod(launcher.absolutePath, 0b111101101) }
            runCatching { Os.chmod(File(binDir, "bash").absolutePath, 0b111101101) }
            runCatching { Os.chmod(pyBin.absolutePath, 0b111101101) }
            runCatching { Os.chmod(File(binDir, "git").absolutePath, 0b111101101) }
            val venvBin = File(termux.prefix, "home/hermes/.venv/bin").apply { mkdirs() }
            runCatching {
                val pLink = File(venvBin, "python")
                pLink.delete()
                Os.symlink("../../../../bin/$pyTarget", pLink.absolutePath)
            }
            runCatching {
                val p3Link = File(venvBin, "python3")
                p3Link.delete()
                Os.symlink("../../../../bin/$pyTarget", p3Link.absolutePath)
            }
            cleanBrokenHermesTools(File(termux.prefix, "home/hermes"))
            cleanBrokenHermesTools(File(termux.prefix, "home/.hermes"))
            termux.ensureShims(force = true)
            if (isBionic) {
                File(termux.prefix, ".harness-installed").writeText(
                    "bash\nbusybox\nca-certificates\ntermux-exec\ngit\ngh\npython\npython-pip\nnodejs\nnpm\nuv\n",
                )
                val hermesHome = File(termux.prefix, "home/hermes").apply { mkdirs() }
                val hermesCfg = File(hermesHome, "config.yaml")
                if (!hermesCfg.exists()) {
                    val defaultRoute = HermesRoute(
                        provider = HermesProviderCatalog.CUSTOM_ID,
                        keyEnv = HermesProviderCatalog.CUSTOM_KEY_ENV,
                        baseUrl = HermesProviderCatalog.CUSTOM_DEF.baseUrl,
                        custom = true,
                    )
                    val defaultCfg = HermesConfigFiles.freshConfig(defaultRoute, "")
                    runCatching { hermesCfg.writeText(defaultCfg) }
                    runCatching {
                        val dotHome = File(termux.prefix, "home/.hermes").apply { mkdirs() }
                        File(dotHome, "config.yaml").writeText(defaultCfg)
                    }
                }
                val hermesEnv = File(hermesHome, ".env")
                if (!hermesEnv.exists()) {
                    val defaultEnv = "CUSTOM_PROVIDER_API_KEY=no-key-required\n"
                    runCatching { hermesEnv.writeText(defaultEnv) }
                    runCatching {
                        val dotHome = File(termux.prefix, "home/.hermes").apply { mkdirs() }
                        File(dotHome, ".env").writeText(defaultEnv)
                    }
                }
            }
            onProgress(RuntimeInstallProgress("Hermes (Termux): verify ho raha hai…", 0.9f))
            val verifyCmd = if (isBionic) {
                val hermesPyPath = "${File(termux.prefix, "home/hermes/Hermes-agent-").absolutePath}:${File(termux.prefix, "home/hermes/.venv/lib/python3.11/site-packages").absolutePath}"
                "PYTHONPATH=\"$hermesPyPath\" HERMES_HOME=\"${File(termux.prefix, "home/hermes").absolutePath}\" \"${pyBin.absolutePath}\" -m hermes_cli.main --version 2>&1"
            } else {
                val cleanEnv = "env -u LD_PRELOAD " +
                    "LD_LIBRARY_PATH=\"${termux.prefix.absolutePath}/glibc/lib\""
                "$cleanEnv \"${launcher.absolutePath}\" --version 2>&1"
            }
            val out = runTermuxCapture(termux, verifyCmd, 300)
            check(out.contains("Python:")) { "Hermes bundled verify fail: ${out.take(300)}" }
            if (isBionic) {
                // v85 merge (from Muse's v82): `--version` alone is too
                // weak a gate — on v80/v81 it passed while the bundle was
                // missing the real library files behind its lib/ symlinks
                // (libz, libsqlite3, libreadline, liblzma, libbz2…), and
                // chat then died with "dlopen failed: library libz.so.1
                // not found". Import the stdlib modules that pull those
                // libraries so a broken bundle fails HERE, loudly.
                val py = File(termux.prefix, "bin/python3.11").absolutePath
                val pyPath = File(termux.prefix, "home/hermes/Hermes-agent-").absolutePath +
                    ":" + File(termux.prefix, "home/hermes/.venv/lib/python3.11/site-packages").absolutePath
                val libCmd = "PYTHONPATH=\"$pyPath\" " +
                    "LD_LIBRARY_PATH=\"${termux.prefix.absolutePath}/lib\" " +
                    "\"$py\" -c \"import binascii, zlib, sqlite3, lzma, bz2, readline, " +
                    "ssl, crypt, dbm.gnu, xml.parsers.expat, _multiprocessing; print('libs-ok')\" 2>&1"
                val libOut = runTermuxCapture(termux, libCmd, 300)
                check(libOut.contains("libs-ok")) { "Hermes bundled library check fail: ${libOut.take(300)}" }
            }
            termuxHermesMarker(termux).writeText(HERMES_VERSION)
            onProgress(RuntimeInstallProgress("Hermes (Termux) ready hai", 1f))
            true
        }.getOrElse {
            Log.w("RuntimeInstaller", "Bundled Hermes install failed, network route lagega", it)
            lastHermesBundleFailure = it.message?.take(240) ?: it.javaClass.simpleName
            false
        }
    }

    /** Bundled Claude Code install: extract, wrap, verify, mark — same bar as the network route. */
    private suspend fun installClaudeFromApkBundle(
        termux: TermuxEnvironment,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ): Boolean {
        if (!bundledAssetPresent("claude.bin")) return false
        return runCatching {
            onProgress(RuntimeInstallProgress("Claude Code app ke andar se install ho raha hai…", 0.15f))
            ensureBundledGlibc(termux)
            extractBundledAsset("claude.bin", termux.prefix)
            val real = File(termux.prefix, "bin/claude-real")
            check(real.isFile) { "claude-real bundle me nahi mila" }
            runCatching { Os.chmod(real.absolutePath, 0b111101101) }
            finishClaudeTermuxInstall(termux, BUNDLED_CLAUDE_VERSION, onProgress)
            true
        }.getOrElse {
            Log.w("RuntimeInstaller", "Bundled Claude install failed, network route lagega", it)
            false
        }
    }

    /**
     * Writes the grun wrapper for bin/claude-real, verifies that
     * `claude --version` really runs, and only then records the marker.
     * Shared by the bundled (v77) and network Claude routes.
     */
    private suspend fun finishClaudeTermuxInstall(
        termux: TermuxEnvironment,
        version: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val binDir = File(termux.prefix, "bin").apply { mkdirs() }
        val real = File(binDir, "claude-real")
        val wrapper = File(binDir, "claude")
        val loader = File(termux.prefix, "glibc/lib/ld-linux-aarch64.so.1").absolutePath
        val glibcLib = File(termux.prefix, "glibc/lib").absolutePath
        // Direct glibc dynamic loader execution with isolated environment:
        // No dependency on broken grun scripts or hardcoded com.termux paths.
        wrapper.writeText(
            "#!${termux.prefix.absolutePath}/bin/bash\n" +
                "unset LD_PRELOAD\n" +
                "unset LD_LIBRARY_PATH\n" +
                "export GLIBC_TUNABLES=\"glibc.pthread.rseq=0\"\n" +
                "export PATH=\"${termux.prefix.absolutePath}/glibc/bin:${termux.prefix.absolutePath}/bin:\$PATH\"\n" +
                "if [ -f \"$loader\" ]; then\n" +
                "    exec \"$loader\" --library-path \"$glibcLib\" \"${real.absolutePath}\" \"\$@\"\n" +
                "else\n" +
                "    exec \"${real.absolutePath}\" \"\$@\"\n" +
                "fi\n",
        )
        Os.chmod(wrapper.absolutePath, 0b111101101)
        onProgress(RuntimeInstallProgress("Claude Code (Termux): verify ho raha hai…", 0.9f))
        // First run of the 380 MB binary through the glibc loader can be
        // slow on a cold page cache — give it real time, and retry once
        // (the second run is warm) before declaring failure.
        // Retry on exception (timeout, etc.) as well as on non-matching output.
        var out: String? = null
        for (attempt in 1..2) {
            val result = runCatching { runTermuxCapture(termux, "\"${wrapper.absolutePath}\" --version 2>&1", 300) }.getOrNull()
            if (result != null) out = result
            if (out != null && Regex("[0-9]+\\.[0-9]+\\.[0-9]+").containsMatchIn(out!!)) break
        }
        check(out != null && Regex("[0-9]+\\.[0-9]+\\.[0-9]+").containsMatchIn(out!!)) {
            "Claude Code (Termux) verify fail: ${out?.take(300) ?: "timeout/error"}"
        }
        termuxClaudeMarker(termux).writeText(version)
        onProgress(RuntimeInstallProgress("Claude Code (Termux) ready hai", 1f))
    }

    /** Extracts a .tar.gz with zip-slip protection (used for the glibc CPython). */
    private fun extractTarGz(archive: File, destDir: File) {
        BufferedInputStream(archive.inputStream()).use { input -> extractTarGzStream(input, destDir) }
    }

    /** Stream form of [extractTarGz] — the v77 agents bundle extracts straight from APK assets. */
    private fun extractTarGzStream(input: java.io.InputStream, destDir: File) {
        destDir.mkdirs()
        val destCanonical = destDir.canonicalFile
        GzipCompressorInputStream(input).use { gz ->
            TarArchiveInputStream(gz).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    val out = File(destDir, entry.name)
                    check(out.canonicalFile.path.startsWith(destCanonical.path)) {
                        "Unsafe tar entry: ${entry.name}"
                    }
                    when {
                        entry.isDirectory -> out.mkdirs()
                        entry.isSymbolicLink -> {
                            out.parentFile?.mkdirs()
                            // Delete first so a re-extract replaces a stale link
                            // instead of silently keeping it (Os.symlink fails
                            // with EEXIST and the runCatching would hide it).
                            runCatching { out.delete() }
                            runCatching { Os.symlink(entry.linkName, out.absolutePath) }
                        }
                        entry.isLink -> {
                            out.parentFile?.mkdirs()
                            val target = File(destDir, entry.linkName)
                            if (target.isFile) target.copyTo(out, overwrite = true)
                        }
                        else -> {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { o -> tar.copyTo(o) }
                            runCatching { Os.chmod(out.absolutePath, entry.mode) }
                        }
                    }
                    entry = tar.nextEntry
                }
            }
        }
    }

    /**
     * Termux Hermes install — glibc route (v75). Why: Hermes's locked deps
     * (pydantic-core, httptools, uvloop, cryptography, numpy…) publish NO
     * Android wheels, so `uv sync` against Termux's bionic Python tries to
     * compile everything from source and dies (seen on device, v74:
     * 'aarch64-linux-android-clang' Permission denied). Instead the venv is
     * built on a glibc CPython (python-build-standalone) whose interpreter
     * is patchelf'd onto the Termux glibc loader: uv then resolves ordinary
     * manylinux wheels for every dep — zero source builds. The bridge's
     * guest path /root/.pocket-hermes/venv still translates to this venv.
     */
    private suspend fun installHermesOnTermux(
        termux: TermuxEnvironment,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        // v77: the prebuilt bundle inside the APK wins; network is fallback.
        if (installHermesFromApkBundle(termux, onProgress)) return
        val home = File(termux.prefix, "home").apply { mkdirs() }
        // uv MUST come from Termux's own repo (native bionic build): pip
        // only finds uv's sdist on this platform and tries to compile it
        // with Rust, which fails (seen on device, v71).
        if (!File(termux.prefix, "bin/uv").exists()) {
            onProgress(RuntimeInstallProgress("Hermes (Termux): uv package install ho raha hai…", 0.05f))
            termux.install(listOf("uv")) { line ->
                onProgress(RuntimeInstallProgress(line, 0.08f))
            }.exceptionOrNull()?.let { throw it }
        }
        ensureGlibcRunner(termux, onProgress)
        // glibc CPython (pinned python-build-standalone), extracted once.
        val optDir = File(termux.prefix, "opt").apply { mkdirs() }
        val glibcPythonHome = File(optDir, "python-glibc")
        val glibcPython = File(glibcPythonHome, "bin/python3.14")
        if (!glibcPython.exists()) {
            onProgress(RuntimeInstallProgress("Hermes (Termux): glibc Python download ho raha hai…", 0.12f))
            val tarball = File(downloads, "cpython-glibc-3.14.tar.gz")
            downloadVerified(GLIBC_PYTHON_URL, tarball, GLIBC_PYTHON_SHA256) { bytes, total ->
                val ratio = if (total > 0L) bytes.toFloat() / total else 0f
                onProgress(
                    RuntimeInstallProgress(
                        "glibc Python download ho raha hai…",
                        0.12f + ratio * 0.2f,
                        bytes,
                        total.takeIf { it > 0L },
                        event = RuntimeInstallEvent.DOWNLOAD,
                    ),
                )
            }
            val staging = File(optDir, "python-extract").apply { deleteRecursively(); mkdirs() }
            extractTarGz(tarball, staging)
            tarball.delete()
            File(staging, "python").renameTo(glibcPythonHome)
            staging.deleteRecursively()
            check(glibcPython.exists()) { "glibc Python extract fail" }
        }
        onProgress(RuntimeInstallProgress("Hermes (Termux): repo clone + wheels install ho rahe hain…", 0.35f))
        val script = """
            set -e
            exec 2>&1
            export UV_PYTHON_DOWNLOADS=never
            cd __HOME__
            if [ -d hermes/.git ]; then git -C hermes pull --ff-only || true; else git clone --depth 1 https://github.com/radhesolanki319-pixel/Hermes-agent- hermes; fi
            cd hermes
            "${'$'}PREFIX/glibc/lib/ld-linux-aarch64.so.1" "${'$'}PREFIX/glibc/bin/patchelf" --set-interpreter "${'$'}PREFIX/glibc/lib/ld-linux-aarch64.so.1" "${'$'}PREFIX/opt/python-glibc/bin/python3.14"
            export LD_LIBRARY_PATH="${'$'}PREFIX/glibc/lib"
            unset LD_PRELOAD
            rm -rf .venv
            uv sync --no-dev --python "${'$'}PREFIX/opt/python-glibc/bin/python3.14"
            .venv/bin/hermes --version
        """.trimIndent()
            .replace("__HOME__", home.absolutePath)
            .replace("__GRUN__", grunPath(termux))
        val process = termux.startShell(script, home)
        val tail = java.util.Collections.synchronizedList(mutableListOf<String>())
        val reader = kotlin.concurrent.thread(name = "hermes-termux-install", isDaemon = true) {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(tail) { tail += line; if (tail.size > 40) tail.removeAt(0) }
                }
            }
        }
        var fraction = 0.4f
        while (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            fraction = (fraction + 0.03f).coerceAtMost(0.92f)
            val last = synchronized(tail) { tail.lastOrNull() } ?: "Hermes install chal raha hai…"
            onProgress(RuntimeInstallProgress(last.take(120), fraction))
        }
        runCatching { reader.join(2_000) }
        val exit = process.exitValue()
        check(exit == 0 && termuxHermesBinary(termux).canExecute()) {
            "Hermes (Termux) install fail (exit $exit): " +
                synchronized(tail) { tail.takeLast(6).joinToString(" · ") } +
                (lastHermesBundleFailure?.let { " · bundle fail: $it" } ?: "")
        }
        termuxHermesMarker(termux).writeText(HERMES_VERSION)
        onProgress(RuntimeInstallProgress("Hermes (Termux) ready hai", 1f))
    }

    /** Runs a Termux shell command and returns its combined output. */
    private fun runTermuxCapture(
        termux: TermuxEnvironment,
        command: String,
        timeoutSeconds: Long = 90,
    ): String {
        val process = termux.startShell(command, File(termux.prefix, "home"))
        val text = StringBuilder()
        val reader = kotlin.concurrent.thread(isDaemon = true) {
            runCatching {
                process.inputStream.bufferedReader().forEachLine {
                    synchronized(text) { text.append(it).append('\n') }
                }
            }
        }
        if (!process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
            runCatching { process.destroy() }
            throw IllegalStateException("Termux command timeout: $command")
        }
        runCatching { reader.join(1_000) }
        return synchronized(text) { text.toString() }
    }

    /**
     * Termux Claude Code install: the official linux-arm64 binary is a glibc
     * executable, so it cannot run on Android's bionic libc directly. The
     * community-proven route is Termux's own glibc repo: glibc-runner (grun)
     * patchelf-loads glibc binaries under the Termux glibc. Layout:
     * <prefix>/bin/claude-real is the untouched official binary and
     * <prefix>/bin/claude is a tiny wrapper that execs it through grun — the
     * bridge's guest path /usr/local/bin/claude translates to the wrapper,
     * so no bridge change is needed. The install only records its marker
     * after `claude --version` really runs on the device.
     */
    private suspend fun installClaudeOnTermux(
        termux: TermuxEnvironment,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        // v79 master gate: Claude disabled (user order) — every install
        // path funnels through here, so no route can start a download.
        if (CLAUDE_DISABLED) {
            onProgress(RuntimeInstallProgress("Claude Code abhi band hai — pehle Hermes pakka ho raha hai", 1f))
            return
        }
        // First wipe any old/broken Claude installation files completely
        purgeClaudeFiles(termux)

        if (installClaudeFromApkBundle(termux, onProgress)) return

        // Ensure glibc dynamic loader and libraries exist
        val glibcLoader = File(termux.prefix, "glibc/lib/ld-linux-aarch64.so.1")
        if (!glibcLoader.exists()) {
            onProgress(RuntimeInstallProgress("Claude Code: glibc runtime download ho raha hai…", 0.05f))
            val glibcDest = File(downloads, "glibc-runtime.bin")
            val glibcUrl = "https://github.com/radhesolanki319-pixel/Hermes-Agent-Mobile-App/releases/download/agents-bundle-v1/glibc.bin"
            val glibcSha256 = "1d90dfe24abd8136b394b7d1c039b9e6381bfa4f4b73189f6d9341b1322b8783"
            downloadVerified(glibcUrl, glibcDest, glibcSha256) { bytes, total ->
                val ratio = if (total > 0L) bytes.toFloat() / total else 0f
                onProgress(
                    RuntimeInstallProgress(
                        "glibc runtime download ho raha hai…",
                        0.05f + ratio * 0.3f,
                        bytes,
                        total.takeIf { it > 0L },
                        event = RuntimeInstallEvent.DOWNLOAD,
                    ),
                )
            }
            onProgress(RuntimeInstallProgress("glibc runtime extract ho raha hai…", 0.38f))
            extractTarGz(glibcDest, termux.prefix)
            glibcDest.delete()
            ensureCoreToolchain(termux)
        }

        val binDir = File(termux.prefix, "bin").apply { mkdirs() }
        val real = File(binDir, "claude-real")
        val downloaded = File(downloads, "claude-real.bin")

        var version = BUNDLED_CLAUDE_VERSION
        val networkSuccess = runCatching {
            val latest = JSONObject(
                fetchText("https://registry.npmjs.org/@anthropic-ai/claude-code/latest"),
            ).getString("version")
            val base = "https://downloads.claude.ai/claude-code-releases/$latest"
            val manifest = JSONObject(fetchText("$base/manifest.json"))
            val checksum = manifest.getJSONObject("platforms").getJSONObject("linux-arm64").getString("checksum")
            downloadVerified("$base/linux-arm64/claude", downloaded, checksum) { bytes, total ->
                val ratio = if (total > 0L) bytes.toFloat() / total else 0f
                onProgress(
                    RuntimeInstallProgress(
                        "Claude Code $latest download ho raha hai…",
                        0.40f + ratio * 0.45f,
                        bytes,
                        total.takeIf { it > 0L },
                        event = RuntimeInstallEvent.DOWNLOAD,
                    ),
                )
            }
            version = latest
            downloaded.copyTo(real, overwrite = true)
            Os.chmod(real.absolutePath, 0b111101101)
            downloaded.delete()
            true
        }.getOrElse {
            Log.w("RuntimeInstaller", "Claude official download failed, fallback to bundle release", it)
            false
        }

        if (!networkSuccess) {
            onProgress(RuntimeInstallProgress("Claude Code bundle download ho raha hai…", 0.40f))
            val bundleUrl = "https://github.com/radhesolanki319-pixel/Hermes-Agent-Mobile-App/releases/download/agents-bundle-v1/claude.bin"
            val bundleSha256 = "22b0ce802cd9bb7ee44c4dba0ba962fb2e8029f19c7f979f490774d6c6f042df"
            downloadVerified(bundleUrl, downloaded, bundleSha256) { bytes, total ->
                val ratio = if (total > 0L) bytes.toFloat() / total else 0f
                onProgress(
                    RuntimeInstallProgress(
                        "Claude Code bundle download ho raha hai…",
                        0.40f + ratio * 0.45f,
                        bytes,
                        total.takeIf { it > 0L },
                        event = RuntimeInstallEvent.DOWNLOAD,
                    ),
                )
            }
            extractTarGz(downloaded, termux.prefix)
            downloaded.delete()
            check(real.isFile) { "claude-real extract fail" }
            Os.chmod(real.absolutePath, 0b111101101)
            version = BUNDLED_CLAUDE_VERSION
        }

        finishClaudeTermuxInstall(termux, version, onProgress)
    }

    private suspend fun installHermesFromBundle(
        proot: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        installRuntimeOverlay(
            bundle = HERMES_BUNDLE,
            message = str(R.string.rt_install_installing_version, "Hermes Agent", HERMES_VERSION),
            from = fraction,
            to = 0.95f,
            onProgress = onProgress,
        )
        // Ensure uv is present so warm transport and package management work seamlessly
        runCatching { ensureUvInstalled(proot, 0.95f, 0.98f, onProgress) }
        verifyGuest(proot, "$HERMES_GUEST_PATH --version", str(R.string.rt_install_verify_failed, "Hermes Agent"))
        hermesMarker.writeText(HERMES_VERSION)
        require(isAgentInstalled(com.jarves.mh.model.AgentKind.HERMES)) {
            "Hermes Agent installation is incomplete"
        }
        // Best-effort warm transport pre-warm: install aiohttp if not present
        runCatching { ensureHermesWarmTransport() }
    }

    /**
     * v32 one-time repair: the warm transport's in-guest API server is
     * part of Hermes's gateway package, which imports aiohttp — a
     * dependency the pinned wheel only carries in its messaging
     * extras, so installs made before v32 (PyPI, source, and the
     * prebuilt bundle alike) do not have it and the gateway would start
     * with no listener. Both install routes now add
     * [HERMES_AIOHTTP_SPEC] to the spec; this repair brings existing
     * on-device venvs up to the same state.
     *
     * Best-effort by contract: returns whether the venv can import
     * aiohttp when it returns, never throws, and is meant to run in the
     * background on a worker dispatcher — chat never waits on it (the
     * bridge's CLI fallback covers every turn until the warm server
     * can start).
     */
    suspend fun ensureHermesWarmTransport(): Boolean {
        return runCatching {
            if (!isAgentInstalled(com.jarves.mh.model.AgentKind.HERMES)) return@runCatching false
            val installed = installedRuntime()
            val python = "$HERMES_PREFIX_GUEST_PATH/venv/bin/python"
            val probe = "$python -c \"import aiohttp\""
            if (guestCommandSucceeds(installed.proot, probe)) return@runCatching true
            if (!isUvInstalled()) {
                runCatching { ensureUvInstalled(installed.proot, 0f, 0.5f) {} }
            }
            val pipCommand = if (isUvInstalled()) {
                "$UV_GUEST_PATH pip install --python $python \"$HERMES_AIOHTTP_SPEC\""
            } else {
                "$python -m pip install \"$HERMES_AIOHTTP_SPEC\""
            }
            runGuestCommand(
                proot = installed.proot,
                command = pipCommand,
                displayCommand = "pip install aiohttp (Hermes warm transport)",
                fraction = 0f,
                timeoutMs = 5 * 60 * 1_000L,
                onProgress = {},
                failureMessage = "Could not install the Hermes warm-transport dependency",
            )
            guestCommandSucceeds(installed.proot, probe)
        }.getOrDefault(false)
    }

    /** True when [command] exits 0 inside the guest; every failure reads as false. */
    private fun guestCommandSucceeds(proot: File, command: String): Boolean = runCatching {
        val proc = process(
            proot = proot,
            rootfs = rootfs,
            workspace = File(rootfs, "workspace"),
            environment = emptyMap(),
            guestCommand = listOf("/bin/bash", "-lc", command),
            guestWorkspacePath = "/workspace",
            emulateHardLinks = true,
            outputFile = File(context.cacheDir, "hermes-warm-probe.log"),
        )
        proc.waitFor() == 0
    }.getOrDefault(false)

    /** The guest source checkout to install Hermes from, or null when absent. */
    private fun hermesSourceCheckout(): File? {
        val dir = File(rootfs, HERMES_SOURCE_GUEST_PATH.removePrefix("/"))
        return dir.takeIf {
            File(it, "pyproject.toml").isFile && File(it, "hermes_cli").isDirectory
        }
    }

    /**
     * GitHub release route (v40; replaces the PyPI route — Hermes stopped
     * publishing to PyPI after 0.19.0, and v0.23.2+ ships only as a GitHub
     * release): pinned uv → uv-managed standalone CPython 3.14
     * (python-build-standalone; the guest's own Python is too old for
     * Hermes) → a dedicated venv under /root/.pocket-hermes → `uv pip
     * install "hermes-agent[acp,mcp] @ <release tarball URL>"`. Three
     * release-era details are handled in the install script:
     *  - HERMES_NIX_BUILD=1 lifts upstream's setup.py block on wheel builds
     *    (their supported escape hatch for packagers; the tarball is a
     *    source archive, so uv must build it).
     *  - The project's own dependency set is internally inconsistent on
     *    pillow (browser-harness pins 12.3.0, Hermes pins 13.0.0); the
     *    project's [tool.uv] override only applies to `uv sync`, so the
     *    same override is passed explicitly with --overrides.
     *  - The tarball's pyproject carries the placeholder version 0.0.0, so
     *    the installed dist metadata under-reports; this route's marker
     *    discipline (the marker is written by hand after the binary
     *    verifies) is what the app reads, exactly as before.
     * Used for the pinned first install and for updates to a user-approved
     * newer release; the version marker is written only after the
     * installed binary verifies.
     */
    private suspend fun installHermesFromGitHubRelease(
        proot: File,
        version: String,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val span = 1f - fraction
        ensureUvInstalled(proot, fraction, fraction + span * 0.25f, onProgress)
        writeResolver()
        val installCommand = """
            set -e
            export UV_PYTHON_INSTALL_DIR=$HERMES_PREFIX_GUEST_PATH/python
            export HERMES_NIX_BUILD=1
            $UV_GUEST_PATH python install 3.14
            rm -rf $HERMES_PREFIX_GUEST_PATH/venv
            $UV_GUEST_PATH venv --python 3.14 $HERMES_PREFIX_GUEST_PATH/venv
            printf 'pillow==13.0.0\n' > /tmp/hermes-overrides.txt
            $UV_GUEST_PATH pip install --python $HERMES_PREFIX_GUEST_PATH/venv/bin/python --overrides /tmp/hermes-overrides.txt "hermes-agent[acp,mcp] @ https://github.com/radhesolanki319-pixel/Hermes-agent-/archive/refs/heads/main.tar.gz" "$HERMES_AIOHTTP_SPEC"
            $HERMES_GUEST_PATH --version
        """.trimIndent()
        runGuestCommand(
            proot = proot,
            command = installCommand,
            displayCommand = "uv pip install hermes-agent[acp,mcp] v$version (GitHub release) + aiohttp (Python 3.14)",
            fraction = fraction + span * 0.5f,
            timeoutMs = 20 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = str(R.string.rt_install_hermes_failed),
        )
        verifyGuest(proot, "$HERMES_GUEST_PATH --version", str(R.string.rt_install_verify_failed, "Hermes Agent"))
        hermesMarker.writeText(version)
        require(isAgentInstalled(com.jarves.mh.model.AgentKind.HERMES)) {
            "Hermes Agent installation is incomplete"
        }
    }

    /**
     * Source route: installs the checkout at [HERMES_SOURCE_GUEST_PATH]
     * through the project's own lockfile — `uv sync --extra acp --python 3.14
     * --locked` inside the checkout, the only from-source route the Phase 0
     * spike verified (a plain pip resolution from a checkout fails on the
     * project's pillow pin conflict, because its uv overrides only apply when
     * the project is the resolution root). UV_PROJECT_ENVIRONMENT redirects
     * the synced environment into the usual /root/.pocket-hermes/venv, so the
     * entry point lands at HERMES_GUEST_PATH exactly like the PyPI route; the
     * sync installs the project editable, i.e. the venv keeps resolving the
     * packages from the checkout itself.
     *
     * The version marker records the checkout's own [project] version with a
     * "+src" suffix (a raw checkout self-reports the placeholder 0.0.0), so
     * update checks keep offering the latest PyPI release; accepting such an
     * update replaces the source install with the released package and
     * leaves the checkout directory itself untouched.
     */
    private suspend fun installHermesFromSource(
        proot: File,
        sourceCheckout: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val span = 1f - fraction
        ensureUvInstalled(proot, fraction, fraction + span * 0.25f, onProgress)
        writeResolver()
        val syncCommand = """
            set -e
            export UV_PYTHON_INSTALL_DIR=$HERMES_PREFIX_GUEST_PATH/python
            export UV_PROJECT_ENVIRONMENT=$HERMES_PREFIX_GUEST_PATH/venv
            $UV_GUEST_PATH python install 3.14
            rm -rf $HERMES_PREFIX_GUEST_PATH/venv
            cd $HERMES_SOURCE_GUEST_PATH
            $UV_GUEST_PATH sync --extra acp --python 3.14 --locked
            $UV_GUEST_PATH pip install --python $HERMES_PREFIX_GUEST_PATH/venv/bin/python "$HERMES_AIOHTTP_SPEC"
            $HERMES_GUEST_PATH --version
        """.trimIndent()
        runGuestCommand(
            proot = proot,
            command = syncCommand,
            displayCommand = "uv sync --extra acp --locked + aiohttp (Hermes source: $HERMES_SOURCE_GUEST_PATH)",
            fraction = fraction + span * 0.5f,
            timeoutMs = 20 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = str(R.string.rt_install_hermes_failed),
        )
        verifyGuest(proot, "$HERMES_GUEST_PATH --version", str(R.string.rt_install_verify_failed, "Hermes Agent"))
        val checkoutVersion = File(sourceCheckout, "pyproject.toml").readTextOrNull()
            ?.let(::parseHermesProjectVersion)
            ?: "0.0.0"
        hermesMarker.writeText("$checkoutVersion+src")
        require(isAgentInstalled(com.jarves.mh.model.AgentKind.HERMES)) {
            "Hermes Agent installation is incomplete"
        }
    }

    /**
     * Installs one optional development stack inside Ubuntu. Safe to call again:
     * already-installed stacks return immediately without network access.
     */
    suspend fun ensureStackInstalled(
        stack: DevStack,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val runtime = installedRuntime()
        // Android's installer also repairs executable bits, Gradle configuration,
        // and compatibility links. Let it run when the bundle is already present;
        // installAndroidToolchain avoids downloading complete artifacts again.
        if (isStackInstalled(stack) && stack != DevStack.ANDROID) return
        applyStack(runtime.proot, stack, 0.05f, 0.95f, onProgress)
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_stack_ready, stack.localizedLabel(context)), 1f))
    }

    /**
     * Removes an optional development stack without touching projects or the core
     * Node.js/Git runtime. Package-backed stacks are purged through dpkg; bundled
     * stacks remove only their dedicated SDK/language directories.
     */
    suspend fun removeStack(
        stack: DevStack,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        require(stack != DevStack.WEB) { str(R.string.rt_install_web_not_removable) }
        val runtime = installedRuntime()
        if (!isStackInstalled(stack)) return

        onProgress(RuntimeInstallProgress(str(R.string.rt_install_removing_stack, stack.localizedLabel(context)), 0.1f, indeterminate = true))
        when (stack) {
            DevStack.WEB -> Unit
            DevStack.PYTHON -> removePythonStack()
            DevStack.ANDROID -> removeAndroidStack()
            DevStack.CPP -> aptRemove(
                runtime.proot,
                listOf("build-essential", "gcc", "g++", "gcc-10", "g++-10", "make", "cmake", "gdb"),
                0.45f,
                onProgress,
            )
            DevStack.PHP -> {
                aptRemove(
                    runtime.proot,
                    listOf("php-cli", "php-mbstring", "php-xml", "php-curl", "php-zip"),
                    0.45f,
                    onProgress,
                )
                removePath(File(rootfs, "usr/local/bin/composer"))
                removePath(File(rootfs, "root/.cache/composer"))
                removePath(File(rootfs, "root/.composer"))
            }
        }

        writeDevStackState(readDevStackState().apply { put(stack.name, false) })
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_stack_removed, stack.localizedLabel(context)), 1f))
    }

    private fun removePythonStack() {
        listOf(
            ".pocket-python-tools-version",
            "usr/bin/python3",
            "usr/bin/python3.8",
            "usr/bin/pip",
            "usr/bin/pip3",
            "usr/lib/aarch64-linux-gnu/libpython3.8.so.1",
            "usr/lib/aarch64-linux-gnu/libpython3.8.so.1.0",
            "usr/lib/python3",
            "usr/lib/python3.8",
            "usr/local/lib/python3.8",
            "usr/share/python3",
            "usr/share/python-wheels",
            "root/.cache/pip",
        ).forEach { removePath(File(rootfs, it)) }
    }

    private fun removeAndroidStack() {
        listOf(
            "root/android-sdk",
            "root/maven",
            "root/.pocket-android-tools-version",
            "root/.gradle/caches",
            "root/.gradle/daemon",
            "root/.gradle/native",
            "root/.gradle/notifications",
            "root/.gradle/wrapper/dists",
            "root/.gradle/init.d/pocketdev-android.gradle",
            "opt/gradle",
            "opt/jdk-17.0.20.1+1",
            "usr/local/bin/jar",
            "usr/local/bin/jarsigner",
            "usr/local/bin/java",
            "usr/local/bin/javac",
            "usr/local/bin/javadoc",
            "usr/local/bin/keytool",
        ).forEach { removePath(File(rootfs, it)) }
        removeAndroidGradleProperty()
    }

    private fun removeAndroidGradleProperty() {
        val properties = File(rootfs, "root/.gradle/gradle.properties")
        if (!properties.isFile) return
        val remaining = properties.readLines().filterNot(::isManagedAndroidGradleProperty)
        if (remaining.isEmpty()) {
            properties.delete()
        } else {
            properties.writeText(remaining.joinToString("\n").trimEnd() + "\n")
        }
    }

    private fun removePath(file: File) {
        if (file.isDirectory && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
            check(file.deleteRecursively()) { "Could not remove ${file.name}" }
        } else if (file.exists() || java.nio.file.Files.isSymbolicLink(file.toPath())) {
            check(file.delete()) { "Could not remove ${file.name}" }
        }
    }

    private suspend fun applyStack(
        proot: File,
        stack: DevStack,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        var verified = true
        when (stack) {
            DevStack.WEB -> {
                onProgress(RuntimeInstallProgress(str(R.string.rt_install_checking_node), from))
                verifyGuest(proot, "node --version && npm --version", str(R.string.rt_install_verify_failed, "Node.js"))
            }
            DevStack.PYTHON -> {
                installRuntimeOverlay(PYTHON_BUNDLE, str(R.string.rt_install_python), from, to, onProgress)
                runCatching {
                    verifyGuest(proot, "python3 --version && pip3 --version", str(R.string.rt_install_verify_failed, "Python"))
                }.onFailure { error ->
                    verified = false
                    onProgress(
                        RuntimeInstallProgress(
                            str(R.string.rt_install_python_verify_failed, error.message?.take(120).orEmpty()),
                            to,
                        ),
                    )
                }
            }
            DevStack.ANDROID -> {
                installAndroidToolchain(proot, from, to, onProgress)
            }
            DevStack.CPP -> {
                aptInstall(
                    proot,
                    // Node 24's native addons (node-gyp) need C++20 and Python;
                    // Ubuntu 20.04's default GCC 9 cannot build them.
                    listOf("build-essential", "gcc-10", "g++-10", "python3", "cmake", "gdb"),
                    str(R.string.rt_install_cpp),
                    from,
                    onProgress,
                )
                verifyGuest(
                    proot,
                    "gcc --version && g++ --version && gcc-10 --version && g++-10 --version && python3 --version && make --version && cmake --version",
                    str(R.string.rt_install_verify_failed, "C/C++"),
                )
            }
            DevStack.PHP -> {
                aptInstall(
                    proot,
                    listOf("php-cli", "php-mbstring", "php-xml", "php-curl", "php-zip", "unzip"),
                    str(R.string.rt_install_php),
                    from,
                    onProgress,
                )
                installComposer(proot, from, onProgress)
                verifyGuest(proot, "php --version && composer --version", str(R.string.rt_install_verify_failed, "PHP"))
            }
        }
        if (!verified) return
        writeDevStackState(readDevStackState().apply { put(stack.name, true) })
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_stack_installed, stack.localizedLabel(context)), to))
    }

    /**
     * Installs Composer into Ubuntu from the official latest-stable release,
     * verified against getcomposer.org's published SHA-256 checksum.
     */
    private suspend fun installComposer(
        proot: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val composer = File(rootfs, "usr/local/bin/composer")
        if (composer.isFile) return
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_downloading_named, "Composer"), fraction))
        downloads.mkdirs()
        val staged = File(downloads, "composer.phar")
        val checksum = fetchText("https://getcomposer.org/download/latest-stable/composer.phar.sha256sum")
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.substringBefore(' ')
            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?: error("Composer checksum was not found")
        downloadVerified(
            "https://getcomposer.org/download/latest-stable/composer.phar",
            staged,
            checksum,
        ) { _, _ -> }
        composer.parentFile?.mkdirs()
        if (composer.exists()) composer.delete()
        // cacheDir and filesDir can live on different mounts: copy instead of rename.
        staged.inputStream().use { input -> FileOutputStream(composer).use { input.copyTo(it) } }
        staged.delete()
        Os.chmod(composer.absolutePath, 0b111101101)
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_installing_named, "Composer"), fraction))
    }

    private suspend fun installAndroidToolchain(
        proot: File,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val marker = File(rootfs, "root/.pocket-android-tools-version")
        val androidHome = File(rootfs, "root/android-sdk")
        val gradleHome = File(rootfs, "opt/gradle")
        val localMaven = File(rootfs, "root/maven/localMvnRepository")
        if (marker.readTextOrNull() != ANDROID_TOOLS_VERSION ||
            !File(androidHome, "platforms/android-36/android.jar").isFile ||
            !File(androidHome, "build-tools/35.0.0/aapt2").isFile ||
            !File(gradleHome, "gradle-8.14.3/bin/gradle").isFile ||
            !localMaven.isDirectory) {
            installRuntimeOverlay(
                ANDROID_BUNDLE,
                str(R.string.rt_install_android_tools),
                from,
                to,
                onProgress,
            )
            makeAndroidToolsExecutable(androidHome, gradleHome)
            marker.parentFile?.mkdirs()
            marker.writeText(ANDROID_TOOLS_VERSION)
        }
        // Keep this outside the download/install branch so app updates repair
        // existing Android toolchains without downloading the bundles again.
        writeAndroidGradleConfiguration(rootfs)

        verifyGuest(
            proot,
            "java -version 2>&1 | grep -E '\"17\\.|version 17' && " +
                "gradle --version && aapt2 version && test -f \"${'$'}ANDROID_HOME/platforms/android-36/android.jar\"",
            str(R.string.rt_install_verify_failed, "Android SDK, Gradle, Java"),
        )
    }

    private suspend fun installRuntimeOverlay(
        bundle: RuntimeBundle,
        message: String,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
        forceEmbedded: Boolean = false,
    ) {
        // Stack overlays honor the same offline/online flavor as the Core bundle:
        // the offline APK ships every stack bundle inside its assets, while the
        // online APK fetches each one from the release URL on demand.
        val archive = obtainRuntimeBundle(bundle, preferEmbedded = forceEmbedded || BuildConfig.OFFLINE_RUNTIME_BUNDLES, from, to * 0.8f + from * 0.2f, onProgress)
        onProgress(RuntimeInstallProgress(message, to * 0.8f + from * 0.2f, indeterminate = true))
        extractZstdTar(archive, rootfs)
        stripMacosMetadataArtifacts(rootfs)
        if (archive.parentFile == downloads) archive.delete()
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_bundle_tools_installed, bundle.label), to))
    }

    /**
     * Removes AppleDouble-style metadata files (`._*`) that some on-device
     * tar builders and macOS tarballs embed alongside the real files. They
     * are harmless to most tools, but Python's site module reads every
     * `.pth` file in site-packages and crashes when one of them is a
     * binary metadata blob.
     */
    private fun stripMacosMetadataArtifacts(root: File) {
        if (!root.isDirectory) return
        val queue = ArrayDeque<File>()
        queue.add(root)
        var removed = 0
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.name.startsWith("._")) {
                    if (child.isDirectory) child.deleteRecursively() else child.delete()
                    removed++
                } else if (child.isDirectory && !java.nio.file.Files.isSymbolicLink(child.toPath())) {
                    queue.add(child)
                }
            }
        }
        if (removed > 0) {
            android.util.Log.i("RuntimeInstaller", "Stripped $removed macOS metadata artifacts")
        }
    }

    private suspend fun obtainRuntimeBundle(
        bundle: RuntimeBundle,
        preferEmbedded: Boolean,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ): File {
        downloads.mkdirs()
        val destination = File(downloads, bundle.fileName)
        val shouldTryEmbedded = preferEmbedded || BuildConfig.OFFLINE_RUNTIME_BUNDLES
        val embeddedStream = if (shouldTryEmbedded) {
            runCatching { context.assets.open("runtime/${bundle.fileName}") }.getOrNull()
        } else null

        if (embeddedStream != null) {
            val loadingLabel = str(R.string.rt_install_loading_bundle, bundle.label)
            onProgress(RuntimeInstallProgress(loadingLabel, from, 0, bundle.compressedBytes))
            val temporary = File(downloads, "${bundle.fileName}.part")
            val extractionOk = runCatching {
                embeddedStream.use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var copied = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            val ratio = (copied.toFloat() / bundle.compressedBytes).coerceIn(0f, 1f)
                            onProgress(RuntimeInstallProgress(loadingLabel, from + ratio * (to - from), copied, bundle.compressedBytes))
                        }
                    }
                }
                require(digest(temporary, "SHA-256").equals(bundle.sha256, ignoreCase = true)) {
                    "${bundle.label} bundle checksum mismatch"
                }
                if (destination.exists()) destination.delete()
                check(temporary.renameTo(destination)) { "Could not stage the ${bundle.label} bundle" }
                true
            }.onFailure {
                temporary.delete()
                Log.w("RuntimeInstaller", "Embedded bundle ${bundle.label} extraction failed or checksum mismatch, falling back to download", it)
            }.getOrDefault(false)

            if (extractionOk) return destination
        }

        val url = "${bundle.releaseBaseUrl ?: BuildConfig.RUNTIME_RELEASE_BASE_URL}/${bundle.fileName}"
        val downloadingLabel = str(R.string.rt_install_downloading_bundle, bundle.label)
        downloadVerified(url, destination, bundle.sha256) { downloaded, total ->
            val ratio = if (total > 0) downloaded.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress(downloadingLabel, from + ratio * (to - from), downloaded, total.takeIf { it > 0 }))
        }
        return destination
    }

    private suspend fun installZipAsset(
        url: String,
        checksum: String,
        archiveName: String,
        destination: File,
        message: String,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        downloads.mkdirs()
        val archive = File(downloads, archiveName)
        downloadVerified(url, archive, checksum) { downloaded, total ->
            val ratio = if (total > 0) downloaded.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress(message, from + ratio * (to - from), downloaded, total.takeIf { it > 0 }))
        }
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_installing_named, archiveName.removeSuffix(".zip")), to, indeterminate = true))
        val staging = File(destination.parentFile, "${destination.name}.installing")
        staging.deleteRecursively()
        staging.mkdirs()
        extractZipArchive(archive, staging)
        destination.deleteRecursively()
        check(staging.renameTo(destination)) { "Could not activate ${destination.name}" }
        archive.delete()
    }

    private fun extractZipArchive(archive: File, destination: File) {
        ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.removePrefix("./").trimStart('/')
                if (name.isNotBlank()) {
                    val target = safeChild(destination, name)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output -> zip.copyTo(output) }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun makeAndroidToolsExecutable(androidHome: File, gradleHome: File) {
        val buildTools = File(androidHome, "build-tools/35.0.0")
        listOf("aapt", "aapt2", "aidl", "apksigner", "d8", "dexdump", "split-select", "zipalign")
            .map { File(buildTools, it) }
            .plus(File(gradleHome, "gradle-8.14.3/bin/gradle"))
            .filter(File::isFile)
            .forEach { Os.chmod(it.absolutePath, 0b111101101) }
    }

    private fun writeAndroidGradleInitScript(runtimeRootfs: File) {
        val script = File(runtimeRootfs, "root/.gradle/init.d/pocketdev-android.gradle")
        script.parentFile?.mkdirs()
        script.writeText(
            """
            def pocketMaven = uri('/root/maven/localMvnRepository')
            beforeSettings { settings ->
                settings.pluginManagement.repositories {
                    maven { url = pocketMaven }
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            settingsEvaluated { settings ->
                settings.dependencyResolutionManagement.repositories {
                    maven { url = pocketMaven }
                }
            }
            gradle.beforeProject { project ->
                project.extensions.extraProperties.set(
                    'android.aapt2FromMavenOverride',
                    '/root/android-sdk/build-tools/35.0.0/aapt2'
                )
                project.buildscript.repositories {
                    maven { url = pocketMaven }
                }
            }
            """.trimIndent() + "\n",
        )
    }

    @Synchronized
    private fun writeAndroidGradleConfiguration(runtimeRootfs: File) {
        val aapt2 = File(runtimeRootfs, ANDROID_AAPT2_HOST_PATH)
        if (!aapt2.isFile) return

        writeAndroidGradleInitScript(runtimeRootfs)
        val gradleDir = File(runtimeRootfs, "root/.gradle").apply { mkdirs() }
        val properties = File(gradleDir, "gradle.properties")
        val existingLines = properties.readTextOrNull()?.lineSequence()?.toList().orEmpty()
        val managedLines = listOf(
            "$ANDROID_AAPT2_PROPERTY=$ANDROID_AAPT2_GUEST_PATH",
            "$GRADLE_JVMARGS_PROPERTY=-Xmx512m -XX:MaxMetaspaceSize=256m -Dfile.encoding=UTF-8",
            "$GRADLE_WORKERS_PROPERTY=2",
            "$GRADLE_PARALLEL_PROPERTY=false",
            "$KOTLIN_EXECUTION_PROPERTY=in-process",
        )
        val updatedLines = existingLines.filterNot(::isManagedAndroidGradleProperty) + managedLines
        if (existingLines == updatedLines) return

        val temporary = File(gradleDir, "gradle.properties.pocketdev.tmp")
        temporary.writeText(updatedLines.joinToString("\n").trimEnd() + "\n")
        Os.rename(temporary.absolutePath, properties.absolutePath)
    }

    private fun isManagedAndroidGradleProperty(line: String): Boolean =
        MANAGED_ANDROID_GRADLE_PROPERTIES.any { key ->
            Regex("^\\s*${Regex.escape(key)}\\s*[:=].*$").matches(line)
        }

    /**
     * One-time upgrade path from the old single-bundle layout: devices that already
     * installed every tool keep all stacks without re-downloading anything.
     */
    fun migrateLegacyToolMarkers() {
        if (!File(rootfs, "usr/bin/bash").isFile) return
        if (languageToolsMarker.readTextOrNull() != LANGUAGE_TOOLS_VERSION) return
        if (coreToolsMarker.readTextOrNull() != CORE_TOOLS_VERSION) coreToolsMarker.writeText(CORE_TOOLS_VERSION)
        val state = readDevStackState()
        DevStack.entries.forEach { stack -> if (!state.containsKey(stack.name)) state[stack.name] = true }
        writeDevStackState(state)
    }

    fun installedStacks(): Set<DevStack> = readDevStackState()
        .filterValues { it }
        .keys
        .mapNotNull { name -> runCatching { DevStack.valueOf(name) }.getOrNull() }
        .filter(::isStackInstalled)
        .toSet()

    fun isStackInstalled(stack: DevStack): Boolean {
        if (readDevStackState()[stack.name] != true) return false
        // Older C/C++ installs lack GCC 10 and Python, which native npm packages need.
        if (stack == DevStack.CPP) return hasNativeAddonToolchain(rootfs)
        if (stack != DevStack.ANDROID) return true
        return File(rootfs, "root/.pocket-android-tools-version").readTextOrNull() == ANDROID_TOOLS_VERSION &&
            File(rootfs, "root/android-sdk/platforms/android-36/android.jar").isFile &&
            File(rootfs, "root/android-sdk/build-tools/35.0.0/aapt2").isFile &&
            File(rootfs, "opt/gradle/gradle-8.14.3/bin/gradle").isFile &&
            File(rootfs, "root/maven/localMvnRepository").let { it.isDirectory && !it.list().isNullOrEmpty() } &&
            File(rootfs, "root/.gradle/init.d/pocketdev-android.gradle").isFile
    }

    private fun hasNativeAddonToolchain(targetRootfs: File): Boolean =
        File(targetRootfs, "usr/bin/gcc-10").canExecute() &&
            File(targetRootfs, "usr/bin/g++-10").canExecute() &&
            File(targetRootfs, "usr/bin/python3").canExecute()

    private fun readDevStackState(): MutableMap<String, Boolean> {
        if (!devStacksFile.isFile) return mutableMapOf()
        return runCatching {
            val obj = JSONObject(devStacksFile.readText())
            mutableMapOf<String, Boolean>().apply {
                DevStack.entries.forEach { stack ->
                    if (obj.has(stack.name)) put(stack.name, obj.optBoolean(stack.name))
                }
            }
        }.getOrDefault(mutableMapOf())
    }

    private fun writeDevStackState(state: Map<String, Boolean>) {
        devStacksFile.parentFile?.mkdirs()
        val obj = JSONObject()
        state.forEach { (name, value) -> obj.put(name, value) }
        devStacksFile.writeText(obj.toString())
    }

    private suspend fun installNodeIfNeeded(
        proot: File,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (File(rootfs, "usr/local/bin/node").exists()) return
        val nodeDownloadLabel = str(R.string.rt_install_downloading_node, NODE_VERSION)
        onProgress(RuntimeInstallProgress(nodeDownloadLabel, from))
        downloads.mkdirs()
        val nodeFileName = "node-$NODE_VERSION-linux-arm64.tar.gz"
        val nodeBaseUrl = "https://nodejs.org/dist/$NODE_VERSION"
        val checksum = fetchText("$nodeBaseUrl/SHASUMS256.txt")
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.endsWith("  $nodeFileName") }
            ?.substringBefore(' ')
            ?: error("Node.js checksum was not found")
        val nodeArchive = File(downloads, nodeFileName)
        downloadVerified("$nodeBaseUrl/$nodeFileName", nodeArchive, checksum) { downloaded, total ->
            val ratio = if (total > 0) downloaded.toFloat() / total else 0f
            onProgress(
                RuntimeInstallProgress(
                    nodeDownloadLabel,
                    from + ratio * (to - from),
                    downloaded,
                    total.takeIf { it > 0 },
                ),
            )
        }
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_node), to))
        val nodeStaging = File(runtimeDir, "node.installing")
        nodeStaging.deleteRecursively()
        nodeStaging.mkdirs()
        extractNodeArchive(nodeArchive, nodeStaging)
        val nodeHome = File(rootfs, "usr/local/lib/nodejs")
        nodeHome.deleteRecursively()
        nodeHome.parentFile?.mkdirs()
        check(nodeStaging.renameTo(nodeHome)) { "Could not activate Node.js" }
        val localBin = File(rootfs, "usr/local/bin").apply { mkdirs() }
        listOf("node", "npm", "npx", "corepack").forEach { command ->
            val link = File(localBin, command)
            if (link.exists() || java.nio.file.Files.isSymbolicLink(link.toPath())) link.delete()
            Os.symlink("../lib/nodejs/bin/$command", link.absolutePath)
        }
        nodeArchive.delete()
    }

    private suspend fun aptInstall(
        proot: File,
        packages: List<String>,
        message: String,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        onProgress(RuntimeInstallProgress(message, fraction))
        aptInstallInternal(proot, packages, fraction, onProgress)
    }

    private suspend fun runSystemMaintenance(
        proot: File,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        onProgress(
            RuntimeInstallProgress(
                message = str(R.string.rt_install_updating_ubuntu),
                fraction = 0.69f,
                indeterminate = true,
            ),
        )
        writeResolver()
        val command = "export DEBIAN_FRONTEND=noninteractive; " +
            "dpkg --configure -a && " +
            "apt-get -o DPkg::Lock::Timeout=120 -f install -y && " +
            "apt-get -o DPkg::Lock::Timeout=120 update && " +
            "apt-get -o DPkg::Lock::Timeout=120 upgrade -y"
        runGuestCommand(
            proot = proot,
            command = command,
            displayCommand = "dpkg --configure -a && apt-get -f install -y && apt-get update && apt-get upgrade -y",
            fraction = 0.69f,
            timeoutMs = 35 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = str(R.string.rt_install_ubuntu_maintenance_failed),
        )
    }

    private suspend fun aptInstallInternal(
        proot: File,
        packages: List<String>,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        require(packages.isNotEmpty()) { "No packages selected" }
        writeResolver()
        val packageNames = packages.joinToString(" ")
        val command = "export DEBIAN_FRONTEND=noninteractive; " +
            "dpkg --configure -a && " +
            "apt-get -o DPkg::Lock::Timeout=120 -f install -y && " +
            "apt-get -o DPkg::Lock::Timeout=120 update && " +
            "apt-get -o DPkg::Lock::Timeout=120 install -y --no-install-recommends $packageNames && " +
            "apt-get clean && rm -rf /var/lib/apt/lists/*"
        runGuestCommand(
            proot = proot,
            command = command,
            displayCommand = "dpkg --configure -a && apt-get -f install -y && apt-get install -y $packageNames",
            fraction = fraction,
            timeoutMs = 30 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = str(R.string.rt_install_could_not_install, packageNames),
        )
    }

    private suspend fun aptRemove(
        proot: File,
        packages: List<String>,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        require(packages.isNotEmpty()) { "No packages selected" }
        val packageNames = packages.joinToString(" ")
        val command = "export DEBIAN_FRONTEND=noninteractive; " +
            "apt-get -o DPkg::Lock::Timeout=120 purge -y $packageNames && " +
            "apt-get clean && rm -rf /var/lib/apt/lists/*"
        runGuestCommand(
            proot = proot,
            command = command,
            displayCommand = "apt-get purge -y $packageNames",
            fraction = fraction,
            timeoutMs = 20 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = str(R.string.rt_install_could_not_remove, packageNames),
        )
    }

    private suspend fun runGuestCommand(
        proot: File,
        command: String,
        displayCommand: String,
        fraction: Float,
        timeoutMs: Long,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
        failureMessage: String,
    ) {
        onProgress(
            RuntimeInstallProgress(
                message = displayCommand,
                fraction = fraction,
                terminalLine = "root@pocket:~# $displayCommand",
                indeterminate = true,
                event = RuntimeInstallEvent.COMMAND,
            ),
        )
        val running = process(
            proot = proot,
            rootfs = rootfs,
            workspace = File(rootfs, "root"),
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/env", "bash", "-lc", command),
        )
        val native = running as? NativeSpawnProcess
        val collected = StringBuilder()
        try {
            withTimeout(timeoutMs) {
                var offset = 0L
                var pending = ""
                while (running.isAlive || (native?.outputFile?.length() ?: 0L) > offset) {
                    coroutineContext.ensureActive()
                    val file = native?.outputFile
                    if (file != null && file.length() > offset) {
                        RandomAccessFile(file, "r").use { input ->
                            input.seek(offset)
                            val available = (input.length() - offset).coerceAtMost(256 * 1024).toInt()
                            val bytes = ByteArray(available)
                            input.readFully(bytes)
                            offset += available
                            pending += bytes.toString(Charsets.UTF_8).replace('\r', '\n')
                        }
                        val parts = pending.split('\n')
                        pending = parts.last()
                        for (raw in parts.dropLast(1)) {
                            val line = sanitizeTerminalLine(raw)
                            if (line.isNotBlank()) {
                                collected.appendLine(line)
                                if (collected.length > MAX_COLLECTED_OUTPUT) collected.delete(0, collected.length - MAX_COLLECTED_OUTPUT)
                                onProgress(
                                    RuntimeInstallProgress(
                                        message = line,
                                        fraction = fraction,
                                        terminalLine = line,
                                        indeterminate = true,
                                        event = RuntimeInstallEvent.OUTPUT,
                                    ),
                                )
                            }
                        }
                    } else {
                        delay(80)
                    }
                }
                sanitizeTerminalLine(pending).takeIf(String::isNotBlank)?.let { line ->
                    collected.appendLine(line)
                    onProgress(RuntimeInstallProgress(line, fraction, terminalLine = line, indeterminate = true, event = RuntimeInstallEvent.OUTPUT))
                }
            }
        } finally {
            if (running.isAlive) running.destroy()
        }
        val exit = running.waitFor()
        onProgress(
            RuntimeInstallProgress(
                message = if (exit == 0) str(R.string.rt_install_command_completed) else str(R.string.rt_install_command_failed, exit),
                fraction = fraction,
                terminalLine = "[exit $exit] $displayCommand",
                event = RuntimeInstallEvent.COMMAND_COMPLETED,
            ),
        )
        check(exit == 0) { actionableProcessError(collected.toString(), failureMessage) }
    }

    private fun sanitizeTerminalLine(raw: String): String = raw
        .replace(ANSI_ESCAPE, "")
        .filter { it == '\t' || it.code >= 32 }
        .take(MAX_TERMINAL_LINE)

    private suspend fun verifyGuest(proot: File, command: String, failureMessage: String) {
        val verify = process(
            proot = proot,
            rootfs = rootfs,
            workspace = File(rootfs, "root"),
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/env", "bash", "-lc", command),
        )
        withTimeout(60_000L) {
            while (verify.isAlive) delay(50)
        }
        val exit = verify.waitFor()
        val output = (verify as? NativeSpawnProcess)?.outputFile
            ?.let(::readProcessOutputSafely)
            .orEmpty()
            .trim()
        check(exit == 0) { actionableProcessError(output, failureMessage) }
    }

    /**
     * Ubuntu 20.04 uses a merged-/usr layout. A partially extracted or upgraded
     * runtime can lose these top-level links while all readiness markers remain,
     * making every ELF executable misleadingly fail with ENOENT. Restore only
     * the known Ubuntu compatibility links and never replace real directories.
     */
    private fun ensureRootfsCompatibilityLinks(targetRootfs: File = rootfs): Boolean {
        if (!targetRootfs.isDirectory) return false
        val links = mapOf(
            "bin" to "usr/bin",
            "lib" to "usr/lib",
            "sbin" to "usr/sbin",
        )
        return runCatching {
            links.forEach { (name, destination) ->
                val link = File(targetRootfs, name)
                val path = link.toPath()
                if (java.nio.file.Files.isSymbolicLink(path)) {
                    if (java.nio.file.Files.readSymbolicLink(path).toString() != destination) {
                        java.nio.file.Files.delete(path)
                        Os.symlink(destination, link.absolutePath)
                    }
                } else if (link.exists()) {
                    check(link.isDirectory) { "Linux /$name is not a directory or symbolic link" }
                } else {
                    Os.symlink(destination, link.absolutePath)
                }
            }
            File(targetRootfs, "usr/bin/env").canExecute() &&
                File(targetRootfs, "usr/bin/bash").canExecute() &&
                File(targetRootfs, "lib/ld-linux-aarch64.so.1").exists()
        }.onFailure {
            android.util.Log.e("RuntimeInstaller", "Could not repair Linux compatibility links", it)
        }.getOrDefault(false)
    }

    private fun isValidCoreRootfs(targetRootfs: File): Boolean =
        ensureRootfsCompatibilityLinks(targetRootfs) &&
            File(targetRootfs, "usr/bin/bash").isFile &&
            File(targetRootfs, ".pocket-rootfs-version").readTextOrNull() == ROOTFS_VERSION

    private fun actionableProcessError(output: String, fallback: String): String {
        val lines = output.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val primaryProotError = lines.firstOrNull {
            it.startsWith("proot error:") && !it.contains("can't chmod")
        } ?: lines.firstOrNull { it.startsWith("proot error:") }
        return primaryProotError ?: output.trim().takeLast(1_000).ifBlank { fallback }
    }

    /**
     * Reads a captured PRoot process output file as UTF-8 with replacement,
     * so a stray non-UTF-8 byte in the bundled runtime (for example a binary
     * .pth file from a tarball built on a non-UTF-8 filesystem) does not
     * abort setup.
     */
    private fun readProcessOutputSafely(file: File): String = runCatching {
        file.readTailText(MAX_PROCESS_OUTPUT_BYTES)
    }.getOrElse { error ->
        android.util.Log.w("RuntimeInstaller", "Could not decode process output as UTF-8: ${error.message}")
        ""
    }

    suspend fun initializeExisting(onProgress: suspend (RuntimeInstallProgress) -> Unit): InstalledRuntime {
        // Stage E: on Termux there is no rootfs to validate — just clear any
        // stale bridge leftovers and hand back the Termux prefix.
        val termux = TermuxEnvironment(context)
        if (termux.isInstalled()) {
            File(context.filesDir, "runtime-bridge").apply {
                mkdirs(); listFiles()?.forEach { runCatching { it.delete() } }
            }
            if (bundledAssetPresent("hermes-bionic.bin")) {
                val pyBin = File(termux.prefix, "bin/python3.11")
                val hermesAgentDir = File(termux.prefix, "home/hermes/Hermes-agent-")
                val marker = termuxHermesMarker(termux)
                if (!pyBin.canExecute() || !hermesAgentDir.isDirectory || !marker.exists() || marker.readText().trim() != HERMES_VERSION) {
                    onProgress(RuntimeInstallProgress("Termux Bionic + Hermes unpack ho raha hai…", 0.15f))
                    installHermesFromApkBundle(termux, onProgress)
                }
            }
            return InstalledRuntime(termux.prefix, termux.prefix)
        }
        val installed = installedRuntime()
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_checking_files), 0.15f))
        writeResolver()
        ensureSettingsAndHooks()
        File(context.filesDir, "runtime-bridge").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_preparing_bridge), 0.42f))
        check(File(rootfs, "usr/local/bin/node").canExecute()) { "Core runtime is missing Node.js" }
        check(File(rootfs, "usr/bin/git").canExecute()) { "Core runtime is missing Git" }
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_runtime_ready), 1f))
        return installed
    }

    fun process(
        proot: File,
        rootfs: File,
        workspace: File,
        environment: Map<String, String>,
        guestCommand: List<String>,
        guestWorkspacePath: String = "/workspace",
        emulateHardLinks: Boolean = true,
        outputFile: File = File(context.cacheDir, "runtime-output-${System.nanoTime()}.log"),
        pseudoTerminal: Boolean = false,
        ptyRows: Int = 40,
        ptyColumns: Int = 120,
    ): Process {
        // Termux cut-over: once the Termux environment is installed it fully
        // replaces the PRoot guest for process execution. Guest paths in the
        // command and environment are translated to the real filesystem, the
        // Termux process environment is overlaid, and the same
        // NativeSpawnProcess handle is returned, so every bridge keeps
        // working unchanged. (Transitional: Stage E deletes the PRoot path.)
        val termux = TermuxEnvironment(context)
        if (termux.isInstalled()) {
            return termuxProcess(
                termux, workspace, environment, guestCommand, guestWorkspacePath,
                outputFile, pseudoTerminal, ptyRows, ptyColumns,
            )
        }
        // Stage E: PRoot execution path deleted — Termux is the only runtime.
        throw IllegalStateException("PRoot runtime hata diya gaya hai — pehle Termux install karo (Settings → Runtime)")
        require(
            guestWorkspacePath == "/workspace" ||
                Regex("^/workspace/[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$").matches(guestWorkspacePath),
        ) { "Invalid project workspace path" }
        workspace.mkdirs()
        File(rootfs, guestWorkspacePath.removePrefix("/")).mkdirs()
        // Self-heal devices whose Android tools were installed by an older app
        // version before the global AAPT2 override was persisted.
        writeAndroidGradleConfiguration(rootfs)
        ensureWorkspaceTrust(guestWorkspacePath)
        val bridge = File(context.filesDir, "runtime-bridge").apply { mkdirs() }
        val args = buildList {
            add(proot.absolutePath)
            if (emulateHardLinks) add("--link2symlink")
            add("-0")
            add("-r")
            add(rootfs.absolutePath)
            add("-b")
            add("/dev")
            add("-b")
            add("/proc")
            add("-b")
            add("/sys")
            // ARM64 Android build tools (notably aapt2) use Bionic's
            // /system/bin/linker64 and, on newer releases, APEX libraries.
            listOf("/system", "/apex", "/vendor", "/product").forEach { hostPath ->
                if (File(hostPath).exists()) {
                    File(rootfs, hostPath.removePrefix("/")).mkdirs()
                    add("-b")
                    add(hostPath)
                }
            }
            add("-b")
            add("${workspace.absolutePath}:$guestWorkspacePath")
            add("-b")
            add("${bridge.absolutePath}:/pocket-bridge")
            // v21: the whole app is the agent's environment — mount every
            // other project's workspace at its own /workspace/<slug> too
            // (otherwise siblings show up as empty mount-point dirs), plus
            // the cross-agent transcripts directory. Both best-effort.
            addAll(siblingProjectBindArgs(workspace, guestWorkspacePath, rootfs))
            addAll(agentChatsBindArgs(rootfs))
            add("-w")
            add(guestWorkspacePath)
            addAll(guestCommand)
        }
        val prootTemp = File(context.cacheDir, "proot-tmp").apply { mkdirs() }
        return NativeSpawnProcess.start(
            argv = args,
            environment = buildMap {
                put("HOME", "/root")
                val androidReady = File(rootfs, "root/.pocket-android-tools-version").readTextOrNull() == ANDROID_TOOLS_VERSION
                val basePath = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                if (androidReady) {
                    put("ANDROID_HOME", "/root/android-sdk")
                    put("ANDROID_SDK_ROOT", "/root/android-sdk")
                    put("GRADLE_HOME", "/opt/gradle/gradle-8.14.3")
                    put("GRADLE_USER_HOME", "/root/.gradle")
                    put("ORG_GRADLE_PROJECT_android.aapt2FromMavenOverride", "/root/android-sdk/build-tools/35.0.0/aapt2")
                    put("PATH", "/opt/gradle/gradle-8.14.3/bin:/root/android-sdk/build-tools/35.0.0:/root/android-sdk/cmdline-tools/latest/bin:$basePath")
                } else {
                    put("PATH", basePath)
                }
                put("LANG", "C.UTF-8")
                put("TERM", "xterm-256color")
                put("LD_LIBRARY_PATH", context.applicationInfo.nativeLibraryDir)
                put("PROOT_NO_SECCOMP", "1")
                put("PROOT_TMP_DIR", prootTemp.absolutePath)
                put("PROOT_LOADER", File(context.applicationInfo.nativeLibraryDir, "libprootloader.so").absolutePath)
                // Also protects any glibc helper Claude starts later.
                put("GLIBC_TUNABLES", "glibc.pthread.rseq=0")
                if (hasNativeAddonToolchain(rootfs)) {
                    // node-gyp compiles native npm packages with $CC/$CXX and
                    // Node 24 headers need C++20, which Ubuntu's default GCC 9 lacks.
                    // It finds python3 on PATH; npm 11 rejects npm_config_python.
                    put("CC", "/usr/bin/gcc-10")
                    put("CXX", "/usr/bin/g++-10")
                }
                // agy otherwise replaces its own binary in the background on any
                // run, moving users to an untested release behind the installed
                // version marker. Only "true" is honoured; "1" is ignored.
                put("AGY_CLI_DISABLE_AUTO_UPDATE", "true")
                putAll(environment)
            },
            cwd = context.filesDir.absolutePath,
            outputFile = outputFile,
            pseudoTerminal = pseudoTerminal,
            ptyRows = ptyRows,
            ptyColumns = ptyColumns,
        )
    }

    /**
     * The Termux twin of [process]: same contract, no PRoot. Translates the
     * guest command line and environment onto the real filesystem (see the
     * path rules in [termuxProcess]'s translate), spawns through
     * [NativeSpawnProcess] so callers keep the handle type they cast to, and
     * launches the outermost binary through the dynamic linker when the
     * app's targetSdk blocks direct execution of app-private files.
     */
    private fun termuxProcess(
        termux: TermuxEnvironment,
        workspace: File,
        environment: Map<String, String>,
        guestCommand: List<String>,
        guestWorkspacePath: String,
        outputFile: File,
        pseudoTerminal: Boolean,
        ptyRows: Int,
        ptyColumns: Int,
    ): Process {
        val prefix = termux.prefix
        val home = File(prefix, "home")
        val cwd = resolveTermuxWorkspace(guestWorkspacePath, workspace)
        cwd.mkdirs()
        // Sibling projects: /workspace/<slug> → that project's real directory.
        val siblingPaths: Map<String, String> = runCatching {
            AppPreferences(context).loadProjects().associate { project ->
                val base = File(context.filesDir, "workspaces/${project.id}")
                val host = if (project.rootPath.isBlank()) base else File(base, project.rootPath)
                "/workspace/${project.slug}" to host.absolutePath
            }
        }.getOrDefault(emptyMap())

        // v85 merge: single-pass ANCHORED translation (Muse's root-cause
        // fix for the v80 chat ENOENT, issue #1) replaces the sequential
        // String.replace chain. The chain rescanned its own output: the
        // Hermes venv path first became <prefix>/home/hermes/.venv, then
        // the blanket "/bin/" rule rewrote a segment INSIDE that real
        // path and produced a nonexistent argv[0]. Here every guest
        // prefix is matched in ONE regex pass over the original string,
        // only where a path token starts (start, or after whitespace /
        // ':' / '=' / quote), and structural prefixes additionally need
        // a path boundary after them — inserted real paths are never
        // rescanned. Antigravity's Hermes bypass below keys off
        // translatedArgv[0] and keeps working unchanged.
        val translateMap = LinkedHashMap<String, String>()
        fun mapGuest(guest: String, real: String) {
            if (guest.isNotEmpty() && guest !in translateMap) translateMap[guest] = real
        }
        // Hermes's venv: guest /root/.pocket-hermes/venv is the uv-sync
        // venv of the repo cloned at <home>/hermes on the Termux side.
        mapGuest("/root/.pocket-hermes/venv", File(home, "hermes/.venv").absolutePath)
        mapGuest("/pocket-bridge", File(context.filesDir, "runtime-bridge").absolutePath)
        mapGuest(guestWorkspacePath, cwd.absolutePath)
        siblingPaths.forEach { (guest, host) -> mapGuest(guest, host) }
        mapGuest(
            "/workspace/.agent-chats",
            File(context.filesDir, "workspaces/.agent-chats").absolutePath,
        )
        mapGuest("/workspace", workspace.absolutePath)
        mapGuest("/root", home.absolutePath)
        val binPrefix = "${prefix.absolutePath}/bin/"
        listOf("/usr/local/sbin/", "/usr/local/bin/", "/usr/sbin/", "/usr/bin/", "/sbin/", "/bin/")
            .forEach { mapGuest(it, binPrefix) }
        val structuralAlt = translateMap.keys
            .filter { !it.endsWith("/") }
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
        val binAlt = translateMap.keys
            .filter { it.endsWith("/") }
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
        val anchor = "(?:^|(?<=[\\s:=\"']))"
        val translateRegex = Regex(
            "$anchor(?:$structuralAlt)(?=\$|[/:\\s=\"'])|$anchor(?:$binAlt)",
        )

        fun translate(value: String): String =
            translateRegex.replace(value) { match -> translateMap[match.value] ?: match.value }

        val translatedArgv = guestCommand.map { translate(it) }

        // Self-heal: ensure bundled Bionic Termux + Hermes rootfs is unpacked before spawning
        if (bundledAssetPresent("hermes-bionic.bin")) {
            val pyBin = File(prefix, "bin/python3.11")
            val hermesAgentDir = File(home, "hermes/Hermes-agent-")
            val marker = termuxHermesMarker(termux)
            if (!pyBin.canExecute() || !hermesAgentDir.isDirectory || !marker.exists() || marker.readText().trim() != HERMES_VERSION) {
                android.util.Log.i("RuntimeInstaller", "Auto-healing bundled hermes-bionic before process execution...")
                kotlinx.coroutines.runBlocking {
                    installHermesFromApkBundle(termux) {}
                }
            }
        }
        ensureCoreToolchain(termux)

        val isHermesSpawn = translatedArgv.isNotEmpty() && (
            guestCommand[0] == HERMES_GUEST_PATH ||
            translatedArgv[0] == termuxHermesBinary(termux).absolutePath ||
            translatedArgv[0].endsWith("/home/hermes/.venv/bin/hermes")
        )

        val effectiveArgv = if (isHermesSpawn) {
            listOf(
                File(prefix, "bin/python3.11").absolutePath,
                "-u",
                "-m",
                "hermes_cli.main",
            ) + translatedArgv.drop(1)
        } else {
            translatedArgv
        }

        val targetBinary = File(effectiveArgv[0])
        val isElfBinary = termux.isElf(targetBinary)
        val linker = termux.linkerPath()
        val needsLinker = linker != null &&
            context.applicationInfo.targetSdkVersion >= 29 &&
            effectiveArgv.isNotEmpty() &&
            isElfBinary

        val argv = when {
            needsLinker -> listOf(linker!!) + effectiveArgv
            targetBinary.isFile && !isElfBinary -> {
                // Script with shebang: dynamic linker fails on non-ELF files ("bad ELF magic").
                // Execute script via Termux bash or /system/bin/sh.
                val bash = File(prefix, "bin/bash")
                if (bash.isFile && linker != null) {
                    listOf(linker, bash.absolutePath) + effectiveArgv
                } else {
                    listOf("/system/bin/sh") + effectiveArgv
                }
            }
            else -> effectiveArgv
        }

        val env = buildMap {
            putAll(termux.processEnv())
            environment.forEach { (key, value) ->
                // Termux owns these; caller values are guest-style and would
                // break the environment (guest PATH, HOME=/root, PRoot's
                // LD_LIBRARY_PATH pointing at the native lib dir).
                if (key == "PATH" || key == "HOME" ||
                    key == "LD_LIBRARY_PATH" || key == "LD_PRELOAD"
                ) return@forEach
                put(key, translate(value))
            }
            if (isHermesSpawn) {
                val hermesHome = File(home, "hermes")
                val hermesAgent = File(hermesHome, "Hermes-agent-")
                val venvPkgs = File(hermesHome, ".venv/lib/python3.11/site-packages")
                put("PYTHONPATH", "${hermesAgent.absolutePath}:${venvPkgs.absolutePath}")
                put("HERMES_HOME", hermesHome.absolutePath)
                val venvBin = File(hermesHome, ".venv/bin").absolutePath
                val prefixBin = "${prefix.absolutePath}/bin"
                val appletsBin = "${prefix.absolutePath}/bin/applets"
                put("PATH", "$prefixBin:$venvBin:$appletsBin:" + (get("PATH") ?: ""))
                // Bionic python3.11 must ONLY search Bionic lib. Glibc libraries must NEVER be searched.
                put("LD_LIBRARY_PATH", "${prefix.absolutePath}/lib")
                put("PYTHONUNBUFFERED", "1")

                val dotHermes = File(home, ".hermes")
                val hermesCfg = File(hermesHome, "config.yaml")
                val dotCfg = File(dotHermes, "config.yaml")
                val rootfsCfg = File(runtimeDir, "ubuntu/root/.hermes/config.yaml")
                if (!hermesCfg.exists()) {
                    when {
                        dotCfg.exists() -> runCatching { dotCfg.copyTo(hermesCfg, overwrite = true) }
                        rootfsCfg.exists() -> runCatching { rootfsCfg.copyTo(hermesCfg, overwrite = true) }
                        else -> {
                            val defaultRoute = HermesRoute(
                                provider = HermesProviderCatalog.CUSTOM_ID,
                                keyEnv = HermesProviderCatalog.CUSTOM_KEY_ENV,
                                baseUrl = HermesProviderCatalog.CUSTOM_DEF.baseUrl,
                                custom = true,
                            )
                            val defaultCfg = HermesConfigFiles.freshConfig(defaultRoute, "")
                            runCatching {
                                hermesHome.mkdirs()
                                hermesCfg.writeText(defaultCfg)
                                dotHermes.mkdirs()
                                dotCfg.writeText(defaultCfg)
                            }
                        }
                    }
                }
                val hermesEnv = File(hermesHome, ".env")
                val dotEnv = File(dotHermes, ".env")
                val rootfsEnv = File(runtimeDir, "ubuntu/root/.hermes/.env")
                if (!hermesEnv.exists()) {
                    when {
                        dotEnv.exists() -> runCatching { dotEnv.copyTo(hermesEnv, overwrite = true) }
                        rootfsEnv.exists() -> runCatching { rootfsEnv.copyTo(hermesEnv, overwrite = true) }
                        else -> {
                            val defaultEnv = "CUSTOM_PROVIDER_API_KEY=no-key-required\n"
                            runCatching {
                                hermesEnv.writeText(defaultEnv)
                                dotEnv.writeText(defaultEnv)
                            }
                        }
                    }
                }
            }
        }
        val touchesGlibcTree = !isHermesSpawn && translatedArgv.any { arg ->
            arg.contains("/opt/python-glibc/") ||
            arg == "${prefix.absolutePath}/bin/claude" ||
            arg.startsWith("${prefix.absolutePath}/glibc/")
        }
        val hasGlibc = File(prefix, "glibc/lib").isDirectory
        val finalEnv = if (touchesGlibcTree && hasGlibc) {
            env.toMutableMap().apply {
                val glibcDirs = listOfNotNull(
                    File(prefix, "glibc/lib").takeIf { it.isDirectory }?.absolutePath,
                    File(prefix, "opt/python-glibc/lib").takeIf { it.isDirectory }?.absolutePath,
                ).joinToString(":")
                put("LD_LIBRARY_PATH", glibcDirs)
                remove("LD_PRELOAD")
                put("GLIBC_TUNABLES", "glibc.pthread.rseq=0")
            }
        } else {
            env
        }
        return NativeSpawnProcess.start(
            argv = argv,
            environment = finalEnv,
            cwd = cwd.absolutePath,
            outputFile = outputFile,
            pseudoTerminal = pseudoTerminal,
            ptyRows = ptyRows,
            ptyColumns = ptyColumns,
        )
    }

    /** The real directory a guest workspace path names under Termux. */
    private fun resolveTermuxWorkspace(guestWorkspacePath: String, workspace: File): File {
        if (guestWorkspacePath == "/workspace") return workspace
        val slug = guestWorkspacePath.removePrefix("/workspace/").trim('/')
        if (slug == ".agent-chats") return File(context.filesDir, "workspaces/.agent-chats")
        val project = runCatching {
            AppPreferences(context).loadProjects().firstOrNull { it.slug == slug }
        }.getOrNull() ?: return workspace
        val base = File(context.filesDir, "workspaces/${project.id}")
        return if (project.rootPath.isBlank()) base else File(base, project.rootPath)
    }

    /**
     * Guarantees python, python3, bash, and git are properly symlinked,
     * executable, and that any broken glibc tool entries are purged from Hermes store.
     */
    fun ensureCoreToolchain(termux: TermuxEnvironment) {
        val prefix = termux.prefix
        val binDir = File(prefix, "bin").apply { mkdirs() }
        val py311 = File(binDir, "python3.11")
        val py314 = File(binDir, "python3.14")
        val pyTarget = when {
            py311.exists() -> "python3.11"
            py314.exists() -> "python3.14"
            else -> null
        }
        if (pyTarget != null) {
            val p = File(binDir, "python")
            val p3 = File(binDir, "python3")
            if (!p.exists()) runCatching { p.delete(); Os.symlink(pyTarget, p.absolutePath) }
            if (!p3.exists()) runCatching { p3.delete(); Os.symlink(pyTarget, p3.absolutePath) }
            runCatching { Os.chmod(File(binDir, pyTarget).absolutePath, 0b111101101) }
        }
        val venvBin = File(prefix, "home/hermes/.venv/bin")
        if (venvBin.isDirectory && pyTarget != null) {
            val vp = File(venvBin, "python")
            val vp3 = File(venvBin, "python3")
            runCatching { vp.delete(); Os.symlink("../../../../bin/$pyTarget", vp.absolutePath) }
            runCatching { vp3.delete(); Os.symlink("../../../../bin/$pyTarget", vp3.absolutePath) }
        }
        runCatching { Os.chmod(File(binDir, "bash").absolutePath, 0b111101101) }
        runCatching { Os.chmod(File(binDir, "git").absolutePath, 0b111101101) }
        val nodeFile = File(binDir, "node")
        if (nodeFile.exists()) runCatching { Os.chmod(nodeFile.absolutePath, 0b111101101) }
        val uvFile = File(binDir, "uv")
        if (uvFile.exists()) runCatching { Os.chmod(uvFile.absolutePath, 0b111101101) }

        cleanBrokenHermesTools(File(prefix, "home/hermes"))
        cleanBrokenHermesTools(File(prefix, "home/.hermes"))
        termux.ensureShims(force = false)

        // Purge non-ELF GNU ld text scripts in glibc/lib that cause Bionic linker crashes
        // (e.g. libc.so, libm.so, libpthread.so with "bad ELF magic: 2f2a2047").
        // Glibc runtime executables link against versioned SONAMEs (libc.so.6, libm.so.6, etc.)
        // and never need unversioned ld scripts.
        val glibcLib = File(prefix, "glibc/lib")
        if (glibcLib.isDirectory) {
            listOf("libc.so", "libm.so", "libpthread.so").forEach { libName ->
                val libFile = File(glibcLib, libName)
                if (libFile.exists() && !termux.isElf(libFile)) {
                    runCatching { libFile.delete() }
                }
            }
        }

        // Auto-purge stale or broken Claude files if marker or binaries are incomplete
        if (!isAgentInstalled(com.jarves.mh.model.AgentKind.CLAUDE_CODE) &&
            (File(prefix, "bin/claude").exists() || File(prefix, ".claude-installed").exists())
        ) {
            purgeClaudeFiles(termux)
        }
    }

    private fun cleanBrokenHermesTools(home: File) {
        runCatching {
            val toolsDir = File(home, "tools")
            val factsFile = File(toolsDir, "facts.json")
            if (factsFile.isFile) {
                val content = factsFile.readText()
                if (content.contains("python-") || content.contains("linux-arm64")) {
                    factsFile.writeText("{\"packages\":{},\"schema\":1}")
                }
            }
            toolsDir.listFiles()?.forEach { file ->
                if (file.isDirectory && file.name.startsWith("python-")) {
                    file.deleteRecursively()
                }
            }
        }
    }

    /**
     * Bind args mounting every other project's workspace at its own
     * /workspace/<slug> guest path, so an agent working in one project can
     * reach the files of all the others (v21 full-environment access). The
     * host directory is resolved exactly like WorkspaceCheckpoints /
     * the bridges resolve it (workspaces/<id>, with the project's rootPath
     * confined inside it). Best-effort: any failure yields no extra binds
     * and never breaks a launch.
     */
    private fun siblingProjectBindArgs(
        currentWorkspace: File,
        currentGuestPath: String,
        rootfs: File,
    ): List<String> = runCatching {
        val slugPath = Regex("^/workspace/[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$")
        val workspacesDir = File(context.filesDir, "workspaces")
        val currentCanonical = currentWorkspace.canonicalFile
        AppPreferences(context).loadProjects().flatMap { project ->
            val guestPath = "/workspace/${project.slug}"
            if (guestPath == currentGuestPath || !slugPath.matches(guestPath)) {
                return@flatMap emptyList()
            }
            val base = File(workspacesDir, project.id).canonicalFile
            val host = if (project.rootPath.isBlank()) base
                else File(base, project.rootPath).canonicalFile
            if (!host.toPath().startsWith(base.toPath()) || host == currentCanonical) {
                return@flatMap emptyList()
            }
            host.mkdirs()
            File(rootfs, guestPath.removePrefix("/")).mkdirs()
            listOf("-b", "${host.absolutePath}:$guestPath")
        }
    }.getOrDefault(emptyList())

    /**
     * Bind args mounting the cross-agent transcripts directory
     * (filesDir/workspaces/.agent-chats) at /workspace/.agent-chats, so
     * every agent session can read sister agents' chat transcripts and
     * append to the shared team-notes.md. Best-effort, like the sibling
     * project binds.
     */
    private fun agentChatsBindArgs(rootfs: File): List<String> = runCatching {
        val host = File(context.filesDir, "workspaces/.agent-chats").apply { mkdirs() }
        File(rootfs, "workspace/.agent-chats").mkdirs()
        listOf("-b", "${host.absolutePath}:${AgentTranscripts.GUEST_ROOT}")
    }.getOrDefault(emptyList())

    fun ensureSettingsAndHooks() {
        // PRoot (legacy) paths
        val hook = File(rootfs, "opt/pocket/permission-hook.sh")
        hook.parentFile?.mkdirs()
        val hookScript = """#!/bin/sh
cat > /dev/null
printf '%s\n' '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow"}}}'
"""
        hook.writeText(hookScript)
        Os.chmod(hook.absolutePath, 0b111101101)

        // Phone control (Shizuku): harness-host script and token file
        seedPhoneControlFiles()

        val settingsContent = JSONObject()
            .put("disableAllHooks", false)
            .put(
                "permissions",
                JSONObject()
                    .put("allow", claudeWorkspaceToolRules())
                    .put("defaultMode", "acceptEdits"),
            )
            .put(
                "hooks",
                JSONObject().put(
                    "PermissionRequest",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("matcher", "Bash|Edit|Write|NotebookEdit")
                            .put(
                                "hooks",
                                org.json.JSONArray().put(
                                    JSONObject().put("type", "command").put("command", "/opt/pocket/permission-hook.sh"),
                                ),
                            ),
                    ),
                ),
            )
            .toString()

        val settingsPaths = listOf(
            File(rootfs, "root/.claude/pocket-settings.json"),
            File(rootfs, "root/.claude/settings.json"),
            File(rootfs, "etc/claude/settings.json"),
        )
        for (target in settingsPaths) {
            target.parentFile?.mkdirs()
            target.writeText(settingsContent)
        }

        // Termux paths (actual runtime environment)
        val termux = TermuxEnvironment(context)
        if (termux.isInstalled()) {
            val termuxPrefix = termux.prefix
            val termuxHook = File(termuxPrefix, "opt/pocket/permission-hook.sh")
            termuxHook.parentFile?.mkdirs()
            termuxHook.writeText(hookScript)
            Os.chmod(termuxHook.absolutePath, 0b111101101)

            val termuxSettingsPaths = listOf(
                File(termuxPrefix, "home/.claude/settings.json"),
            )
            // Hook command points to the Termux absolute path
            val termuxSettingsContent = JSONObject()
                .put("disableAllHooks", false)
                .put(
                    "permissions",
                    JSONObject()
                        .put("allow", claudeWorkspaceToolRules())
                        .put("defaultMode", "acceptEdits"),
                )
                .put(
                    "hooks",
                    JSONObject().put(
                        "PermissionRequest",
                        org.json.JSONArray().put(
                            JSONObject()
                                .put("matcher", "Bash|Edit|Write|NotebookEdit")
                                .put(
                                    "hooks",
                                    org.json.JSONArray().put(
                                        JSONObject().put("type", "command").put("command", termuxHook.absolutePath),
                                    ),
                                ),
                        ),
                    ),
                )
                .toString()

            for (target in termuxSettingsPaths) {
                target.parentFile?.mkdirs()
                target.writeText(termuxSettingsContent)
            }
        }

        ensureWorkspaceTrust("/workspace")
    }

    /**
     * Seeds phone control (Shizuku) helper files into the guest:
     * - /usr/local/bin/harness-host: shell script that reads the token file and
     *   sends commands to the app's localhost bridge at 127.0.0.1:37123
     * - /opt/pocket/harness-host.token: the auth token (same as app's stored token)
     *
     * Called from ensureSettingsAndHooks so it's always in place when the runtime is ready.
     */
    private fun seedPhoneControlFiles() {
        // harness-host script on guest PATH
        val binDir = File(rootfs, "usr/local/bin").apply { mkdirs() }
        val harnessHostScript = File(binDir, "harness-host")
        harnessHostScript.writeText(
            """#!/bin/bash
# harness-host — run a command on the Android host outside the Ubuntu guest
# Uses the Phone Control bridge at 127.0.0.1:37123 (requires Shizuku permission).
# If phone control is not enabled or the bridge is not reachable, fails harmlessly.

set -euo pipefail

TOKEN_FILE="/opt/pocket/harness-host.token"
BRIDGE_HOST="127.0.0.1"
BRIDGE_PORT=37123

# Check token file exists
if [[ ! -f "${'$'}TOKEN_FILE" ]]; then
    echo "Phone control not configured: token file missing at ${'$'}TOKEN_FILE" >&2
    exit 1
fi

TOKEN=$(cat "${'$'}TOKEN_FILE" 2>/dev/null | tr -d '[:space:]')
if [[ -z "${'$'}TOKEN" ]]; then
    echo "Phone control not configured: empty token" >&2
    exit 1
fi

# Check we have a command
if [[ $# -eq 0 ]]; then
    echo "Usage: harness-host <command>" >&2
    exit 1
fi

# Join all args as the command (preserving quoting for shell -c)
CMD="$*"

# Use bash /dev/tcp to connect to the bridge
# Protocol: line 1 = token, line 2 = command, then read response until EOF
exec 3<> /dev/tcp/${'$'}BRIDGE_HOST/${'$'}BRIDGE_PORT 2>/dev/null || {
    echo "Phone control unavailable: cannot connect to bridge at ${'$'}BRIDGE_HOST:${'$'}BRIDGE_PORT" >&2
    echo "Make sure Phone Control (Shizuku) is enabled in Hermes Agent Settings." >&2
    exit 1
}

# Send auth token
echo "${'$'}TOKEN" >&3

# Send command
echo "${'$'}CMD" >&3

# Close write side, read response
exec 3>&-
cat <&3
exec 3<&-
""",
        )
        Os.chmod(harnessHostScript.absolutePath, 0b111101101)

        // Token file: always (re)write the app's current token so the
        // guest copy can never drift from the value the bridge checks.
        val pocketDir = File(rootfs, "opt/pocket").apply { mkdirs() }
        val tokenFile = File(pocketDir, "harness-host.token")
        tokenFile.writeText(AppPreferences(context).ensurePhoneControlToken())
        Os.chmod(tokenFile.absolutePath, 0b110100100) // 0644
    }

    /**
     * Seeds the MCP servers configuration (from app preferences) as
     * `.mcp.json` at the workspace root, for Claude Code and any agent that
     * reads the standard MCP config file format. [workspaceDir] is the
     * project's HOST workspace directory — the one the bridges bind-mount
     * into the guest at /workspace/<slug>; writing under the rootfs's own
     * /workspace instead would be shadowed by that bind and never seen.
     */
    fun seedMcpServersConfig(workspaceDir: File, mcpServersPrefs: McpServersPrefs) {
        val servers = mcpServersPrefs.getEnabled().filter { it.isValid() }
        val mcpFile = File(workspaceDir, ".mcp.json")
        if (servers.isEmpty()) {
            // Nothing enabled: if the user deleted every server, remove a
            // previously seeded file so the deleted servers stop loading;
            // if they merely disabled everything, write an empty config.
            if (mcpServersPrefs.getAll().isEmpty()) {
                if (mcpFile.isFile) mcpFile.delete()
            } else {
                workspaceDir.mkdirs()
                mcpFile.writeText(JSONObject().put("mcpServers", JSONObject()).toString(2))
            }
            return
        }
        workspaceDir.mkdirs()
        val obj = JSONObject().put("mcpServers", JSONObject())
        servers.forEach { server ->
            val serverObj = JSONObject()
            if (server.isStdio) {
                server.command?.let { serverObj.put("command", it) }
                if (server.args.isNotEmpty()) serverObj.put("args", server.args)
                if (server.env.isNotEmpty()) serverObj.put("env", JSONObject(server.env))
            } else if (server.isRemote) {
                server.url?.let { serverObj.put("url", it) }
                if (server.headers.isNotEmpty()) serverObj.put("headers", JSONObject(server.headers))
                server.auth?.let { serverObj.put("auth", it) }
                server.oauth?.let { serverObj.put("oauth", JSONObject(it)) }
            }
            if (!server.enabled) serverObj.put("enabled", false)
            server.tools?.let { serverObj.put("tools", JSONObject(it)) }
            server.connectTimeout?.let { serverObj.put("connect_timeout", it) }
            obj.getJSONObject("mcpServers").put(server.name, serverObj)
        }
        mcpFile.writeText(obj.toString(2))
    }

    private fun ensureWorkspaceTrust(workspacePath: String) {
        val stateFile = File(rootfs, "root/.claude.json")
        val state = runCatching { JSONObject(stateFile.readText()) }.getOrElse { JSONObject() }
        // Older alpha builds incorrectly wrote settings into Claude's state file.
        // Keep Claude's generated state, but remove only those stale settings keys.
        listOf("disableAllHooks", "permissions", "hooks", "allowedTools", "autoApprove")
            .forEach(state::remove)
        val projects = state.optJSONObject("projects") ?: JSONObject()
        val workspace = projects.optJSONObject(workspacePath) ?: JSONObject()
        workspace.put("hasTrustDialogAccepted", true)
        projects.put(workspacePath, workspace)
        state.put("projects", projects)
        stateFile.writeText(state.toString())
    }

    private fun claudeWorkspaceToolRules() = org.json.JSONArray().apply {
        put("Bash")
        put("Edit")
        put("Write")
        put("NotebookEdit")
        put("Read")
        put("Glob")
        put("Grep")
    }

    private fun writeResolver() {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val dns = manager.getLinkProperties(manager.activeNetwork)?.dnsServers.orEmpty()
        val servers = dns.mapNotNull { it.hostAddress }.ifEmpty { listOf("8.8.8.8", "1.1.1.1") }
        File(rootfs, "etc/resolv.conf").writeText(servers.joinToString("\n") { "nameserver $it" } + "\n")
    }

    private fun extractRootfs(archive: File, destination: File) {
        val deferredLinks = mutableListOf<Pair<File, File>>()
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            var entry: TarArchiveEntry? = tar.nextEntry
            while (entry != null) {
                val cleanName = entry.name.removePrefix("./")
                val target = safeChild(destination, cleanName)
                when {
                    entry.isDirectory -> target.mkdirs()
                    entry.isSymbolicLink -> {
                        target.parentFile?.mkdirs()
                        if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                        Os.symlink(entry.linkName, target.absolutePath)
                    }
                    entry.isLink -> {
                        target.parentFile?.mkdirs()
                        val linkTarget = safeChild(destination, entry.linkName.removePrefix("./"))
                        if (linkTarget.exists()) {
                            linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
                            runCatching { Os.chmod(target.absolutePath, entry.mode and 0b111111111) }
                        } else {
                            deferredLinks += target to linkTarget
                        }
                    }
                    entry.isFile -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output -> tar.copyTo(output) }
                        runCatching { Os.chmod(target.absolutePath, entry.mode and 0b111111111) }
                    }
                }
                entry = tar.nextEntry
            }
        }
        deferredLinks.forEach { (target, linkTarget) ->
            require(linkTarget.isFile) { "Archive hard-link target is missing" }
            target.parentFile?.mkdirs()
            linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
            runCatching { Os.chmod(target.absolutePath, android.system.Os.stat(linkTarget.absolutePath).st_mode) }
        }
    }

    private fun extractZstdTar(archive: File, destination: File) {
        val deferredLinks = mutableListOf<Pair<File, File>>()
        TarArchiveInputStream(
            ZstdCompressorInputStream(BufferedInputStream(archive.inputStream())),
        ).use { tar ->
            var entry: TarArchiveEntry? = tar.nextEntry
            while (entry != null) {
                val cleanName = entry.name.removePrefix("./")
                if (cleanName.isNotBlank()) {
                    val target = safeChild(destination, cleanName)
                    when {
                        entry.isDirectory -> target.mkdirs()
                        entry.isSymbolicLink -> {
                            target.parentFile?.mkdirs()
                            if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                            Os.symlink(entry.linkName, target.absolutePath)
                        }
                        entry.isLink -> {
                            target.parentFile?.mkdirs()
                            val linkTarget = safeChild(destination, entry.linkName.removePrefix("./"))
                            if (linkTarget.exists()) {
                                linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
                                runCatching { Os.chmod(target.absolutePath, entry.mode and 0b111111111) }
                            } else {
                                deferredLinks += target to linkTarget
                            }
                        }
                        entry.isFile -> {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { output -> tar.copyTo(output) }
                            runCatching { Os.chmod(target.absolutePath, entry.mode and 0b111111111) }
                        }
                    }
                }
                entry = tar.nextEntry
            }
        }
        deferredLinks.forEach { (target, linkTarget) ->
            require(linkTarget.isFile) { "Archive hard-link target is missing" }
            target.parentFile?.mkdirs()
            linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
            runCatching { Os.chmod(target.absolutePath, android.system.Os.stat(linkTarget.absolutePath).st_mode) }
        }
    }

    private fun extractNodeArchive(archive: File, destination: File) {
        val deferredLinks = mutableListOf<Pair<File, File>>()
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            var entry: TarArchiveEntry? = tar.nextEntry
            while (entry != null) {
                val relative = entry.name.removePrefix("./").substringAfter('/', "")
                if (relative.isNotBlank()) {
                    val target = safeChild(destination, relative)
                    when {
                        entry.isDirectory -> target.mkdirs()
                        entry.isSymbolicLink -> {
                            target.parentFile?.mkdirs()
                            if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                            Os.symlink(entry.linkName, target.absolutePath)
                        }
                        entry.isLink -> {
                            val relativeLink = entry.linkName.removePrefix("./").substringAfter('/', "")
                            val linkTarget = safeChild(destination, relativeLink)
                            target.parentFile?.mkdirs()
                            if (linkTarget.exists()) {
                                linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
                                runCatching { Os.chmod(target.absolutePath, entry.mode and 0b111111111) }
                            } else {
                                deferredLinks += target to linkTarget
                            }
                        }
                        entry.isFile -> {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { output -> tar.copyTo(output) }
                            runCatching { Os.chmod(target.absolutePath, entry.mode and 0b111111111) }
                        }
                    }
                }
                entry = tar.nextEntry
            }
        }
        deferredLinks.forEach { (target, linkTarget) ->
            require(linkTarget.isFile) { "Node.js archive hard-link target is missing" }
            target.parentFile?.mkdirs()
            linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
            runCatching { Os.chmod(target.absolutePath, android.system.Os.stat(linkTarget.absolutePath).st_mode) }
        }
    }

    private fun safeChild(root: File, relative: String): File {
        require(relative.isNotBlank() && !relative.startsWith('/')) { "Unsafe archive path" }
        val file = File(root, relative)
        val rootPath = root.canonicalFile.toPath()
        val parentPath = (file.parentFile ?: root).canonicalFile.toPath()
        require(parentPath.startsWith(rootPath)) { "Archive path escapes runtime" }
        return file
    }

    private suspend fun downloadVerified(
        url: String,
        destination: File,
        expectedChecksum: String,
        algorithm: String = "SHA-256",
        onBytes: suspend (downloaded: Long, total: Long) -> Unit,
    ) {
        destination.parentFile?.mkdirs()
        if (destination.isFile && digest(destination, algorithm).equals(expectedChecksum, ignoreCase = true)) {
            onBytes(destination.length(), destination.length())
            return
        }
        val temporary = File(destination.parentFile, "${destination.name}.part")
        var existing = temporary.takeIf(File::isFile)?.length() ?: 0L

        var currentUrl = url
        var connection: HttpURLConnection? = null
        var redirectCount = 0
        while (redirectCount < 6) {
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Hermes-Agent-App")
            if (existing > 0L) conn.setRequestProperty("Range", "bytes=$existing-")
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) break
                currentUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                    location
                } else {
                    URL(URL(currentUrl), location).toString()
                }
                redirectCount++
                continue
            }
            if (existing > 0L && code !in 200..299 && code != HttpURLConnection.HTTP_PARTIAL) {
                conn.disconnect()
                temporary.delete()
                existing = 0L
                currentUrl = url
                redirectCount = 0
                continue
            }
            connection = conn
            break
        }
        val activeConn = checkNotNull(connection) { "Could not connect to $url" }
        try {
            check(activeConn.responseCode in 200..299) {
                temporary.delete()
                str(R.string.rt_install_download_failed_http, activeConn.responseCode)
            }
            val resumed = activeConn.responseCode == HttpURLConnection.HTTP_PARTIAL && existing > 0L
            if (!resumed) {
                temporary.delete()
                existing = 0L
            }
            val total = activeConn.contentLengthLong.takeIf { it >= 0L }?.plus(existing) ?: -1L
            activeConn.inputStream.use { input ->
                FileOutputStream(temporary, resumed).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var downloaded = existing
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onBytes(downloaded, total)
                    }
                }
            }
        } finally {
            activeConn.disconnect()
        }
        val actual = digest(temporary, algorithm)
        if (!actual.equals(expectedChecksum, ignoreCase = true)) {
            temporary.delete()
            error("Downloaded file checksum did not match")
        }
        destination.delete()
        check(temporary.renameTo(destination)) { "Could not finish download" }
    }

    private fun fetchText(url: String): String {
        var currentUrl = url
        var connection: HttpURLConnection? = null
        var redirectCount = 0
        while (redirectCount < 6) {
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "Hermes-Agent-App")
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) break
                currentUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                    location
                } else {
                    URL(URL(currentUrl), location).toString()
                }
                redirectCount++
                continue
            }
            connection = conn
            break
        }
        val activeConn = checkNotNull(connection) { "Could not connect to $url" }
        return try {
            check(activeConn.responseCode in 200..299) { "Request failed with HTTP ${activeConn.responseCode}" }
            activeConn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            activeConn.disconnect()
        }
    }

    private fun digest(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun File.readTextOrNull(): String? = runCatching { readText().trim() }.getOrNull()

    companion object {
        const val AGY_GUEST_PATH = "/root/.local/bin/agy"
        const val GITHUB_CLI_GUEST_PATH = "/root/.local/bin/gh"
        const val HERMES_GUEST_PATH = "/root/.pocket-hermes/venv/bin/hermes"
        private const val HERMES_PREFIX_GUEST_PATH = "/root/.pocket-hermes"
        private const val UV_GUEST_PATH = "/root/.local/bin/uv"
        /** Pinned uv release; version, URL and SHA-256 are Hermes's own installer pins (`pm/lock.json`). */
        private const val UV_VERSION = "0.13.9"
        private const val UV_RELEASE_URL = "https://github.com/astral-sh/uv/releases/download/0.13.9/uv-aarch64-unknown-linux-gnu.tar.gz"
        private const val UV_RELEASE_SHA256 = "0797542018e541a9288855df555c8077eed06f7a47d206362030480e8ecc9f3d"
        /**
         * Pinned Hermes release for the first install from PyPI (spike-verified).
         * Later upgrades are unlocked: they install the newest PyPI release the
         * user approves, and the version marker then records that release.
         */
        // The bundle is built from the user's own GitHub repo
        // (radhesolanki319-pixel/Hermes-agent- main) — the version label is
        // the build date, matching the bundle file name and release tag.
        // v55: rebuilt from the rewritten main (custom mods + MAX POWER).
        const val HERMES_VERSION = "2026.11.22"

        /** Claude Code version inside the v77 APK bundle (agents-bundle-v1). */
        const val BUNDLED_CLAUDE_VERSION = "2.1.330"

        /**
         * v79 (user order, 2026-11-21): Claude Code stays DISABLED until
         * Hermes is proven working on his device — no auto-install, no
         * manual install, no 380 MB fallback download. Its bundled route
         * also still carries the broken grun script (its body sources a
         * com.termux path that does not exist inside this app); that fix
         * lands with the build that re-enables Claude.
         */
        const val CLAUDE_DISABLED = false

        /**
         * Pinned glibc CPython (python-build-standalone) for the Hermes venv.
         * v77: 3.14.10, NOT 3.13 — Hermes 2026.11.22 gates every core
         * dependency to python_version >= '3.14', so a 3.13 interpreter
         * resolves ZERO dependencies and Hermes can never start (that is
         * why the v75 network install failed on-device). SHA-256 verified
         * against the release's official SHA256SUMS.
         */
        const val GLIBC_PYTHON_URL =
            "https://github.com/astral-sh/python-build-standalone/releases/download/20261110/cpython-3.14.10%2B20261110-aarch64-unknown-linux-gnu-install_only.tar.gz"
        const val GLIBC_PYTHON_SHA256 =
            "e59f83159984a55e719bb3805a0b2b5b281f645769222218084b78b92eedc07f"

        /**
         * The aiohttp pin the pinned Hermes wheel's own metadata
         * declares for its gateway/messaging extras. The wheel's base
         * install (and therefore the app bundle) does not include it,
         * but the gateway package imports it unconditionally — without
         * it `hermes gateway run` starts with no platform listener and
         * the v32 warm transport can never answer. Installed alongside
         * hermes-agent on every route; see [ensureHermesWarmTransport]
         * for the repair of pre-v32 venvs.
         */
        const val HERMES_AIOHTTP_SPEC = "aiohttp==3.14.5"
        /** Guest path of an optional Hermes source checkout; when present it is installed instead of the PyPI release. */
        private const val HERMES_SOURCE_GUEST_PATH = "/root/hermes-agent-src"
        private const val AGY_VERSION = "1.1.27"
        private const val AGY_RELEASE_URL = "https://storage.googleapis.com/antigravity-public/antigravity-cli/1.1.27-5211191891591168/linux-arm/cli_linux_arm64.tar.gz"
        private const val AGY_RELEASE_SHA512 = "ed45f6930785aa4b42f14e07ace1c9d91a94fb76e760f54acbd7d3d3951e1f957fd456a0dae2a3124dd9a3b689bf7afb7c9303a3e4ba95037fc10063424d9bf9"
        private const val GITHUB_CLI_VERSION = "2.100.0"
        private const val GITHUB_CLI_RELEASE_URL = "https://github.com/cli/cli/releases/download/v2.105.0/gh_2.105.0_linux_arm64.tar.gz"
        private const val GITHUB_CLI_RELEASE_SHA256 = "b1278c75107423d818f1937fd92cafc9e5e9b606631568f68d7fb816f5885a02"
        private const val LEGACY_README = "# Pocket Dev project\n\nThis project is managed locally on Android.\n"
        private const val LEGACY_INDEX = "<!doctype html><title>Pocket Dev</title><h1>Hello from Android</h1>\n"
        private const val ROOTFS_VERSION = "ubuntu-26.04.1-arm64-gnu2"
        private const val ROOTFS_FILE = "ubuntu-base-26.04.1-base-arm64.tar.gz"
        private const val ROOTFS_URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/26.04/release/$ROOTFS_FILE"
        private const val ROOTFS_SHA256 = "5a1906794ced63a71a8119c3f211ef5f0bbe0a243001b4bbd41fdf80c5b219fd"
        private const val NODE_VERSION = "v26.13.1"
        private const val LANGUAGE_TOOLS_VERSION = "node-v24.19.0-python3-v1"
        private const val CORE_TOOLS_VERSION = "core-bundle-2026.11.4"
        private const val LEGACY_CORE_TOOLS_VERSION = "core-bundle-2026.09.5"
        private const val SYSTEM_UPGRADE_VERSION = "ubuntu-maintenance-v1"
        private const val ANDROID_TOOLS_VERSION = "sdk36-build-tools35-gradle8.14.3-maven-2026.09"
        private const val ANDROID_ASSET_BASE = "https://appdevforall.org/dev-assets/debug"
        private const val ANDROID_SDK_URL = "$ANDROID_ASSET_BASE/android-sdk-arm64-v8a.zip"
        private const val ANDROID_SDK_SHA256 = "bfe5bc940a7ede14735817a40962256666ce4152b9f3135f34a4ab9bccb87c3f"
        private const val ANDROID_GRADLE_URL = "$ANDROID_ASSET_BASE/gradle-8.14.3-bin.zip"
        private const val ANDROID_GRADLE_SHA256 = "8e228b640319a7c739c0a93d002facdeb08e8f3ba394d57d74ee355a5e93072c"
        private const val ANDROID_MAVEN_URL = "$ANDROID_ASSET_BASE/localMvnRepository.zip"
        private const val ANDROID_MAVEN_SHA256 = "3ba89892b43377d60743568d1b9004f172c2eab75dac059064f82dec497819f9"
        private const val ANDROID_AAPT2_PROPERTY = "android.aapt2FromMavenOverride"
        private const val GRADLE_JVMARGS_PROPERTY = "org.gradle.jvmargs"
        private const val GRADLE_WORKERS_PROPERTY = "org.gradle.workers.max"
        private const val GRADLE_PARALLEL_PROPERTY = "org.gradle.parallel"
        private const val KOTLIN_EXECUTION_PROPERTY = "kotlin.compiler.execution.strategy"
        private val MANAGED_ANDROID_GRADLE_PROPERTIES = setOf(
            ANDROID_AAPT2_PROPERTY,
            GRADLE_JVMARGS_PROPERTY,
            GRADLE_WORKERS_PROPERTY,
            GRADLE_PARALLEL_PROPERTY,
            KOTLIN_EXECUTION_PROPERTY,
        )
        private const val ANDROID_AAPT2_GUEST_PATH = "/root/android-sdk/build-tools/35.0.0/aapt2"
        private const val ANDROID_AAPT2_HOST_PATH = "root/android-sdk/build-tools/35.0.0/aapt2"
        private val CLAUDE_VERSION_PATTERN = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
        private val CORE_BUNDLE = RuntimeBundle(
            label = "Core",
            fileName = "pocketdev-core-arm64-2026.11.4.tar.zst",
            sha256 = "6c04f498fa152d8e74e0a515c111268cd6032dddb9b28cd66d16fcabd5281065",
            compressedBytes = 84_593_164L,
            // Ubuntu 26.04.1 core is published in the user's own repo (tag
            // core-bundle-2026.11.3) — the upstream runtime release still
            // carries the old 20.04 core under the shared base URL.
            // 2026.11.3 switches the guest coreutils from uutils to GNU
            // (coreutils-from-gnu): Ubuntu's uutils build carries a patch
            // that refuses to run when the resolved executable name differs
            // from the applet name, which under PRoot breaks /usr/bin/env,
            // cat, ls and the installer's agent verification.
            releaseBaseUrl = BuildConfig.CORE_BUNDLE_BASE_URL,
        )
        private const val CLAUDE_BUNDLED_VERSION = "2.1.316"
        private const val CLAUDE_GUEST_PATH = "/usr/local/bin/claude"
        private val CLAUDE_BUNDLE = RuntimeBundle(
            label = "Claude Code",
            fileName = "pocketdev-claude-arm64-2026.11.1.tar.zst",
            sha256 = "26316a6481983e5f42d362b4b6fdfa0441a1c27e9ddc74eb33fd4fcbba4fe3eb",
            compressedBytes = 116_997_995L,
        )
        private val PYTHON_BUNDLE = RuntimeBundle(
            label = "Python",
            fileName = "pocketdev-python-arm64-2026.09.2.tar.zst",
            sha256 = "6b3f56f7743fec142bc045db3ea561ee83fe89af87c2799165ef60351164ef85",
            compressedBytes = 55_419_626L,
        )
        private val ANDROID_BUNDLE = RuntimeBundle(
            label = "Android",
            fileName = "pocketdev-android-arm64-2026.09.1.tar.zst",
            sha256 = "01bea058ebcb17416d1eb08c0211b3782da3228eb3c8348eb3719f2d61dd3ec6",
            compressedBytes = 569_652_007L,
        )
        private val AGY_BUNDLE = RuntimeBundle(
            label = "Antigravity CLI",
            fileName = "pocketdev-agy-arm64-2026.09.1.tar.zst",
            sha256 = "a659ab9188956fc4721ca86fb21b5118e0e489f47a5e02ae6b4f2fb423659d78",
            compressedBytes = 41_870_025L,
        )
        private val HERMES_BUNDLE = RuntimeBundle(
            label = "Hermes Agent",
            fileName = "pocketdev-hermes-arm64-2026.11.22.tar.zst",
            sha256 = "c77df3a3709271e3bda956eed70cabae2b71330d2be3930d4214c328b6bf899a",
            compressedBytes = 67_298_088L,
            // Published as a release asset in the user's repo (tag
            // hermes-bundle-0.23.2), not under the upstream runtime release —
            // the online flavor downloads it from here, SHA-256 verified.
            releaseBaseUrl = BuildConfig.HERMES_BUNDLE_BASE_URL,
        )
        private const val MAX_TERMINAL_LINE = 500
        private const val MAX_COLLECTED_OUTPUT = 24_000
        private const val MAX_PROCESS_OUTPUT_BYTES = 256 * 1024
        private val ANSI_ESCAPE = Regex("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))")
    }
}
