# Brief — Termux migration, Stage 2 (DRAFT — finalize only after Stage 1 fix round passes Muse's verification)

Goal (per TERMUX_MIGRATION_PLAN.md Stage 2): put the app's process execution on the Termux environment behind the SAME flag (`termuxRuntimeEnabled`), so the Native agent and the terminal run without PRoot when the flag is ON. Flag OFF = PRoot path untouched.

## Scope

1. **Exec parity facade**: a Termux-backed implementation of the execution contract the app already uses:
   - Study `RuntimeInstaller.process()` and every call site (13+): bridges (Native/Hermes/Claude), terminal, setup checks. Catalog the exact contract: argv, cwd, env extras, stdin, streaming stdout/stderr callbacks, exit codes, timeouts, Process handle semantics (incl. the NativeSpawnProcess outputFile/PTY specifics).
   - Implement the same contract over `TermuxEnvironment.startShell`/ProcessBuilder: env from `TermuxEnvironment.processEnv` + caller extras, cwd mapping (guest `/workspace/<slug>` → real dir), streaming preserved.
   - Route at ONE choke point if the codebase has one; otherwise a small `RuntimeRouter` that picks PRoot vs Termux by the flag at each existing call site with minimal diffs.
2. **Workspace mapping**: inside the Termux prefix, create `workspace/<slug>` symlinks → the app's real project dirs (filesDir/workspaces/<id>), so guest-style paths keep working for the Native agent's tools (read_file/write_file/shell cwd).
3. **Cut-over behind the flag**: Native agent shell tool + terminal use the facade when flag ON; Hermes/Claude bridges NOT yet (Stages 3–4).
4. **Setup flow untouched** in this stage (Stage 5), except: when flag ON, the terminal/agent paths must detect "Termux not installed" and surface the Stage 1 install entry point instead of failing.

## Constraints

Same as Stage 1: no commit/push, no version/signing/provider changes, flag OFF = zero behavior change, mandatory grep-based self-check in the report (every new import/symbol evidenced), honest "could not verify" section.

## Open questions for Muse before this brief is final

- Exact choke point vs per-site routing (decide from the Stage 1 tree + call-site catalog).
- PTY support: does the terminal need a PTY under Termux, and does AndroidHarness's approach (linker64 + shims) sustain an interactive shell? (Stage 0 device spike informs this.)
