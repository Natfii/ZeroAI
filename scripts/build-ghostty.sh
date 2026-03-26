#!/usr/bin/env bash
# Copyright (c) 2026 @Natfii. All rights reserved.
#
# Cross-compile libghostty-vt for Android targets.
# Requires: Zig 0.15.x installed, Ghostty repo cloned to vendor/ghostty-src.
#
# Usage:
#   ./scripts/build-ghostty.sh [--clone]
#
# The --clone flag clones the Ghostty repo if not already present.
# Without it, assumes vendor/ghostty-src already exists.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
FFI_DIR="$PROJECT_ROOT/zeroclaw-android/zeroclaw-ffi"
LIBS_DIR="$FFI_DIR/libs"
VENDOR_DIR="$FFI_DIR/vendor"
GHOSTTY_SRC="$VENDOR_DIR/ghostty-src"
GHOSTTY_REPO="https://github.com/ghostty-org/ghostty.git"

# Pinned commit — update when upgrading libghostty-vt
GHOSTTY_COMMIT="HEAD"

if [[ "${1:-}" == "--clone" ]]; then
    if [ ! -d "$GHOSTTY_SRC" ]; then
        echo "=== Cloning Ghostty ==="
        git clone --depth 100 "$GHOSTTY_REPO" "$GHOSTTY_SRC"
    fi
    cd "$GHOSTTY_SRC"
    git fetch origin
    git checkout "$GHOSTTY_COMMIT"
    cd "$PROJECT_ROOT"
fi

if [ ! -d "$GHOSTTY_SRC" ]; then
    echo "ERROR: $GHOSTTY_SRC not found."
    echo "Run: ./scripts/build-ghostty.sh --clone"
    exit 1
fi

cd "$GHOSTTY_SRC"

echo "=== Building libghostty-vt for aarch64-linux-android ==="
zig build -Demit-lib-vt=true -Dtarget=aarch64-linux-android -Doptimize=ReleaseSafe
mkdir -p "$LIBS_DIR/aarch64"
cp zig-out/lib/libghostty-vt.a "$LIBS_DIR/aarch64/libghostty_vt.a"

echo "=== Building libghostty-vt for x86_64-linux-android ==="
zig build -Demit-lib-vt=true -Dtarget=x86_64-linux-android -Doptimize=ReleaseSafe
mkdir -p "$LIBS_DIR/x86_64"
cp zig-out/lib/libghostty-vt.a "$LIBS_DIR/x86_64/libghostty_vt.a"

# Copy headers for bindgen
HEADER_DIR="$VENDOR_DIR/ghostty/include/ghostty"
mkdir -p "$HEADER_DIR"
cp -r zig-out/include/ghostty/vt "$HEADER_DIR/"
cp zig-out/include/ghostty/vt.h "$HEADER_DIR/"

echo "=== Done ==="
ls -lh "$LIBS_DIR"/aarch64/libghostty_vt.a "$LIBS_DIR"/x86_64/libghostty_vt.a
echo "Headers: $HEADER_DIR/vt.h"
