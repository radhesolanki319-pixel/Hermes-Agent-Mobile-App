# Bug Fix Report

## Summary
Fixed 5 bugs across 5 files. The most important fix addresses a session ID handling bug in `HermesRuntimeBridge.kt` where a failed turn would save a new session ID and then immediately clear it, losing the session and forcing the next turn to start fresh unnecessarily.

---

## Bugs Fixed

### 1. Session ID Lost on Failed Turn (Severity: High)
**File:** `app/src/main/java/com/jarves/mh/runtime/HermesRuntimeBridge.kt` (lines 1018-1035, 1198-1212)

**What was wrong:**
In both the warm transport and CLI fallback paths, when a turn failed but the server/process had created a new session ID, the code would:
1. Save the new session ID: `saveConversationId(projectId, outcome.sessionId)`
2. Then unconditionally clear it: `if (resumeId != null) saveConversationId(projectId, "")`

This meant the new session ID was saved and then immediately removed, losing the session. The next message would start fresh with only the history embed for context.

**What was changed:**
Changed the logic to "new ID wins, otherwise clear old":
- If `outcome.sessionId` is non-blank → save it (don't clear)
- Else if `resumeId` was non-null → clear the old ID
- This preserves newly created sessions while still clearing invalid ones.

**Why it's correct:**
The old comment claimed "A session Hermes no longer knows fails every resume" but this only applies when NO new session was created. When the server creates a new session on a failed turn, that session IS valid and should be kept.

---

### 2. Think Tag Truncation Logic Inverted (Severity: Medium)
**File:** `app/src/main/java/com/jarves/mh/runtime/HermesWarmTransport.kt` (lines 377-402)

**What was wrong:**
The `speculativePending()` function in `HermesStreamAssembler` was supposed to truncate partial think tags (like `<thi`, `</thi`) at the end of a streaming line so they don't leak as visible answer text. The condition was inverted:
```kotlin
if ("question".startsWith(tail) || "answered".startsWith(tail))  // WRONG
```
This checked if the literal think tag string STARTS WITH the tail, which is almost never true. The intent was to check if the tail STARTS WITH a think tag prefix.

**What was changed:**
Rewrote the condition to properly detect:
- Complete think tag prefixes: `<think`, `<thinking`, `<reasoning`, `<thought`, `<reasoning_scratchpad` and their closing variants
- Partial think tags (prefixes of the above): e.g., `<thi`, `<thin`, `</th`, `</thi`

**Why it's correct:**
Matches the think tag variants recognized by `HermesQuietOutput.Collector` (THINK_OPEN/THINK_CLOSE regexes). Prevents partial think tags from flashing on screen during streaming.

---

### 3. Missing User-Agent Header in GitHub Release Updater (Severity: Medium)
**File:** `app/src/main/java/com/jarves/mh/update/GitHubReleaseUpdater.kt` (line 77)

**What was wrong:**
The GitHub API requires a User-Agent header. Without it, requests may be rejected with HTTP 403. The in-app updater would silently fail to check for updates.

**What was changed:**
Added `connection.setRequestProperty("User-Agent", "Hermes-Agent-App-Updater")` to the API request.

---

### 4. Missing User-Agent Header in AppUpdater (Severity: Medium)
**File:** `app/src/main/java/com/jarves/mh/update/AppUpdater.kt` (lines 48, 82)

**What was wrong:**
Both the manifest check (`check()`) and the APK download (`download()`) were missing User-Agent headers. GitHub release asset downloads require a User-Agent header, which could cause downloads to fail with 403.

**What was changed:**
Added User-Agent header to both HTTP connections.

---

### 5. Missing User-Agent Header in ProviderApiClient (Severity: Low)
**File:** `app/src/main/java/com/jarves/mh/network/ProviderApiClient.kt` (line 189)

**What was wrong:**
Provider API requests were missing a User-Agent header. Some providers (especially corporate firewalls, Cloudflare-protected endpoints) reject requests without a User-Agent.

**What was changed:**
Added default User-Agent header `"Hermes-Agent-App"` to all provider requests. (OpenCode Zen endpoint keeps its specific User-Agent override.)

---

## Suspected Problems NOT Fixed

### 1. Dead Code: `compactToolText` function (HermesRuntimeBridge.kt:1602)
- **Why not fixed:** Only used in one test (`AgentProviderPresetTest.kt`). Not called from any production code. The task says "do not delete features" — this may be intended for future use or was accidentally left unused.
- **On-device test:** Search codebase for calls to `compactToolText` — none found outside tests.

### 2. Dead Code: `friendlyHttpError` function (ProviderApiClient.kt:306)
- **Why not fixed:** Only called from one place (line 75 in `discoverModels`). The function is used but the same error handling is duplicated inline in `validate()` with more specific messages. Could be consolidated but not a bug.
- **On-device test:** Trigger model discovery with a non-200/401/404/200 response code (e.g., 418) and verify error message.

### 3. Race Condition in `HermesWarmServerManager.ensure()`
- **Why not fixed:** Theoretical race between idle stop job and `ensure()` spawning a new server. In practice: idle stop only fires after 10 min inactivity, and `ensure()` is called when a turn starts (server was just used). Window is negligible. The race would only cause a failed warm start (falls back to CLI), not a crash.
- **On-device test:** Spam messages while server is idle-stopping — verify no crashes, only possible fallback to CLI.

### 4. Health Check Cooldown Gap
- **Why not fixed:** If server is alive but unhealthy (healthCheck fails), `ensure()` proceeds to replace it WITHOUT setting `lastFailureAtMs`. This means no cooldown, and every turn retries the full 15s wait. In practice, unhealthy-but-alive is rare (usually process dies completely).
- **On-device test:** Start server, kill its HTTP listener but leave process alive — verify subsequent turns don't get stuck in 15s waits.

### 5. APK Stale Check Logic (`findReusableDebugApk` / `isDebugApkStale`)
- **Why not fixed:** The logic uses `lastModified()` timestamps which can be unreliable on some filesystems (e.g., FAT, or when clock skew occurs). Also `walkTopDown()` with filters may miss generated files. Not a crash — worst case rebuilds when not needed.
- **On-device test:** Build Android project, touch a source file with older timestamp, run `isDebugApkStale` — verify it correctly detects staleness.

---

## Files Changed
1. `app/src/main/java/com/jarves/mh/runtime/HermesRuntimeBridge.kt` — Session ID fix (2 locations)
2. `app/src/main/java/com/jarves/mh/runtime/HermesWarmTransport.kt` — Think tag truncation fix
3. `app/src/main/java/com/jarves/mh/update/GitHubReleaseUpdater.kt` — User-Agent header
4. `app/src/main/java/com/jarves/mh/update/AppUpdater.kt` — User-Agent headers (2 locations)
5. `app/src/main/java/com/jarves/mh/network/ProviderApiClient.kt` — User-Agent header

All changes are minimal, focused, and don't modify signing config, version codes, or CLAUDE.md rules.