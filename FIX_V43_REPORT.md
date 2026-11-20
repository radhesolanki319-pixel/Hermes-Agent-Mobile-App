# FIX V43 — Checksum Corrections

## 1. uv SHA-256 fix (RuntimeInstaller.kt:2325)

- **Before**: `UV_RELEASE_SHA256 = "4bb69cf95ed13f23713bb7bc09f241896ea0c75b7c557e615a20604b82f9c403"`
- **After**: `UV_RELEASE_SHA256 = "0797542018e541a9288855df555c8077eed06f7a47d206362030480e8ecc9f3d"`

The old value was the wrong pinned hash for the uv 0.13.9 aarch64 tarball
(`uv-aarch64-unknown-linux-gnu.tar.gz`), which is why phone setup failed with
"Downloaded file checksum did not match". The new value is the verified real
SHA-256 of uv 0.13.9 aarch64.

## 2. GitHub CLI SHA-256 verification (RuntimeInstaller.kt:2351)

- **Pinned (old)**: `ea4e7a581a32ccad6cc7923cb1576ac5859ba4b9a16ab22eb8f8a96e78e2e961`
- **Computed (real)**: `b1278c75107423d818f1937fd92cafc9e5e9b606631568f68d7fb816f5885a02`

**Result: MISMATCH — fixed.**

Downloaded `gh_2.105.0_linux_arm64.tar.gz` from
`https://github.com/cli/cli/releases/download/v2.105.0/gh_2.105.0_linux_arm64.tar.gz`
(13,684,308 bytes, valid gzip tarball). The computed SHA-256 did not match the
pinned constant, so `GITHUB_CLI_RELEASE_SHA256` was replaced with the real hash.

## 3. Scope

Only the two checksum constants above were changed, plus this report file.
No version, signing, provider, model, or API-key changes were made. No commit
or push was performed.