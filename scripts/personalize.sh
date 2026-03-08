#!/usr/bin/env bash
set -euo pipefail

# Determine the repository name
if [ $# -ge 1 ]; then
    REPO_NAME="$1"
else
    # Derive from the top-level directory name (mirrors how GitHub Actions uses repository.name)
    REPO_NAME="$(basename "$(git rev-parse --show-toplevel)")"
fi

if [ -z "$REPO_NAME" ]; then
    echo "Error: could not determine repository name." >&2
    echo "Usage: $0 [repo-name]" >&2
    exit 1
fi

echo "Personalizing template with repository name: ${REPO_NAME}"

# Replace __TEMPLATE_NAME__ in all relevant source files
find . -type f \( -name "*.kts" -o -name "*.md" -o -name "*.yml" -o -name "*.kt" -o -name "*.xml" \) \
    ! -path "./.git/*" \
    -exec sed -i "s/__TEMPLATE_NAME__/${REPO_NAME}/g" {} +

# Rename the Java/Kotlin source directory that carries the placeholder name
TEMPLATE_DIR="app/src/main/java/de/codevoid/__TEMPLATE_NAME__"
TARGET_DIR="app/src/main/java/de/codevoid/${REPO_NAME}"

if [ -d "${TEMPLATE_DIR}" ]; then
    mv "${TEMPLATE_DIR}" "${TARGET_DIR}"
    echo "Renamed source directory to: ${TARGET_DIR}"
fi

echo "Done. All __TEMPLATE_NAME__ occurrences replaced with '${REPO_NAME}'."
