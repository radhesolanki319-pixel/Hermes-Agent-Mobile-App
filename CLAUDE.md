# Mobile Harness (Hermes Agent) - Claude Code Project Guide

## 1. Project Overview & Context
- **Repository**: `https://github.com/radhesolanki319-pixel/Mobile-Harness`
- **Owner / Boss**: Radhe Solanki (Boss)
- **Device**: iQOO Neo 10 (Model `I2405`, `PD2453F_EX`, Android 15 / Funtouch OS 15).
- **Core Purpose**: Android app (package `com.jarves.mh`) providing a full development environment with embedded coding agents:
  1. **Hermes Agent** (Python/PRoot embedded agent with tool calling)
  2. **Claude Code** (via in-guest CLI / bridge)
  3. **Antigravity** (CLI bridge)

---

## 2. FreeLLM API Server Integration
- **Default Provider**: `ProviderKind.CUSTOM` ("FreeLLM API")
- **Base URL**: `https://freellmapi-backup.onrender.com/v1`
- **Baked API Key**: `freellmapi-c7bf396fcf0351dbba9546933a8881054c627e6f78875206`
- **Default Fast Model**: `claude-haiku-4.5` (Total 107 flagship models available on FreeLLM)
- **Supported Protocols**:
  - OpenAI-compatible (`/v1/chat/completions`)
  - Anthropic-compatible (`/v1/messages`)
  - Models probe (`/v1/models`)
- **Key Storage**: Automatically populated into secure vault (`ApiKeyVault`) under `"FreeLLM Render Key"` so it is active and visible in the UI.

---

## 3. GitHub Build, Release & In-App Update Workflow
Mobile Harness has a built-in **Self-Update / In-App Update System** (`com.jarves.mh.update.GitHubReleaseUpdater` & `AppUpdater`).
Boss can update the app directly inside the phone with a single tap, without using Termux or ADB.

### How to Release an Update:
1. **Bump Version**:
   In `app/build.gradle.kts`:
   ```kotlin
   versionCode = <new_version_code> // e.g. 35 -> 36
   versionName = "1.0.<new_minor>"  // e.g. "1.0.35"
   ```
2. **Commit & Push**:
   ```bash
   git add -A
   git commit -m "feat/fix: <description>"
   git push origin main
   ```
3. **Wait for GitHub Actions Build**:
   Workflow `Build APK` (`.github/workflows/build.yml`) builds debug APKs in the cloud using the stable committed keystore (`keystore/hermes-debug.keystore`).
   Monitor with:
   ```bash
   gh run list --repo radhesolanki319-pixel/Mobile-Harness --limit 1
   ```
4. **Publish GitHub Release**:
   Download the compiled artifact from the successful run and publish a release with tag matching `v<versionCode>`:
   ```bash
   RUN_ID=$(gh run list --repo radhesolanki319-pixel/Mobile-Harness --limit 1 --json databaseId -q '.[0].databaseId')
   gh run download $RUN_ID --repo radhesolanki319-pixel/Mobile-Harness -D /tmp/mh-build
   gh release create v<versionCode> /tmp/mh-build/Mobile-Harness-debug-apk/app-online-debug.apk#Hermes_Agent_v<versionCode>.apk \
     --repo radhesolanki319-pixel/Mobile-Harness \
     --title "Hermes Agent v<versionCode>" \
     --notes "<changelog>"
   ```
5. **In-App Update**:
   - As soon as the release is published, the app checks `https://api.github.com/repos/radhesolanki319-pixel/Mobile-Harness/releases/latest`.
   - When `remoteVersionCode > installedVersionCode`, the app shows an in-app popup dialog: `"Update to Hermes Agent v<versionCode>"`.
   - Boss taps **"Download and install"** → app downloads, verifies signature, and triggers Android system package installer.
   - 1-tap update inside the app!

---

## 4. Hermes Speed & Latency Rules
- **Model Mandate**: Always pass a concrete model (default: `claude-haiku-4.5`) for `ProviderKind.CUSTOM`. NEVER pass an empty model string to FreeLLM API, otherwise FreeLLM retries across all fallback providers for 45 seconds and returns HTTP 400.
- **Warm Server Timing**: `START_WAIT_BUDGET_MS` must remain low (8,000ms / 8s) so if the in-guest gateway server doesn't respond quickly, the turn immediately falls back to CLI without freezing the chat for a minute.
- **Anti-Narration Prompt**: Hermes must NEVER recite "running inside <project> directory on your phone" or narrate that the workspace is empty. Greetings must be concise and natural.
- **Strict Hinglish**: When Boss communicates in Hinglish, Hermes must strictly reply in Roman Hinglish.

---

## 5. Permanent System & Device Protection Rules
1. **Strict Zero-Background Policy**: NEVER leave persistent background processes, servers (`IsDaemon: true`), or ADB daemons running on Boss's phone. Always execute `adb kill-server` immediately after any ADB command.
2. **Thermal Target**: 34°C - 35°C at all times. Never run local Gradle compilations on the phone; let GitHub Actions handle all builds.
3. **Strict Roman Hinglish**: Always communicate with Boss in clean Roman Hinglish. Zero pure English, zero Devanagari script.
4. **Address User**: Always address user as **Boss** (Radhe Solanki).
5. **No Unauthorized Changes**: Never modify system properties (`setprop`), `settings put`, or package configurations without Boss's explicit prior approval.
