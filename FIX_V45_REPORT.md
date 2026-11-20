# v45 — core bundle 2026.11.3 (GNU coreutils) on top of v44

Built on top of 2789ff2 (v44, Termux agents: bundle download redirects +
warm transport aiohttp). This change only repoints the core bundle and
bumps the rootfs marker so existing installs swap to the new rootfs:

- app/build.gradle.kts: coreBundleBaseUrl -> core-bundle-2026.11.3;
  versionCode 44 -> 45; versionName 1.0.43 -> 1.0.44
- RuntimeInstaller.kt: ROOTFS_VERSION -> ubuntu-26.04.1-arm64-gnu1;
  CORE_TOOLS_VERSION -> core-bundle-2026.11.3; CORE_BUNDLE ->
  pocketdev-core-arm64-2026.11.3.tar.zst,
  sha256 aac62add1b9603f0a74d6b4b9b631d21bc362efbc75d2d72184ede60b036999a,
  84586832 bytes.

Why: Ubuntu 26.04's uutils coreutils carries a patch that refuses to run
when the resolved executable name differs from the applet name. Under
PRoot every applet resolves to the multicall name, so /usr/bin/env, cat
and ls fail with "Security violation" — and the installer enters every
guest command through /usr/bin/env, so Hermes install verification
always failed and Hermes showed "not installed". Core 2026.11.3 switches
the guest to GNU coreutils (coreutils-from-gnu), which has no such check.
Verified in the build chroot: cat/env/ls report GNU coreutils 9.7 and
/usr/bin/env bash -c works.
