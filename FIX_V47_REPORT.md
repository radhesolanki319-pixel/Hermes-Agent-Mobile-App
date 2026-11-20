# FIX_V46_REPORT — point the app at core bundle 2026.11.4

## Root cause

Core bundle **2026.11.3** shipped **absolute symlinks** for
`usr/local/bin/node|npm|npx` and `usr/local/lib/nodejs/current` — a regression
vs 2026.11.2's relative links. On the host side, the readiness check
`File(rootfs, "usr/local/bin/node").exists()` (RuntimeInstaller.kt:102) follows
the absolute symlink **outside** the rootfs, which does not exist on the
Android host, so `exists()` returns `false`. As a result:

- `isInstalled()` stayed `false` after setup completed successfully.
- `installedRuntime()` (RuntimeInstaller.kt:126) threw
  `IllegalStateException("Core runtime setup is incomplete")` at startup.

## Fix

New bundle **2026.11.4** with relative links + `gnu2` rootfs markers, already
built and published. This change only repoints the app's pinned constants at
it and bumps the app version.

## Exact lines changed

### app/src/main/java/com/jarves/mh/runtime/RuntimeInstaller.kt

| Line (new) | Change |
|---|---|
| 2432 | `ROOTFS_VERSION`: `"ubuntu-26.04.1-arm64-gnu1"` → `"ubuntu-26.04.1-arm64-gnu2"` |
| 2438 | `CORE_TOOLS_VERSION`: `"core-bundle-2026.11.3"` → `"core-bundle-2026.11.4"` |
| 2466 | `CORE_BUNDLE.fileName`: `"pocketdev-core-arm64-2026.11.3.tar.zst"` → `"pocketdev-core-arm64-2026.11.4.tar.zst"` |
| 2467 | `CORE_BUNDLE.sha256` → `"6c04f498fa152d8e74e0a515c111268cd6032dddb9b28cd66d16fcabd5281065"` |
| 2468 | `CORE_BUNDLE.compressedBytes`: `84_586_832L` → `84_593_164L` |

### app/build.gradle.kts

| Line (new) | Change |
|---|---|
| 44 | `coreBundleBaseUrl` tag segment: `core-bundle-2026.11.3` → `core-bundle-2026.11.4` |
| 150 | `versionCode`: `45` → `46` |
| 151 | `versionName`: `"1.0.44"` → `"1.0.45"` |

## Verification

`git diff` confirms only the 8 lines above changed across the two files. No
provider/model/key/default logic, signing, workflow files, or Hermes bundle
constants were touched. No commit, no push (per brief).
