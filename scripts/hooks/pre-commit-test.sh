#!/bin/bash
# Pre-commit test hook for Claude Code
# Detects which files are being committed and runs appropriate test tier

set -euo pipefail

PROJECT_DIR="$(git rev-parse --show-toplevel)"
cd "$PROJECT_DIR"

export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot"
export ANDROID_HOME="/c/Users/Natal/AppData/Local/Android/Sdk"
export PATH="$HOME/.cargo/bin:$JAVA_HOME/bin:$PATH"

# Check if this is actually a git commit command
TOOL_INPUT="${TOOL_INPUT:-}"
if ! echo "$TOOL_INPUT" | grep -q "git commit"; then
    exit 0
fi

# Get staged files
STAGED=$(git diff --cached --name-only 2>/dev/null || true)
if [ -z "$STAGED" ]; then
    exit 0
fi

RUN_RUST=false
RUN_KOTLIN=false
RUN_SCREEN=false

# Classify changed files
while IFS= read -r file; do
    case "$file" in
        zeroclaw-ffi/src/*|zeroclaw-android/zeroclaw-ffi/src/*)
            RUN_RUST=true
            ;;
        app/src/main/java/*/ui/*|app/src/main/java/*/screen/*)
            RUN_KOTLIN=true
            RUN_SCREEN=true
            ;;
        app/src/*|lib/src/*)
            RUN_KOTLIN=true
            ;;
        maestro/*)
            RUN_SCREEN=true
            ;;
    esac
done <<< "$STAGED"

FAILED=false

if [ "$RUN_RUST" = true ]; then
    echo "Running Rust unit tests..."
    if ! (cd zeroclaw-android && cargo test -p zeroclaw-ffi); then
        FAILED=true
    fi
fi

if [ "$RUN_KOTLIN" = true ]; then
    echo "Running Kotlin unit tests..."
    if ! ./gradlew :app:testDebugUnitTest :lib:testDebugUnitTest -q; then
        FAILED=true
    fi
fi

if [ "$RUN_SCREEN" = true ]; then
    echo "Running Compose screen tests (needs emulator)..."
    if adb devices | grep -q "device$"; then
        if ! ./gradlew pixel7Api35DebugAndroidTest --tests "com.zeroclaw.android.screen.*" -q; then
            FAILED=true
        fi
    else
        echo "WARN: No emulator connected, skipping screen tests"
    fi
fi

if [ "$FAILED" = true ]; then
    echo "BLOCKED: Tests failed. Fix before committing."
    exit 1
fi
