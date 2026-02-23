#!/bin/bash
# Gate script for Claude Code PreToolUse Bash hook
# Routes to pre-release or pre-commit test runner based on context

TOOL_INPUT="${TOOL_INPUT:-}"

# Only trigger on git commit commands
if ! echo "$TOOL_INPUT" | grep -q "git commit"; then
    exit 0
fi

PROJECT_DIR="$(git rev-parse --show-toplevel 2>/dev/null)"
if [ -z "$PROJECT_DIR" ]; then
    exit 0
fi

# Try pre-release hook first (checks for version bump)
if [ -f "$PROJECT_DIR/scripts/hooks/pre-release-test.sh" ]; then
    bash "$PROJECT_DIR/scripts/hooks/pre-release-test.sh" && exit 0
fi

# Fall back to pre-commit hook (tiered tests)
if [ -f "$PROJECT_DIR/scripts/hooks/pre-commit-test.sh" ]; then
    bash "$PROJECT_DIR/scripts/hooks/pre-commit-test.sh"
fi
