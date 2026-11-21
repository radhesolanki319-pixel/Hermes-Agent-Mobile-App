# Brief — Termux Stage 1 FIX round (for Claude Code)

Your Stage 1 port was reviewed by Muse. The core port (PackageMirrors, TermuxUtils rewrites, index/download/extract logic) is good, but **TermuxEnvironment.kt as written will not compile in this app**: it calls helper classes that exist in AndroidHarness but NOT in this codebase, and it imports OkHttp, which this app does not have. Your report incorrectly described them as "already used by Hermes". Verified facts (grep, this repo):

- DO NOT EXIST anywhere in this app: `GitHubProvision`, `AndroidBuildToolchain`, `NetTls`, `ShellPolicy` (your import `com.jarves.mh.tools.ShellPolicy` is phantom), `LocaleCommand`, `SharedToolStore`, `StorageFiles`, `CodeGraphBundlePatches`.
- OkHttp is NOT a dependency (no squareup/okhttp in any gradle file). This app's HTTP stack is `java.net.HttpURLConnection` (see RuntimeInstaller.kt ~line 2342).
- Declared deps you MAY use: kotlinx-coroutines-android 1.9.0, commons-compress 1.27.1, Android SDK, Kotlin stdlib.

## Fix tasks

1. **Remove every phantom collaborator from TermuxEnvironment.kt:**
   - `AndroidBuildToolchain.*` — delete all calls (repairLaunchers, brokenPackages, status). In `processEnv`, drop the JAVA_HOME / LD_LIBRARY_PATH additions that came from it (our Stage 1 package sets contain no JDK).
   - `GitHubProvision.*` — delete the GitHub-materialize feature entirely (git must work without it).
   - `NetTls.*` — replace with the ca-certificates package's own bundle: TLS env vars point at `<prefix>/etc/tls/cert.pem` when that file exists (SSL_CERT_FILE, GIT_SSL_CAINFO); no custom staging.
   - `ShellPolicy` — delete; scratch/tmp = `<prefix>/tmp` (create on demand).
   - `LocaleCommand` — delete calls.
   - `SharedToolStore`, `StorageFiles`, `CodeGraphBundlePatches`, and the whole ShellTier / stageForShell / ensureShellDeploy machinery — delete. Stage 1 is APP-TIER ONLY.
2. **Replace OkHttp with HttpURLConnection** for index + .deb downloads, preserving: mirror fallback order, SHA-256 verification against the index, redirect following, and timeout behavior. Mirror RuntimeInstaller's download style.
3. **Wire the terminal hookup (the gap your report admitted):** find how the Terminal tab launches its shell process (start from MainViewModel.runProjectTerminalProcess and the terminal UI call sites). When `AppPreferences.termuxRuntimeEnabled` is true AND the Termux prefix is installed, launch the shell via TermuxEnvironment.startShell; otherwise the existing PRoot path runs, byte-for-byte unchanged behavior.
4. **Install entry point:** one small Settings row (or Terminal empty state — pick the smaller diff): a toggle bound to `termuxRuntimeEnabled` plus an "Install Termux packages" button that runs the FULL package set install with simple progress text and a success/failure line. No new screens.
5. **Mandatory self-check before you finish** (include its output in MIGRATION_REPORT.md, replacing the old report):
   - For every `import` in the termux package files: show the grep evidence that the symbol exists in this repo or is a declared dependency / Android SDK / stdlib.
   - Grep your new files for any remaining reference to the phantom names above — must be zero.
   - List every file you touched this round with a one-line purpose.

## Constraints (unchanged)

No commit/push. No versionCode/versionName, signing, provider/model/key logic changes. Flag OFF = zero behavior change. Keep diffs outside the termux package minimal.

When done, stop. Do not start Stage 2.
