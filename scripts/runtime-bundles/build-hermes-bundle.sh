#!/usr/bin/env bash
set -euo pipefail

# Build the prebuilt Hermes Agent runtime bundle (Phase 2 of the Hermes plan).
#
# Unlike the other bundle scripts this one needs no ARM64 host: the payload is
# assembled entirely from prebuilt aarch64 artifacts, without executing any
# aarch64 code —
#
#   1. a standalone CPython 3.14 for aarch64-unknown-linux-gnu from
#      python-build-standalone (the same source `uv python install` uses),
#      laid out exactly the way uv lays it out under UV_PYTHON_INSTALL_DIR, so
#      the in-app uv/network install route keeps working on top of it;
#   2. the site-packages closure of the pinned PyPI release
#      `hermes-agent[acp]==<version>`, resolved by uv for the aarch64 platform
#      from binary wheels only (--only-binary :all:), i.e. byte-for-byte the
#      same packages the in-guest network install would fetch;
#   3. a venv-shaped tree whose pyvenv.cfg, interpreter symlinks and console
#      entry scripts point at the GUEST paths (/root/.pocket-hermes/...),
#      generated from the resolved packages' own entry_points metadata using
#      the same script template uv writes.
#
# The result lands at guest /root/.pocket-hermes/{python,venv} when the app
# extracts the overlay into its rootfs, which is where RuntimeInstaller and
# HermesRuntimeBridge expect it (HERMES_GUEST_PATH =
# /root/.pocket-hermes/venv/bin/hermes). The version marker is written by the
# installer after its in-guest verification, like the Antigravity bundle.
#
# Requirements: bash, curl, tar, python3, uv, and zstd (CLI, or the Python
# `zstandard` module as a fallback compressor).

HERMES_VERSION="${POCKETDEV_HERMES_VERSION:-0.19.0}"
ARCHIVE="pocketdev-hermes-arm64-${HERMES_VERSION}.tar.zst"

PYTHON_VERSION="3.14.10"
PYTHON_RELEASE="20261110"
PYTHON_DIR_NAME="cpython-${PYTHON_VERSION}-linux-aarch64-gnu"
PYTHON_TARBALL="cpython-${PYTHON_VERSION}+${PYTHON_RELEASE}-aarch64-unknown-linux-gnu-install_only_stripped.tar.gz"
PYTHON_URL="https://github.com/astral-sh/python-build-standalone/releases/download/${PYTHON_RELEASE}/${PYTHON_TARBALL}"
# SHA-256 as published in the python-build-standalone release asset digest.
PYTHON_SHA256="38fd50f07170adc29d481913d1c30b17ecb538fbe290315fe1f83d84fdd5b694"

GUEST_PREFIX="/root/.pocket-hermes"
GUEST_VENV="${GUEST_PREFIX}/venv"
# The interpreter is referenced by its canonical versioned directory name
# (the name uv itself installs and discovers). uv also creates a
# cpython-3.14-linux-aarch64-gnu alias symlink, but the deterministic tar
# builder does not carry directory symlinks, and nothing at runtime needs
# the alias — so the bundle omits it (and the venv's lib64 -> lib alias)
# rather than shipping links the archive would silently drop.
GUEST_PYTHON_HOME="${GUEST_PREFIX}/python/${PYTHON_DIR_NAME}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
BUNDLES_DIR="$ROOT_DIR/dist/runtime-bundles"
WORK_DIR="${POCKETDEV_HERMES_BUILD_DIR:-$(mktemp -d)}"
CACHE_DIR="${POCKETDEV_HERMES_CACHE_DIR:-$WORK_DIR/cache}"
KEEP_WORK="${POCKETDEV_HERMES_KEEP_WORK:-0}"
[[ "$KEEP_WORK" == "1" ]] || trap 'rm -rf "$WORK_DIR"' EXIT

command -v uv >/dev/null || { echo "uv is required" >&2; exit 1; }
command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 1; }
mkdir -p "$BUNDLES_DIR" "$CACHE_DIR" "$WORK_DIR"

# --- 1. Standalone CPython 3.14 (aarch64, glibc) -----------------------------
python_archive="$CACHE_DIR/$PYTHON_TARBALL"
if [[ ! -f "$python_archive" ]]; then
  echo "Downloading $PYTHON_TARBALL"
  curl -fsSL "$PYTHON_URL" -o "$python_archive"
fi
actual_sha256="$(shasum -a 256 "$python_archive" | cut -d' ' -f1)"
[[ "$actual_sha256" == "$PYTHON_SHA256" ]] || {
  echo "CPython archive checksum mismatch: $actual_sha256" >&2
  exit 1
}

payload="$WORK_DIR/payload"
prefix="$payload/root/.pocket-hermes"
mkdir -p "$prefix/python"
tar -xzf "$python_archive" -C "$WORK_DIR"
# The install_only tarball wraps the tree in a top-level `python/` directory;
# uv installs its contents under the canonical name
# cpython-<ver>-linux-aarch64-gnu inside UV_PYTHON_INSTALL_DIR.
mv "$WORK_DIR/python" "$prefix/python/$PYTHON_DIR_NAME"

# --- 2. Resolve hermes-agent[acp] for aarch64 from binary wheels only --------
site_packages="$WORK_DIR/site-packages"
echo "Resolving hermes-agent[acp]==$HERMES_VERSION for aarch64 (binary wheels only)"
uv pip install \
  --target "$site_packages" \
  --python-platform aarch64-unknown-linux-gnu \
  --python-version 3.14 \
  --only-binary :all: \
  "hermes-agent[acp]==$HERMES_VERSION"

# Wheels resolved for another platform would poison the guest; refuse to pack
# anything that is not aarch64.
if find "$site_packages" -name '*.so' | grep -q 'x86_64'; then
  echo "Resolution produced x86_64 binaries; refusing to pack" >&2
  exit 1
fi

# --- 3. Assemble the venv-shaped tree ----------------------------------------
venv="$prefix/venv"
mkdir -p "$venv/bin" "$venv/lib/python3.14"
mv "$site_packages" "$venv/lib/python3.14/site-packages"

# Wheel `.data/scripts` payloads belong in the venv's bin directory.
if [[ -d "$venv/lib/python3.14/site-packages/bin" ]]; then
  mv "$venv/lib/python3.14/site-packages/bin/"* "$venv/bin/" 2>/dev/null || true
  rmdir "$venv/lib/python3.14/site-packages/bin" 2>/dev/null || true
fi

# Interpreter links, mirroring a uv-created venv.
ln -s "${GUEST_PYTHON_HOME}/bin/python3.14" "$venv/bin/python"
ln -s python "$venv/bin/python3"
ln -s python "$venv/bin/python3.14"

UV_VERSION_USED="$(uv --version | awk '{print $2}')"
cat > "$venv/pyvenv.cfg" <<EOF
home = ${GUEST_PYTHON_HOME}/bin
implementation = CPython
uv = ${UV_VERSION_USED}
version_info = 3.14
include-system-site-packages = false
EOF

# Console entry scripts, generated from the resolved packages' entry_points
# metadata with the exact template uv/pip write, shebang pointed at the guest
# venv interpreter.
python3 - "$venv" "$GUEST_VENV" <<'PYEOF'
import configparser
import sys
from pathlib import Path

venv = Path(sys.argv[1])
guest_venv = sys.argv[2]
site_packages = venv / "lib" / "python3.14" / "site-packages"
bin_dir = venv / "bin"

TEMPLATE = """#!{shebang}
# -*- coding: utf-8 -*-
import sys
from {module} import {imported}
if __name__ == "__main__":
    if sys.argv[0].endswith("-script.pyw"):
        sys.argv[0] = sys.argv[0][:-11]
    elif sys.argv[0].endswith(".exe"):
        sys.argv[0] = sys.argv[0][:-4]
    sys.exit({call}())
"""

written = []
for dist_info in sorted(site_packages.glob("*.dist-info")):
    entry_points = dist_info / "entry_points.txt"
    if not entry_points.is_file():
        continue
    parser = configparser.ConfigParser()
    parser.read(entry_points)
    if not parser.has_section("console_scripts"):
        continue
    for name, target in parser.items("console_scripts"):
        target = target.split("[")[0].strip()  # strip extras markers
        module, _, attr = target.partition(":")
        imported = attr.split(".")[0]
        script = bin_dir / name
        script.write_text(
            TEMPLATE.format(
                shebang=f"{guest_venv}/bin/python",
                module=module.strip(),
                imported=imported,
                call=attr.strip(),
            )
        )
        script.chmod(0o755)
        written.append(name)

required = {"hermes", "hermes-agent", "hermes-acp"}
missing = required - set(written)
if missing:
    raise SystemExit(f"Missing required console scripts: {sorted(missing)}")
print(f"Console scripts written: {len(written)} ({', '.join(sorted(written))})")
PYEOF

# Activation helpers, modelled on a uv venv with the guest path substituted.
REF_VENV="${POCKETDEV_HERMES_REF_VENV:-$HOME/workspace/hermes_spike/src/.venv}"
if [[ -d "$REF_VENV/bin" ]]; then
  for helper in activate activate.csh activate.fish activate.nu activate.ps1 activate.xsh activate.bat activate_this.py deactivate.bat pydoc.bat; do
    if [[ -f "$REF_VENV/bin/$helper" ]]; then
      sed "s|$REF_VENV|$GUEST_VENV|g" "$REF_VENV/bin/$helper" > "$venv/bin/$helper"
    fi
  done
fi

# No bytecode caches: the guest compiles fresh for its own architecture.
find "$prefix" -name '__pycache__' -type d -prune -exec rm -rf {} + 2>/dev/null || true
find "$prefix" -name '*.pyc' -delete 2>/dev/null || true

# --- 4. Deterministic tar + zstd ---------------------------------------------
# The deterministic tar builder does not carry directory symlinks; fail
# loudly instead of silently dropping one (see the GUEST_PYTHON_HOME note).
while IFS= read -r link; do
  if [[ -d "$link" ]]; then
    echo "Directory symlink in payload would be dropped by the tar builder: $link" >&2
    exit 1
  fi
done < <(find "$payload" -type l)

python3 "$SCRIPT_DIR/create_deterministic_tar.py" "$payload" "$WORK_DIR/payload.tar"
if command -v zstd >/dev/null; then
  zstd -19 -T0 -f "$WORK_DIR/payload.tar" -o "$BUNDLES_DIR/$ARCHIVE"
else
  python3 - "$WORK_DIR/payload.tar" "$BUNDLES_DIR/$ARCHIVE" <<'PYEOF'
import sys
import zstandard

src, dst = sys.argv[1], sys.argv[2]
compressor = zstandard.ZstdCompressor(level=19, threads=-1)
with open(src, "rb") as fin, open(dst, "wb") as fout:
    compressor.copy_stream(fin, fout)
PYEOF
fi

shasum -a 256 "$BUNDLES_DIR/$ARCHIVE"
wc -c "$BUNDLES_DIR/$ARCHIVE" "$WORK_DIR/payload.tar"
echo "Hermes bundle created: dist/runtime-bundles/$ARCHIVE"
echo "Guest layout: $GUEST_PREFIX/python ($PYTHON_DIR_NAME) + $GUEST_VENV (hermes-agent[acp]==$HERMES_VERSION)"
