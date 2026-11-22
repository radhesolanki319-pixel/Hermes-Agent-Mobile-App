# v86 Bug-Fix Report

All fixes applied in the working tree (branch `fix-v86-audit`). No commits, no versionCode changes, no CI changes.

---

## A1 — Claude chat hangs forever: stdin never closed (CRITICAL)
**File:** `ClaudeRuntimeBridge.kt`
**Change:** Added `runCatching { process.outputStream.close() }` immediately after process spawn in `startSession()`, matching the pattern already used in `hello()`. This closes stdin so Claude Code in `-p` mode doesn't wait for piped input EOF.

---

## A2 — Claude settings + permission hook written only to dead PRoot paths (CRITICAL)
**File:** `RuntimeInstaller.kt` (`ensureSettingsAndHooks`)
**Change:** When Termux is installed, now also:
- Writes the permission hook script to `<termux.prefix>/opt/pocket/permission-hook.sh` (executable)
- Writes settings JSON to `<termux.prefix>/home/.claude/settings.json` with the hook `command` pointing to that real absolute path
- Keeps existing PRoot writes untouched for backward compatibility

---

## A3 — Glibc clean-env guard doesn't cover Claude spawns
**File:** `RuntimeInstaller.kt` (`termuxProcess`)
**Changes:**
1. Extended `touchesGlibcTree` to also match when argv equals `<prefix>/bin/claude` or starts with `<prefix>/glibc/`
2. In the clean-env branch, now also sets `GLIBC_TUNABLES=glibc.pthread.rseq=0`

---

## A4 — Provider-error detector kills healthy Claude turns
**File:** `ClaudeRuntimeBridge.kt` (`startSession` output loop)
**Change:** Wrapped the `ProviderRuntimeErrorDetector.detect()` call in a guard that only runs when the line parses as JSON AND represents an error event:
- `type == "result"` with `is_error == true`, OR
- system event with `subtype == "api_retry"` or `subtype == "error"`
Plain text deltas and tool results no longer trigger the detector.

---

## A5 — Claude install verify retry ignores timeouts
**File:** `RuntimeInstaller.kt` (`finishClaudeTermuxInstall`)
**Change:** Wrapped the first `runTermuxCapture` in `runCatching`; retry once on exception (timeout, crash) as well as on non-matching output. If second attempt also fails, propagates the failure.

---

## B1 — App forces model `auto-fusion` on Hermes for CUSTOM provider (user rule violation)
**Files:** 
- `HermesLaunchConfig.kt` (`modelFor`)
- `HermesRuntimeBridge.kt` (`modelSectionLines`, `updateModelSection`, DIRECT CLI, one-shot CLI)
**Change:** Removed all five custom-route substitutions of `CUSTOM_DEF.defaultModel` ("auto-fusion"). Blank model now stays blank everywhere (no `default:` line in config.yaml, no `-m` flag on CLI). User's rule "model bilkul set mat karo — Hermes khud decide kare" now honored.

---

## B2 — Install/spawn fallback configs seed `auto-fusion`
**File:** `RuntimeInstaller.kt` (two locations: bundle install fallback ~line 903, termuxProcess Hermes spawn fallback ~line 2603)
**Change:** Both `HermesConfigFiles.freshConfig()` calls now pass `""` instead of `CUSTOM_DEF.defaultModel`. Route stays custom; forced model removed.

---

## B3 — FreeLLM endpoint + dummy OpenAI key injected into EVERY Hermes spawn
**Files:** 
- `RuntimeInstaller.kt` (`termuxProcess` isHermesSpawn block): Removed unconditional `OPENAI_BASE_URL` / `OPENAI_API_KEY` injection
- `HermesRuntimeBridge.kt` (`hermesLaunchEnvironment`): Now conditionally adds `OPENAI_BASE_URL` (route's baseUrl) and `OPENAI_API_KEY` (resolved secret) ONLY when route is custom
- `RuntimeInstaller.kt` (two fallback `.env` writes): Dropped `OPENAI_BASE_URL=` / `OPENAI_API_KEY=` lines, kept `CUSTOM_PROVIDER_API_KEY=no-key-required`

---

## B4 — Every chat turn wipes other providers' keys from Hermes's `.env`
**File:** `HermesRuntimeBridge.kt` (`HermesConfigFiles.mergedEnv`)
**Change:** Instead of removing ALL `ALL_KEY_ENVS`, now only removes the CURRENT route's `keyEnv` plus legacy `MH_HERMES_API_KEY` for custom routes. Other user-added keys (e.g., `XAI_API_KEY` from guest terminal) now survive across turns.

---

## B6 — Warm-server failure cooldown never engages (per-message latency)
**File:** `HermesWarmServer.kt` (`HermesWarmServerManager`)
**Changes:**
1. Added `lastFailedFingerprint` field, set alongside every `lastFailureAtMs` assignment (spawn failure + health timeout)
2. In `ensure()`, cooldown check now uses `lastFailedFingerprint` (not `serverFingerprint` which is nulled on failure)
3. Clears `lastFailedFingerprint` on successful healthy start
This prevents re-attempting a full spawn + 15s health wait on every message after a failure.

---

## B7 — Idle-stop can kill the warm server mid-turn
**Files:**
- `HermesWarmServer.kt`: Added `inFlightTurns` counter with `beginTurn()`/`endTurn()`; idle job now reschedules instead of stopping when turns in flight
- `HermesRuntimeBridge.kt` (`runWarmTurn`): Calls `warmServer.beginTurn()` before and `warmServer.endTurn()` in `finally` around `streamTurn`

---

## B8 — Silent empty success on blank Hermes reply
**File:** `HermesRuntimeBridge.kt` (`runCliTurn` outcome handling)
**Change:** Exit 0 with blank `responseText` now throws a clear failure: `"Hermes ne koi jawab nahi diya (empty response)"` instead of silently completing the turn.

---

## C1 — Agents tab can show Hermes "Installed" when it's broken
**File:** `RuntimeInstaller.kt` (`isAgentInstalled` HERMES branch)
**Change:** Dropped the `|| File(..., "bin/python3.11").canExecute()` fallback. `hasBin` now requires the actual Hermes entry point (`termuxHermesBinary`).

---

## C2 — No recovery if a turn never emits a terminal event (chat wedges)
**File:** `MainViewModel.kt` (`stopTask`)
**Change:** After calling `stopActiveSession()`, schedules a 2-second delayed check. If state is still `isRunning` for the SAME session/turn (guarded by captured `activeSessionId` and `activeProjectId`), force-clears `isRunning` locally and clears follow-up queue. Cannot kill a new turn started after the stop.

---

## C3 — Native agent re-copies the whole workspace every no-change turn
**File:** `WorkspaceCheckpoints.kt`
**Changes:**
1. `createCheckpoint`: Now writes an empty `changes.json` (`[]`) baseline when creating the checkpoint, so read-only turns don't trigger re-copy
2. `snapshot`: Skips symlink files (aligned with `createCheckpoint`); for files > 10 MB, uses `length:lastModified` fingerprint instead of SHA-256 hashing

---

## C4 — Updater download fragility
**File:** `AppUpdater.kt` (`download`)
**Changes:**
1. Raised asset-download read timeout from 30s to 120s
2. On any exception, deletes the `.part` file before re-throwing
3. After download, if `sizeBytes` is known and `partial.length() != sizeBytes`, fails with distinct "download incomplete" error and deletes `.part`

---

## C5 — Updater picks the first `.apk` asset in a release
**File:** `GitHubReleaseUpdater.kt` (`parseRelease`)
**Change:** Now prefers assets in priority order: (1) name contains "online" (case-insensitive), (2) name matches `Hermes_Agent_v<N>.apk`, (3) first `.apk` asset as fallback.

---

## C6 — Disk I/O inside a StateFlow update lambda
**File:** `MainViewModel.kt` (`selectAgent`)
**Change:** Hoisted `preferences.saveProvider()` and `preferences.loadProvider()` calls above the `_state.update { }` lambda. The lambda now only builds new state from precomputed values.

---

## C7 — extractDeb keeps stale symlinks on reinstall/upgrade
**File:** `TermuxEnvironment.kt` (deb extraction loop)
**Change:** Before `Os.symlink`, deletes any existing non-directory file/symlink at the target path. Prevents EEXIST silently leaving stale symlinks.

---

## Notes / Left Unchanged
- No remaining `CUSTOM_DEF.defaultModel` usages in the Hermes launch/config/transport path (verified by grep).
- `Models.kt` provider defaults and settings UI untouched per scope rules.
- Bundled-Claude dead code path left untouched.
- CI workflow and versionCode/versionName untouched.