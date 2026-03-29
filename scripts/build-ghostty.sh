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

# Pinned commit — update when upgrading libghostty-vt.
# Upgrade procedure:
#   1. Update GHOSTTY_COMMIT to the new hash
#   2. Run: ./scripts/build-ghostty.sh --clone
#   3. Diff vendor/ghostty/include/ghostty/vt.h against ghostty_sys.rs
#   4. Update ghostty_sys.rs for any ABI changes
#   5. Run: cargo clippy --target aarch64-linux-android --features ghostty-vt
GHOSTTY_COMMIT="efc0e4118a39f2d8364a02053b5a9a8e4118dcec"

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

# Verify the vendored source matches the pinned commit.
ACTUAL="$(git -C "$GHOSTTY_SRC" rev-parse HEAD)"
if [ "$ACTUAL" != "$GHOSTTY_COMMIT" ]; then
    echo "ERROR: vendor/ghostty-src is at $ACTUAL, expected $GHOSTTY_COMMIT"
    echo "Run: ./scripts/build-ghostty.sh --clone"
    exit 1
fi

cd "$GHOSTTY_SRC"

echo "=== Building libghostty-vt for aarch64-linux-android ==="
zig build -Demit-lib-vt=true -Dtarget=aarch64-linux-android -Doptimize=ReleaseSafe
mkdir -p "$LIBS_DIR/aarch64"
# Zig produces both .a and .so; we need the .so for dynamic linking on Android.
# Use the unversioned symlink (always created by Zig) and rename hyphen→underscore
# to match the rustc-link-lib=dylib=ghostty_vt directive in build.rs.
cp zig-out/lib/libghostty-vt.so "$LIBS_DIR/aarch64/libghostty_vt.so"

echo "=== Building libghostty-vt for x86_64-linux-android ==="
zig build -Demit-lib-vt=true -Dtarget=x86_64-linux-android -Doptimize=ReleaseSafe
mkdir -p "$LIBS_DIR/x86_64"
cp zig-out/lib/libghostty-vt.so "$LIBS_DIR/x86_64/libghostty_vt.so"

# Copy into jniLibs for APK bundling
JNILIBS="$PROJECT_ROOT/app/src/main/jniLibs"
mkdir -p "$JNILIBS/arm64-v8a" "$JNILIBS/x86_64"
cp "$LIBS_DIR/aarch64/libghostty_vt.so" "$JNILIBS/arm64-v8a/libghostty_vt.so"
cp "$LIBS_DIR/x86_64/libghostty_vt.so"  "$JNILIBS/x86_64/libghostty_vt.so"

# Copy headers for reference
HEADER_DIR="$VENDOR_DIR/ghostty/include/ghostty"
mkdir -p "$HEADER_DIR"
cp -r zig-out/include/ghostty/vt "$HEADER_DIR/"
cp zig-out/include/ghostty/vt.h "$HEADER_DIR/"

echo "=== Done ==="
ls -lh "$LIBS_DIR"/aarch64/libghostty_vt.so "$LIBS_DIR"/x86_64/libghostty_vt.so
ls -lh "$JNILIBS"/arm64-v8a/libghostty_vt.so "$JNILIBS"/x86_64/libghostty_vt.so
echo "Headers: $HEADER_DIR/vt.h"
