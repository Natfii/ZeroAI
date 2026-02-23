#!/bin/bash
# Pre-release test hook for Claude Code
# Runs full test pyramid when version bump is detected

set -euo pipefail

PROJECT_DIR="$(git rev-parse --show-toplevel)"
cd "$PROJECT_DIR"

export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot"
export ANDROID_HOME="/c/Users/Natal/AppData/Local/Android/Sdk"
export PATH="$HOME/.cargo/bin:$JAVA_HOME/bin:$PATH"

# Check if this is a git commit
TOOL_INPUT="${TOOL_INPUT:-}"
if ! echo "$TOOL_INPUT" | grep -q "git commit"; then
    exit 0
fi

# Check if this looks like a version bump
STAGED_DIFF=$(git diff --cached 2>/dev/null || true)
IS_VERSION_BUMP=false

if echo "$STAGED_DIFF" | grep -qE '(versionName|versionCode|^version\s*=)'; then
    IS_VERSION_BUMP=true
fi
if echo "$TOOL_INPUT" | grep -qiE '(bump|release|version)'; then
    IS_VERSION_BUMP=true
fi

if [ "$IS_VERSION_BUMP" = false ]; then
    exit 0
fi

echo "=== Version bump detected: running full test pyramid ==="
FAILED=false

# Rust tests
echo "1/4 Rust unit tests..."
if ! (cd zeroclaw-android && cargo test -p zeroclaw-ffi); then
    FAILED=true
fi

# Kotlin unit tests
echo "2/4 Kotlin unit tests..."
if ! ./gradlew :app:testDebugUnitTest :lib:testDebugUnitTest -q; then
    FAILED=true
fi

# Compose screen tests
echo "3/4 Compose screen tests..."
if adb devices | grep -q "device$"; then
    if ! ./gradlew pixel7Api35DebugAndroidTest --tests "com.zeroclaw.android.screen.*" -q; then
        FAILED=true
    fi
else
    echo "WARN: No emulator, skipping screen tests"
fi

# Maestro journey tests
echo "4/4 Maestro journey tests..."
if command -v maestro &> /dev/null && adb devices | grep -q "device$"; then
    ./gradlew :app:assembleDebug -q
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    if ! maestro test maestro/flows/ --exclude-tags real-daemon; then
        FAILED=true
    fi
else
    echo "WARN: Maestro not available or no emulator, skipping E2E tests"
fi

if [ "$FAILED" = true ]; then
    echo "BLOCKED: Full test pyramid failed. Fix before release commit."
    exit 1
fi

echo ""
echo "All automated tests passed."
echo "REMINDER: Run ./scripts/test-real-daemon.sh locally before pushing."
