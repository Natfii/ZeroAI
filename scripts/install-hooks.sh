#!/usr/bin/env bash
# Copyright (c) 2026 @Natfii. All rights reserved.
#
# Installs git hooks by symlinking scripts/ into .git/hooks/.
# Run once after cloning: bash scripts/install-hooks.sh

set -euo pipefail

ROOT_DIR="$(git rev-parse --show-toplevel)"
HOOKS_DIR="$ROOT_DIR/.git/hooks"
SCRIPTS_DIR="$ROOT_DIR/scripts"

echo "Installing git hooks..."

# Pre-commit
ln -sf "$SCRIPTS_DIR/pre-commit.sh" "$HOOKS_DIR/pre-commit"
echo "  Installed pre-commit -> scripts/pre-commit.sh"

echo "Done. Hooks installed to .git/hooks/"
