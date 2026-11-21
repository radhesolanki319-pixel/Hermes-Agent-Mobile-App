package com.jarves.mh.termux

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The app's Linux environment: Termux packages (termux-main, aarch64) unpacked
 * into an app-private prefix and executed natively — no PRoot, no rootfs.
 *
 * Mechanics ported from AndroidHarness (Sanuu7/AndroidHarness, MIT): package
 * index + SHA-256 verified .deb downloads from ranked mirrors, extraction with
 * symlink/shebang/git-shell-path rewrites, a marker + binary-proof install
 * check, bash-function shims (etc/harness-shims.sh) plus /system/bin/linker64
 * so binaries in app-private storage stay executable despite W^X, and
 * termux-exec LD_PRELOAD interception for nested execs.
 *
 * This class is deliberately app-tier only: no Shizuku/shell tier, no storage
 * manager, no GitHub provisioning — the app layers those on top if needed.
 */
class TermuxEnvironment(private val context: Context) {

    /** The Termux prefix: bin/, lib/, etc/, home/, tmp/ live here. */
    val prefix: File = File(context.filesDir, "linux")
    private val marker = File(prefix, ".harness-installed")
    private val tempDir: File = File(context.cacheDir, "termux-debs")
    private val shimFile = File(prefix, "etc/harness-shims.sh")
    private val installMutex = Mutex()
    private var indexCache: Map<String, PkgMeta>? = null

    companion object {
        private const val TAG = "TermuxEnvironment"
        const val BASE_URL = "https://packages-cf.termux.dev/apt/termux-main"
        private const val MAX_DOWNLOAD_HOSTS = 4

        val corePackages = listOf("bash", "busybox", "ca-certificates", "termux-exec", "git")
        val fullPackages = corePackages + listOf("gh", "python", "python-pip", "nodejs", "npm", "uv")

        /** Package name → files proving it is actually usable (any-of). */
        private val BINARY_PROOF: Map<String, List<String>> = mapOf(
            "bash" to listOf("bin/bash"),
            "busybox" to listOf("bin/busybox"),
            "git" to listOf("bin/git"),
            "termux-exec" to listOf("lib/libtermux-exec-ld-preload.so", "lib/libtermux-exec.so"),
            "gh" to listOf("bin/gh"),
            "python" to listOf("bin/python3", "bin/python"),
            "python-pip" to listOf("bin/pip", "bin/pip3"),
            "nodejs" to listOf("bin/node"),
            "npm" to listOf("bin/npm"),
            "ca-certificates" to listOf("etc/tls/cert.pem", "etc/tls/cacert.pem"),
        )
    }

    // ------------------------------------------------------------------
    // Install state
    // ------------------------------------------------------------------

    fun installedPackages(): List<String> =
        runCatching { marker.readText().lines().map { it.trim() }.filter { it.isNotBlank() } }
            .getOrDefault(emptyList())

    fun installedContains(name: String): Boolean = installedPackages().any { it == name }

    /** Marker says so AND every core package's proof binary is on disk. */
    fun isInstalled(): Boolean = hasUsableCore()

    fun isFullyInstalled(): Boolean =
        fullPackages.all { installedContains(it) } && brokenInstalledPackages().isEmpty()

    private fun hasUsableCore(): Boolean = marker.exists() && corePackages.all { name ->
        installedContains(name) && BINARY_PROOF[name].orEmpty().any { File(prefix, it).isFile }
    }

    /** Installed-per-marker packages whose proof binaries have vanished. */
    private fun brokenInstalledPackages(): List<String> =
        installedPackages().filter { name ->
            val proof = BINARY_PROOF[name] ?: return@filter false
            proof.none { File(prefix, it).exists() }
        }

    /** What is missing or broken, human-readable (for setup/status UI). */
    fun checkMissing(): String {
        val installed = installedPackages().toSet()
        val missingPkgs = fullPackages.filter { it !in installed }
        val broken = brokenInstalledPackages()
        return when {
            missingPkgs.isEmpty() && broken.isEmpty() ->
                "All present: bash, git, gh, python, pip, node, npm."
            else -> buildString {
                if (missingPkgs.isNotEmpty()) {
                    append("Missing packages: ").append(missingPkgs.joinToString(", "))
                }
                if (broken.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append("Broken (marked installed but binary gone): ")
                        .append(broken.joinToString(", "))
                }
            }
        }
    }

    private fun markInstalled(names: List<String>) {
        val current = installedPackages().toMutableSet()
        current.addAll(names)
        marker.parentFile?.mkdirs()
        marker.writeText(current.joinToString("\n"))
    }

    suspend fun uninstall() = withContext(Dispatchers.IO) {
        installMutex.withLock {
            check(prefix.deleteRecursively() || !prefix.exists()) { "Could not remove the toolchain." }
            indexCache = null
        }
    }

    // ------------------------------------------------------------------
    // Package index
    // ------------------------------------------------------------------

    private fun abiName(): String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "aarch64"
        "x86_64" -> "x86_64"
        "armeabi-v7a" -> "arm"
        "x86" -> "i686"
        else -> "aarch64"
    }

    private fun indexUrl(base: String): String =
        "$base/dists/stable/main/binary-${abiName()}/Packages"

    private fun glibcIndexUrl(glibcBase: String): String =
        "$glibcBase/dists/glibc/stable/binary-${abiName()}/Packages"

    suspend fun getPackageIndex(forceRefresh: Boolean = false): Map<String, PkgMeta> =
        withContext(Dispatchers.IO) {
            if (!forceRefresh) indexCache?.let { return@withContext it }
            var failure: Exception? = null
            // The index carries every checksum, so it only ever comes from
            // Termux's own hosts.
            for (host in PackageMirrors.official) {
                try {
                    val parsed = PackageIndex.parse(fetchText(indexUrl(host))).toMutableMap()
                    // The termux-glibc repo carries glibc-runner & friends —
                    // the loader that lets glibc binaries (Claude Code) run
                    // on Termux. Best-effort merge: the main repo must keep
                    // working even when the glibc repo is unreachable, and
                    // main-repo entries always win name collisions.
                    runCatching {
                        val glibcHost = host.replace("termux-main", "termux-glibc")
                        PackageIndex.parse(fetchText(glibcIndexUrl(glibcHost)), repo = "glibc")
                            .forEach { (name, meta) -> parsed.putIfAbsent(name, meta) }
                    }
                    indexCache = parsed
                    return@withContext parsed
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = e
                }
            }
            throw failure ?: IllegalStateException("Could not reach the package repository")
        }

    /** Plain HttpURLConnection GET (this app has no OkHttp). Follows redirects. */
    private fun fetchText(url: String): String {
        val conn = openConnection(url)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code fetching $url")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "HermesAgent-TermuxEnv")
        return conn
    }

    // ------------------------------------------------------------------
    // Install
    // ------------------------------------------------------------------

    /**
     * Installs [wanted] plus their dependency closure. Sequential downloads in
     * dependency order; every file is SHA-256 verified against the index.
     * [onProgress] receives short human-readable status lines.
     */
    suspend fun install(
        wanted: List<String>,
        onProgress: suspend (String) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        installMutex.withLock {
            try {
                onProgress("Package list download ho raha hai…")
                val index = getPackageIndex()
                val unknown = wanted.filter { it !in index }
                if (unknown.isNotEmpty()) {
                    throw IllegalArgumentException(
                        "Unknown package(s): ${unknown.joinToString(", ")}. Not found in repository."
                    )
                }
                val already = installedPackages().toSet() - brokenInstalledPackages().toSet()
                val closure = resolve(index, wanted).filter { it.name !in already }
                if (closure.isEmpty()) {
                    markInstalled(wanted)
                    return@withLock Result.success(Unit)
                }
                tempDir.mkdirs()
                closure.forEachIndexed { i, pkg ->
                    // The index is network data: keep the download path inside
                    // the repo pool and the local file inside our temp dir.
                    val fileField = pkg.filename
                    val debBase = fileField.substringAfterLast('/')
                    if (fileField.startsWith('/') || fileField.contains('\\') ||
                        fileField.split('/').any { it.isEmpty() || it == "." || it == ".." } ||
                        !debBase.endsWith(".deb")
                    ) {
                        throw IllegalStateException("Unsafe package filename in the index: $fileField")
                    }
                    val debFile = File(tempDir, debBase)
                    onProgress("Download ${i + 1}/${closure.size}: ${pkg.name}")
                    downloadPoolFile(fileField, debFile, pkg.sha256, pkg.repo)
                    onProgress("Install ${i + 1}/${closure.size}: ${pkg.name}")
                    extractDeb(debFile)
                    debFile.delete()
                    // Record progress per package so a killed install resumes.
                    markInstalled(listOf(pkg.name))
                }
                File(prefix, "home").mkdirs()
                File(prefix, "tmp").mkdirs()
                markInstalled(wanted)
                ensureShims(force = true)
                onProgress("Ho gaya")
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Install failed", e)
                Result.failure(e)
            }
        }
    }

    /** Reinstalls whatever the marker claims but the disk lost. */
    suspend fun repairIfNeeded(): String? {
        val broken = brokenInstalledPackages()
        val missingCore = corePackages.filter { !installedContains(it) }
        val wanted = (broken + missingCore).distinct()
        if (wanted.isEmpty()) return null
        val result = install(if (wanted.any { it in fullPackages }) fullPackages else corePackages)
        return if (result.isSuccess) "Repaired: ${wanted.joinToString(", ")}"
        else "Repair failed: ${result.exceptionOrNull()?.message}"
    }

    /** Dependency closure of [wanted], dependencies first. */
    private fun resolve(index: Map<String, PkgMeta>, wanted: List<String>): List<PkgMeta> {
        val visited = LinkedHashSet<String>()
        fun visit(name: String) {
            if (name in visited) return
            val meta = index[name] ?: return
            meta.depends.forEach { visit(it) }
            visited += name
        }
        wanted.forEach { visit(it) }
        return visited.mapNotNull { index[it] }
    }

    /**
     * Fetches a pool file from the quickest hosts in turn. A mirror's file must
     * match the official checksum, so a stale or broken mirror only costs a
     * retry. Without a checksum, only Termux's own hosts are trusted.
     */
    private suspend fun downloadPoolFile(fileField: String, dest: File, expectedSha256: String, repo: String = "main") {
        // glibc packages only exist on Termux's own glibc hosts (the public
        // mirrors carry termux-main only).
        val ranked = if (repo == "glibc") {
            PackageMirrors.official.map { it.replace("termux-main", "termux-glibc") }
        } else {
            PackageMirrors.ranked()
        }
        val hosts = ranked
            .let { if (expectedSha256.isBlank() && repo != "glibc") it.filter { h -> h in PackageMirrors.official } else it }
            .ifEmpty { listOf(BASE_URL) }
            .take(MAX_DOWNLOAD_HOSTS)
        var failure: Exception? = null
        for (host in hosts) {
            try {
                downloadVerified("$host/$fileField", dest, expectedSha256)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
                dest.delete()
                Log.w(TAG, "Package download from $host failed, trying the next host", e)
            }
        }
        PackageMirrors.reset()
        throw failure ?: IllegalStateException("Could not download $fileField")
    }

    private fun downloadVerified(url: String, dest: File, expectedSha256: String) {
        val conn = openConnection(url)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code downloading $url")
            val digest = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (expectedSha256.isNotBlank() && !actual.equals(expectedSha256, ignoreCase = true)) {
                dest.delete()
                throw IllegalStateException("Checksum mismatch for ${dest.name}")
            }
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------
    // Extraction (with the three Termux rewrites)
    // ------------------------------------------------------------------

    /**
     * Unpacks one .deb into the prefix. Returns the relative paths written.
     * Adapted from AndroidHarness's extractDeb; shebangs are rewritten into
     * THIS prefix (app tier) instead of a deployed /data/local/tmp copy.
     */
    private fun extractDeb(deb: File): List<String> {
        val installedPaths = mutableListOf<String>()
        ArArchiveInputStream(deb.inputStream().buffered()).use { ar ->
            var entry = ar.nextEntry
            while (entry != null && !entry.name.startsWith("data.tar")) {
                entry = ar.nextEntry
            }
            if (entry == null) throw IllegalStateException("No data archive in ${deb.name}")
            val name = entry.name
            val input: java.io.InputStream = when {
                name.endsWith(".xz") || name.endsWith(".lzma") ->
                    CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.XZ, ar)
                name.endsWith(".gz") ->
                    CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.GZIP, ar)
                name.endsWith(".bz2") ->
                    CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.BZIP2, ar)
                name.endsWith(".zst") ->
                    CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.ZSTANDARD, ar)
                else -> ar // plain tar
            }
            TarArchiveInputStream(input).use { tar ->
                while (true) {
                    val e = tar.nextTarEntry ?: break
                    var rel = e.name.removePrefix("./").removePrefix("/")
                    val termuxPrefix = "data/data/com.termux/files/usr"
                    if (rel.startsWith(termuxPrefix)) {
                        rel = rel.removePrefix(termuxPrefix).removePrefix("/")
                    }
                    if (rel.isEmpty() || rel.startsWith("data/data")) continue
                    // Zip-slip guard: real packages never ship .. segments.
                    if (rel.split('/').any { it == ".." }) {
                        throw IllegalStateException("Refusing traversal path in ${deb.name}: ${e.name}")
                    }

                    val target = File(prefix, rel)
                    if (!e.isDirectory) installedPaths += rel
                    when {
                        e.isDirectory -> target.mkdirs()
                        e.isSymbolicLink -> {
                            target.parentFile?.mkdirs()
                            // Termux packages carry absolute symlinks into their
                            // own build prefix; rewrite them relative or they
                            // arrive dangling (bzcmp, bzless, …).
                            val linkName = TermuxLinkRewrite.relativeTarget(e.linkName, rel) ?: e.linkName
                            runCatching { Os.symlink(linkName, target.absolutePath) }
                        }
                        else -> {
                            target.parentFile?.mkdirs()
                            // Peek the first bytes of every regular file: scripts
                            // with a Termux-absolute shebang split three ways —
                            // Android-bridge wrappers (bin/pm, am, …) are dropped
                            // (they shadow /system binaries and die with 126);
                            // real tooling gets its shebang rewritten into this
                            // prefix; anything else is written verbatim.
                            var skipWrite = false
                            val peeked = ByteArray(256)
                            val n = runCatching { tar.read(peeked, 0, peeked.size) }.getOrDefault(-1)
                            val entryText = if (n > 0) String(peeked, 0, n, Charsets.UTF_8) else ""
                            val firstLine = entryText.lineSequence().firstOrNull()?.trim() ?: ""
                            val rewritten = TermuxShebangs.rewrittenFirstLine(firstLine, prefix.absolutePath)
                            val binEntry = rel.startsWith("bin/") && !rel.contains("/applets/")
                            when {
                                (binEntry && TermuxShebangs.isUnsupportedBridge(rel.substringAfterLast('/'))) ||
                                    TermuxShebangs.isWrapperScript(rel.substringAfterLast('/'), firstLine) ||
                                    (binEntry && firstLine.startsWith(TermuxShebangs.TERMUX_PREFIX_MARK) && rewritten == null) -> {
                                    tar.copyTo(object : java.io.OutputStream() {
                                        override fun write(b: Int) {}
                                    })
                                    skipWrite = true
                                }
                                rewritten != null && entryText.indexOf('\n') >= 0 -> {
                                    val nl = entryText.indexOf('\n')
                                    FileOutputStream(target).use { out ->
                                        out.write(rewritten.toByteArray(Charsets.UTF_8))
                                        out.write(peeked, nl, n - nl)
                                        tar.copyTo(out)
                                    }
                                    runCatching { Os.chmod(target.absolutePath, e.mode.toInt() and 0xFFF) }
                                    skipWrite = true
                                }
                                n > 0 -> {
                                    FileOutputStream(target).use { out ->
                                        out.write(peeked, 0, n)
                                        tar.copyTo(out)
                                    }
                                    runCatching { Os.chmod(target.absolutePath, e.mode.toInt() and 0xFFF) }
                                    // git's ELFs embed the dead Termux shell
                                    // path; neutralize it while the bytes are hot.
                                    if (TermuxShellPath.appliesTo(rel)) TermuxShellPath.patch(target)
                                    skipWrite = true
                                }
                            }
                            if (skipWrite) continue
                            FileOutputStream(target).use { out -> tar.copyTo(out) }
                            runCatching { Os.chmod(target.absolutePath, e.mode.toInt() and 0xFFF) }
                        }
                    }
                }
            }
        }
        return installedPaths
    }

    // ------------------------------------------------------------------
    // Process environment
    // ------------------------------------------------------------------

    fun processEnv(extra: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
        val p = prefix.absolutePath
        put("PATH", "$p/bin:$p/bin/applets:/system/bin:/system/xbin:/vendor/bin")
        // The Termux glibc (glibc-runner route) keeps its libs in glibc/lib;
        // glibc-linked binaries (Claude Code, the glibc CPython that runs
        // Hermes) need it on the loader path.
        put("LD_LIBRARY_PATH", if (File(prefix, "glibc/lib").isDirectory) "$p/lib:$p/glibc/lib" else "$p/lib")
        put("HOME", "$p/home")
        put("TMPDIR", "$p/tmp")
        put("PREFIX", p)
        put("npm_config_cache", File(context.cacheDir, "npm").absolutePath)
        put("PIP_CACHE_DIR", File(context.cacheDir, "pip").absolutePath)
        // termux-exec intercepts execve in children and re-roots paths into
        // this prefix. Recent packages split the interception library from the
        // API library; the -ld-preload one is the interceptor.
        val termuxExec = File(prefix, "lib/libtermux-exec-ld-preload.so")
            .takeIf { it.exists() } ?: File(prefix, "lib/libtermux-exec.so")
        if (termuxExec.exists()) {
            put("LD_PRELOAD", termuxExec.absolutePath)
            put("TERMUX__PREFIX", p)
            put("TERMUX_APP__DATA_DIR", context.applicationInfo.dataDir)
            put("TERMUX_APP__LEGACY_DATA_DIR", "/data/data/${context.packageName}")
        }
        put("TERM", "xterm-256color")
        put("LANG", "C.UTF-8")
        // Termux-built git looks for templates/exec helpers under its old
        // build prefix; point both at ours or HTTPS clones die.
        val templates = File(prefix, "share/git-core/templates")
        if (templates.isDirectory) put("GIT_TEMPLATE_DIR", templates.absolutePath)
        val gitExec = File(prefix, "libexec/git-core")
        if (gitExec.isDirectory) put("GIT_EXEC_PATH", gitExec.absolutePath)
        // A generated global config marks every repo safe (dubious-ownership
        // would otherwise block work in checkouts owned by another uid) and
        // the system scope is disabled outright: git's compiled-in system
        // config path points at the dead Termux prefix and exits 128.
        put("GIT_CONFIG_GLOBAL", gitGlobalConfig().absolutePath)
        put("GIT_CONFIG_NOSYSTEM", "1")
        putAll(tlsEnvVars())
        // OpenSSL tools read a config path under the old prefix; Node refuses
        // to start when it is unreadable. Point at ours when present.
        File(prefix, "etc/tls/openssl.cnf").takeIf { it.isFile }
            ?.let { put("OPENSSL_CONF", it.absolutePath) }
        // bash sources this for non-interactive shells: the shims make every
        // toolchain binary runnable despite W^X (see ensureShims).
        if (shimFile.exists()) put("BASH_ENV", shimFile.absolutePath)
        putAll(extra)
    }

    /** TLS trust vars pointing at the CA bundle inside the prefix. */
    fun tlsEnvVars(): Map<String, String> {
        val cert = File(prefix, "etc/tls/cert.pem").takeIf { it.isFile }
            ?: File(prefix, "etc/tls/cacert.pem").takeIf { it.isFile }
            ?: return emptyMap()
        return mapOf(
            "SSL_CERT_FILE" to cert.absolutePath,
            "GIT_SSL_CAINFO" to cert.absolutePath,
            "CURL_CA_BUNDLE" to cert.absolutePath,
            "REQUESTS_CA_BUNDLE" to cert.absolutePath,
            "NODE_EXTRA_CA_CERTS" to cert.absolutePath,
        )
    }

    /** The generated global gitconfig (safe.directory=*), written on demand. */
    private fun gitGlobalConfig(): File {
        val file = File(prefix, "etc/gitconfig")
        if (!file.isFile) {
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText("[safe]\n\tdirectory = *\n[init]\n\tdefaultBranch = main\n")
            }
        }
        return file
    }

    // ------------------------------------------------------------------
    // Shims + process launching (W^X survival)
    // ------------------------------------------------------------------

    fun linkerPath(): String? = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> null
        File("/system/bin/linker64").exists() -> "/system/bin/linker64"
        File("/apex/com.android.runtime/bin/linker64").exists() -> "/apex/com.android.runtime/bin/linker64"
        File("/system/bin/linker").exists() -> "/system/bin/linker"
        else -> null
    }

    fun bashExecutable(): File? = File(prefix, "bin/bash").takeIf { it.isFile }

    fun isElf(file: File): Boolean = runCatching {
        file.inputStream().use { ins ->
            val magic = ByteArray(4)
            ins.read(magic) == 4 &&
                magic[0] == 0x7f.toByte() && magic[1] == 'E'.code.toByte() &&
                magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte()
        }
    }.getOrDefault(false)

    private fun File.isElf(): Boolean = isElf(this)

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** (Re)generates the bash-function shim file when missing or stale. */
    fun ensureShims(force: Boolean = false) {
        runCatching {
            val linker = linkerPath()
            val header = ShellShims.header(linker)
            val binDir = File(prefix, "bin")
            if (!binDir.isDirectory) return
            if (force) shimFile.delete()
            if (!force && shimFile.isFile &&
                runCatching { shimFile.bufferedReader().use { it.readLine() } }.getOrNull() == header
            ) return
            val bash = File(binDir, "bash").absolutePath
            val nodeBin = File(binDir, "node").takeIf { it.exists() }?.absolutePath
            val pythonBin = (File(binDir, "python3").takeIf { it.exists() }
                ?: File(binDir, "python").takeIf { it.exists() })?.absolutePath
            val sb = StringBuilder()
            sb.append(header).append('\n')

            fun addShim(file: File) {
                val isElf = file.isElf()
                val shebang = if (isElf) null
                else runCatching { file.bufferedReader().use { it.readLine() } }.getOrNull()?.trim()
                ShellShims.line(file.name, file.absolutePath, isElf, shebang, linker, bash, nodeBin, pythonBin)
                    ?.let { sb.append(it) }
            }

            binDir.listFiles()?.forEach { if (it.isFile || it.canonicalPath != it.absolutePath) addShim(it) }
            File(prefix, "bin/applets").listFiles()?.forEach { addShim(it) }

            shimFile.parentFile?.mkdirs()
            shimFile.writeText(sb.toString())
        }
    }

    /**
     * bash 5.3 stopped sourcing $BASH_ENV for `bash -c`, so the prelude loads
     * the shims explicitly. Empty when there is nothing to load.
     */
    private fun shimPrelude(): String {
        ensureShims()
        val path = shimFile.takeIf { it.isFile }?.absolutePath ?: return ""
        return ". ${shellQuote(path)} 2>/dev/null; "
    }

    /** A ProcessBuilder for the Termux bash (linker-launched when needed). */
    fun shellProcessBuilder(command: String? = null): ProcessBuilder {
        val bash = bashExecutable()
        if (bash != null) {
            ensureShims()
            val argv = listOfNotNull(linkerPath(), bash.absolutePath) +
                if (command != null) listOf("-c", shimPrelude() + command) else emptyList()
            return ProcessBuilder(argv).apply { environment().putAll(processEnv()) }
        }
        return ProcessBuilder(if (command != null) listOf("sh", "-c", command) else listOf("sh"))
    }

    /**
     * Starts [command] with the best available shell: linker-launched bash,
     * then direct bash, then toybox sh. [cwd] must be a real directory.
     */
    fun startShell(command: String, cwd: File): Process {
        val envAvailable = bashExecutable() != null
        val script = if (envAvailable) shimPrelude() + command else command
        val setsid = when {
            File("/system/bin/setsid").exists() -> "/system/bin/setsid"
            File("/usr/bin/setsid").exists() -> "/usr/bin/setsid"
            else -> null
        }
        val shBin = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "sh"
        val linker = linkerPath()
        if (envAvailable && linker != null) {
            val p = runCatching {
                val bashPath = bashExecutable()!!.absolutePath
                val binPath = File(prefix, "bin").absolutePath
                val cmdList = if (setsid != null) {
                    listOf(
                        setsid, shBin, "-c",
                        "export PATH=\"$binPath:\$PATH\"; exec \"$linker\" \"$bashPath\" -c \"\$@\"",
                        "sh", script,
                    )
                } else {
                    listOf(linker, bashPath, "-c", script)
                }
                ProcessBuilder(cmdList).directory(cwd)
                    .apply { environment().putAll(processEnv()) }.start()
            }.getOrNull()
            if (p != null) return p
        }
        if (envAvailable) {
            val p = runCatching {
                val bashPath = bashExecutable()!!.absolutePath
                val binPath = File(prefix, "bin").absolutePath
                val cmdList = if (setsid != null) {
                    listOf(
                        setsid, shBin, "-c",
                        "export PATH=\"$binPath:\$PATH\"; exec \"$bashPath\" -c \"\$@\"",
                        "sh", script,
                    )
                } else {
                    listOf(bashPath, "-c", script)
                }
                ProcessBuilder(cmdList).directory(cwd)
                    .apply { environment().putAll(processEnv()) }.start()
            }.getOrNull()
            if (p != null) return p
        }
        val toyboxCmd = if (setsid != null) listOf(setsid, "sh", "-c", command)
        else listOf("sh", "-c", command)
        return ProcessBuilder(toyboxCmd).directory(cwd).start()
    }
}

/**
 * Bash-function shims: one function per toolchain binary, routing the call
 * through the dynamic linker on Android 10+ (app-private files are not
 * directly executable there), or directly on older Android. Ported from
 * AndroidHarness (MIT).
 */
internal object ShellShims {
    const val HEADER = "# auto-generated by Hermes Agent (v1): do not edit"

    fun header(linker: String?): String =
        if (linker == null) "$HEADER (direct exec)" else HEADER

    /**
     * One shim line. [entryPath] must be the bin entry itself, NOT its
     * canonical target: multi-call binaries (busybox, coreutils, git)
     * dispatch on argv[0]. Non-ELF entries are scripts run under bash, or
     * the node/python binary when their shebang asks for it.
     */
    fun line(
        name: String,
        entryPath: String,
        isElf: Boolean,
        shebang: String?,
        linker: String?,
        bashPath: String,
        nodeBin: String?,
        pythonBin: String?,
    ): String? {
        if (!name.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.+:-]*"))) return null
        fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
        val interp = when {
            shebang?.contains("node") == true -> nodeBin ?: bashPath
            shebang?.contains("python") == true -> pythonBin ?: bashPath
            else -> bashPath
        }
        val target = q(entryPath)
        val launch = if (linker != null) "command $linker" else "command"
        return if (isElf) {
            "$name() { $launch $target \"\$@\"; }\n"
        } else {
            "$name() { $launch ${q(interp)} $target \"\$@\"; }\n"
        }
    }
}
