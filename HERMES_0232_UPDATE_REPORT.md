# Hermes v0.23.2 + Environment + Ubuntu 26.04 Update — Report

Target release: **v40 / versionName 1.0.39** (version bump happens at release time).
Done by Muse directly, per the user's instruction ("Yeh kaam tum karo", 2026-11-19 ~23:53 IST).
Combined with the MCP + Shizuku feature work in the same tree (see FEATURE notes at the end).

## 1. Hermes 0.19.0 → 0.23.2

- Upstream no longer publishes to PyPI (latest there is still 0.19.0; their
  setup.py blocks wheel builds). v0.23.2 is a GitHub-only release
  (2026-11-16, source tarball only, no wheel assets).
- Wheel built locally from the official tag tarball: pyproject version set
  to 0.23.2 + `HERMES_NIX_BUILD=1 uv build` → `hermes_agent-0.23.2-py3-none-any.whl`.
- Runtime bundle rebuilt: `pocketdev-hermes-arm64-0.23.2.tar.zst`
  - SHA-256: `867188964fac4af901415ea8b1cbd9d8531d91be68c9da00db1216ff04824842`
    (71,191,725 B compressed / 297,840,640 B payload; contents verified:
    venv/bin/hermes, hermes_agent-0.23.2 dist-info, aiohttp-3.14.5)
  - Contents: CPython 3.14.10 (python-build-standalone 20261110 — still the
    latest), venv with `hermes-agent[acp,mcp]==0.23.2`, mcp 2.3.0,
    aiohttp 3.14.5 (needed by the warm API-server transport; not pulled by
    the acp/mcp extras, so it is an explicit bundle requirement).
  - Upstream's own metadata is internally inconsistent (hermes pins
    pillow==13.0.0, browser-harness==0.1.13 pins pillow==12.3.0); the build
    uses a uv override `pillow==13.0.0`. The same override is written by the
    app's network-install fallback.
- App changes (RuntimeInstaller.kt):
  - `HERMES_VERSION` 0.19.0 → 0.23.2; `HERMES_AIOHTTP_SPEC` → aiohttp==3.14.5.
  - Update check moved from PyPI JSON to GitHub
    `repos/NousResearch/hermes-agent/releases/latest` (canary tags with `+`
    are rejected, so canaries are never offered).
  - `installHermesFromPyPi` → `installHermesFromGitHubRelease`: installs
    `hermes-agent[acp,mcp] @ https://github.com/NousResearch/hermes-agent/archive/refs/tags/v<version>.tar.gz`
    with `HERMES_NIX_BUILD=1` and the pillow override file.
  - Bundle constants + `dist/runtime-bundles/manifest.json` updated;
    `hermesBundleBaseUrl` → release tag `hermes-bundle-0.23.2`.

## 2. Direct CLI transport (user directive 2026-11-19 ~23:11 IST)

The chat now talks to the **real Hermes CLI directly**, as the primary
transport:

- New `runtime/HermesStreamJson.kt`: parser/collector for the CLI's
  `--format stream-json` protocol (verified against the 0.23.2 source,
  `hermes_cli/stream_json.py`): `system/init` → `text` deltas → `tool_use` /
  `tool_result` → terminal `result` (canonical text, session id, exit code).
  Think/reasoning blocks are filtered append-only-safe (partial tags at a
  chunk boundary are withheld until resolved).
- `HermesRuntimeBridge.runDirectCliTurnIfPossible`: per-turn
  `hermes chat -q <prompt> --format stream-json --provider <p> [-m <model>] -t all --yolo [--resume <id> --no-restore-cwd]`,
  deltas streamed live into the chat. Session discipline matches the v39
  fixes (a failed turn saves a newly learned session id, else clears the
  resume id — never both saved and cleared).
- Transport order is now: **direct CLI → warm API server → one-shot quiet
  CLI**. The direct path returns "not possible" only when nothing
  protocol-shaped was delivered (spawn failure / pre-0.23.2 CLI), so older
  installs degrade gracefully.
- **Persistent interactive session — blocker, reported honestly:** the
  interactive CLI is a full-screen TUI that needs a TTY; it cannot be
  embedded in the chat. The shipped design is the real CLI in
  one-shot-per-turn form with `--resume` continuity (same session state,
  same tools, streaming structured events) — not a reimplementation.

## 3. Ubuntu core 20.04 → 26.04.1 LTS

- New core bundle built from the official `ubuntu-base-26.04.1-base-arm64`
  tarball (SHA-256 verified against cdimage.ubuntu.com), replicating the
  upstream core build on this VM via binfmt_misc + static qemu-aarch64:
  apt update+upgrade, tools (git, ca-certificates, curl, wget, unzip, zip,
  xz-utils, zstd, libatomic1 — Node's arm64 binaries link libatomic.so.1,
  which ubuntu-base does not ship), Node.js (below), same `/.pocket-*`
  markers. (Build detail: the guest's GNU tar cannot create files under
  qemu-user, so the checksum-verified Node tarball is extracted host-side
  and copied in; the guest links and smoke-tests it — `node --version`,
  `npm --version`, `git --version` all run in the guest during the build.)
  - `pocketdev-core-arm64-2026.11.1.tar.zst` (84,584,526 B compressed /
    456,222,720 B payload)
  - SHA-256: `b682be31d26de9330f5e7f6f458e2c61dee3b09b34283b1a9ebf83d53549fe16`
  - Guest smoke tests passed during the build: `node --version` →
    v26.13.1, `npm --version` → 11.20.0, `git --version` → 2.53.0.
- App changes: new `coreBundleBaseUrl` → release tag `core-bundle-2026.11.1`
  (the new core is NOT fetched from the upstream runtime release, which
  still carries the 20.04 core); `CORE_BUNDLE` constants updated;
  offline flavor embeds the new core file name.
- Upgrade path for existing installs (verified in code): bumping
  `ROOTFS_VERSION` to `ubuntu-26.04.1-arm64` makes the first launch after
  the update download the new core and swap the rootfs via
  `RootfsRepair.activate`, which **preserves the whole guest `/root`**
  (Hermes's `/root/.hermes` — config, sessions, memory — plus Claude /
  Antigravity home state). Projects live on the Android side and are
  bind-mounted, so they are untouched. `CORE_TOOLS_VERSION` →
  `core-bundle-2026.11.1` (previous value kept as the legacy-accepted
  one), `NODE_VERSION` → v26.13.1 (fallback path only; the shipped core
  already carries Node). One fix this required: the Hermes version
  marker lives outside `/root`, so after a swap the old 0.19.0 venv
  would outlive its marker and the 0.23.2 bundle would extract over it —
  `ensureHermesInstalled` now wipes a marker-less leftover venv before
  installing, so Hermes always lands clean (its data in `/root/.hermes`
  is never touched).
- ⚠️ On-device risk, stated plainly: Ubuntu 26.04 ships a much newer glibc;
  whether the app's PRoot build handles it can only be settled on the
  phone. The old core bundle remains published and untouched as fallback.

## 4. Environment bumps

| Component | Old | New | Note |
|---|---|---|---|
| Hermes Agent | 0.19.0 (PyPI) | **0.23.2** (GitHub) | §1 |
| Ubuntu core | 20.04 | **26.04.1 LTS** | §3 |
| Node.js (core) | v24.19.0 | **v26.13.1 LTS** | SHASUMS256-verified in build |
| uv | 0.13.5 | **0.13.9** | RuntimeInstaller pin + URL |
| GitHub CLI | v2.100.0 | **v2.105.0** | RuntimeInstaller pin (verified latest) |
| aiohttp (Hermes venv) | 3.14.1 | **3.14.5** | matches 0.23.2's own pin |
| CPython (Hermes bundle) | 3.14.10 | 3.14.10 | already latest (20261110) |
| Claude Code / Antigravity bundles | — | unchanged | both self-update in-guest via the app's existing update paths, so a stale bootstrap bundle self-heals |
| Android build toolchain | — | unchanged | explicitly out of scope per the user's order |

## 5. Re-verifications against the 0.23.2 source

- `--format stream-json` and `--query-file` exist; stream-json implies
  `--quiet` and requires `-q`. Protocol read in full from source.
- Config keys still present: `approvals.single_query_mode`,
  `delegation.max_concurrent_children`, `agent.reasoning_effort`,
  `platform_toolsets`, `agent.coding_context`, `tools.tool_search`,
  `show_subagents`, `providers.<name>`; `model.max_tokens` is a recognized
  agent init param. Warm contract intact (api_server `POST
  /v1/chat/completions`, `X-Hermes-Session-Id`).
- Provider catalog re-checked against 0.23.2's registry + model-provider
  plugins: all 30 remaining app ids exist upstream. **Upstream removed
  `copilot`** — removed from `HermesProviderCatalog` here too (the user's
  active profile is OpenRouter; nothing stored is touched).
- `mcp_servers` config format unchanged; every key the app writes
  (command/args/env/url/headers/auth/oauth/enabled/tools/connect_timeout)
  is read by 0.23.2's `hermes_cli/mcp_config.py` / MCP launch path.
- Standing rules preserved: no provider/model/key is written, reset or
  defaulted anywhere in this update; Hermes still decides its own model
  when none is picked; the v27 FreeLLM pre-connect is untouched (stored
  key always wins).

## 6. Also in this tree (MCP + Shizuku features)

Implemented by the VM's Claude Code under brief, then reviewed and
compile-fixed by Muse (the Actions build is the first full compile):

- **MCP support**: Settings → MCP Servers (add stdio/remote servers,
  enable/disable, delete). Servers are written into Hermes's config.yaml
  `mcp_servers:` section and seeded for Claude Code as `.mcp.json` in the
  project's (bind-mounted) workspace. Antigravity is not wired for MCP
  in this version. Review fix by Muse: the seed originally wrote under
  the rootfs's own /workspace, which the workspace bind-mount shadows —
  Claude Code would never have seen the file; it now writes to the host
  workspace directory, and removing/disabling all servers cleans up or
  empties the seeded file.
- **Shizuku phone control**: Settings → Phone Control. With the Shizuku
  app installed and permission granted, agents can run Android-host
  commands via the in-guest `harness-host` command (localhost bridge,
  per-install token). Fixed against the real Shizuku 13.1.5 API
  (`rikka.shizuku` package; user service as an AIDL Stub binder — the
  first draft used a wrong package and a manifest service entry that
  Shizuku user services must not have).
- Hardening by Muse during review: MCP server names / env / header keys
  are YAML-quoted on emission so a free-text name cannot corrupt
  config.yaml.

## 7. On-device verification list (after install)

1. Hermes chat: reply arrives via direct CLI (fast, streaming), no
   narration/disclaimer leakage; second turn continues the same session.
2. Settings → MCP Servers: add a server, run a Hermes turn that uses it.
3. Settings → Phone Control: install Shizuku → Grant → enable → a Hermes
   turn running `harness-host …` works.
4. Terminal: `lsb_release -a` → Ubuntu 26.04.1 LTS; `node --version` →
   v26.13.1.
5. Provider/model/key exactly as before the update (OpenRouter profile
   untouched); Hermes model still unset unless picked.
