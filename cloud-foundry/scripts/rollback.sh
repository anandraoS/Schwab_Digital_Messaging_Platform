#!/usr/bin/env bash
# Roll back a CF app. NOT executed here. Usage: rollback.sh <app-name> [revision-number]
# Uses `cf rollback` (revisions, CLI v8+). Fallback documented in docs: re-assign a previous droplet with cf set-droplet.
set -euo pipefail

APP="${1:?usage: rollback.sh <app-name> [revision]}"
REV="${2:-}"

command -v cf >/dev/null 2>&1 || { echo "ERROR: cf CLI not installed" >&2; exit 2; }

echo "Revisions for $APP:"
cf revisions "$APP"

if [ -z "$REV" ]; then
  echo "Specify a revision number to roll back to (see list above)." >&2
  exit 2
fi

cf rollback "$APP" --version "$REV" --strategy rolling
echo "Rollback to revision $REV submitted. Verify: cf app $APP && bash $(dirname "${BASH_SOURCE[0]}")/smoke.sh <env>"
