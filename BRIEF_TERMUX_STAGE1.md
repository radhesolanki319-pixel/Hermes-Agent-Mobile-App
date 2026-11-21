# Brief — Termux runtime migration, Stage 1 (for Claude Code)

You are working in a clone of the user's Android app (Mobile-Harness fork, at v68 / commit 84f42a0). The app currently runs its Linux guest via PRoot + a bundled Ubuntu rootfs. PRoot has caused repeated fatal failures. The user ordered PRoot replaced with the Termux-toolchain system used by the AndroidHarness app.

READ FIRST, IN FULL: `~/workspace/harness_genui/TERMUX_MIGRATION_PLAN.md` — it contains the complete study of both codebases (AndroidHarness's LinuxEnvironment/CompatProcess/TerminalManager and this app's PRoot touchpoints), the staged plan, and the risks. Follow its Stage definitions.

Reference source (MIT-licensed, porting is permitted — keep an attribution comment naming Sanuu7/AndroidHarness in each ported file): `~/workspace/repos/androidharness/app/src/main/java/com/androidharness/app/`

## Your scope: STAGE 1 only (per the plan)

Port the Termux environment into this app:

1. New code under `app/src/main/java/com/jarves/mh/termux/` (adapt names/structure to this app's conventions):
   - Termux package index + mirror handling (termux-main, aarch64), .deb download with SHA-256 verification from the index.
   - .deb extraction into a prefix at `filesDir/termux` (NOT the old rootfs dir), applying AndroidHarness's three extraction rewrites: absolute symlinks → relative (TermuxLinkRewrite equivalent), shebang re-rooting (TermuxShebangs equivalent), git's embedded shell-path patch (TermuxShellPath equivalent).
   - Installed-package marker + a BINARY_PROOF-style sanity check + repair-if-needed (self-heal), mirroring their discipline.
   - Process environment builder: PREFIX, PATH, HOME inside the prefix, BASH_ENV shims file (etc/harness-shims.sh equivalent), termux-exec LD_PRELOAD interception, outermost process launched via /system/bin/linker64 exactly as AndroidHarness does it.
   - Package sets: core = bash, busybox, ca-certificates, termux-exec, git; full adds gh, python, python-pip, nodejs, npm.
2. A developer feature flag (default OFF) — e.g. a boolean in AppPreferences, e.g. `termuxRuntimeEnabled`. When OFF, nothing in the app changes behavior.
3. Terminal-only hookup: when the flag is ON and the Termux prefix is installed, the app's Terminal tab/session runs its shell from the Termux prefix instead of the PRoot guest. Every other caller (agents, setup) keeps using the existing runtime in this stage.
4. A minimal way to trigger the Termux install for testing: reuse the existing setup/install UI plumbing patterns where reasonable; if that is too invasive for this stage, expose an install entry point in the Terminal empty state or Settings — keep it small and note your choice in the report.

## Hard constraints

- Do NOT commit or push anything. Leave the working tree dirty + write `MIGRATION_REPORT.md` (repo root) describing: files added/changed, how the flag + terminal hookup work, what you could NOT verify, and what Stage 2 needs next.
- Do NOT change: versionCode/versionName, signing config, provider/model/API-key logic or stored preferences, the existing PRoot runtime's behavior when the flag is OFF.
- You cannot run Gradle here (VM RAM) — write careful Kotlin: check every import, every referenced symbol against the actual codebase (grep before you reference). Prefer small, self-contained files over editing big ones; where you must edit (Terminal hookup, preferences), keep diffs minimal.
- Do not delete any PRoot code in this stage.

When Stage 1 is complete per the above, stop and finish with the report. Do not start Stage 2.
