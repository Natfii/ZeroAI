#!/usr/bin/env bash
# Copyright (c) 2026 @Natfii. All rights reserved.
#
# Pre-commit hook: runs ktlint, detekt, clippy, and cargo-deny on staged files.
# Install via: scripts/install-hooks.sh

set -euo pipefail

ROOT_DIR="$(git rev-parse --show-toplevel)"

# Collect staged files by extension
STAGED_KT=$(git diff --cached --name-only --diff-filter=ACMR -- '*.kt' || true)
STAGED_KTS=$(git diff --cached --name-only --diff-filter=ACMR -- '*.kts' || true)
STAGED_RS=$(git diff --cached --name-only --diff-filter=ACMR -- '*.rs' || true)

HAS_KOTLIN=false
HAS_RUST=false

if [[ -n "$STAGED_KT" || -n "$STAGED_KTS" ]]; then
    HAS_KOTLIN=true
fi
if [[ -n "$STAGED_RS" ]]; then
    HAS_RUST=true
fi

# Early exit if nothing relevant is staged
if [[ "$HAS_KOTLIN" == false && "$HAS_RUST" == false ]]; then
    exit 0
fi

FAILED=0

# --- Kotlin checks ---
if [[ "$HAS_KOTLIN" == true ]]; then
    echo "==> Running ktlint (spotlessCheck)..."
    if ! "$ROOT_DIR/gradlew" -p "$ROOT_DIR" :app:spotlessCheck --quiet 2>&1; then
        echo "    FAILED: ktlint found formatting issues."
        echo "    Run: ./gradlew :app:spotlessApply"
        FAILED=1
    fi

    echo "==> Running detekt..."
    if ! "$ROOT_DIR/gradlew" -p "$ROOT_DIR" :app:detekt --quiet 2>&1; then
        echo "    FAILED: detekt found issues."
        FAILED=1
    fi
fi

# --- Rust checks ---
if [[ "$HAS_RUST" == true ]]; then
    echo "==> Running clippy..."
    if ! cargo clippy --manifest-path "$ROOT_DIR/zeroclaw-android/Cargo.toml" \
         --all-targets -- -D warnings 2>&1; then
        echo "    FAILED: clippy found warnings/errors."
        FAILED=1
    fi

    echo "==> Running cargo-deny..."
    if ! cargo deny --manifest-path "$ROOT_DIR/zeroclaw-android/Cargo.toml" \
         check 2>&1; then
        echo "    FAILED: cargo-deny found license/advisory issues."
        FAILED=1
    fi
fi

if [[ "$FAILED" -ne 0 ]]; then
    echo ""
    echo "Pre-commit checks failed. Fix the issues above, then re-stage and commit."
    exit 1
fi

echo "Pre-commit checks passed."
