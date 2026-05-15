#!/bin/bash
# Установка pre-commit хука
# Копирует хуки из tools/git-hooks/ в .git/hooks/

HOOKS_SRC="$(dirname "$0")/git-hooks"
HOOKS_DST="$(git rev-parse --git-dir)/hooks"

for hook in "$HOOKS_SRC"/*; do
    name=$(basename "$hook")
    cp "$hook" "$HOOKS_DST/$name"
    chmod +x "$HOOKS_DST/$name"
    echo "✅ $name установлен"
done
