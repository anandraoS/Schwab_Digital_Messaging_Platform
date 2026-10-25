#!/usr/bin/env bash
# End-to-end smoke test against a running backend (profile local). Usage: bash scripts/smoke-test.sh [baseUrl]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$ROOT/scripts/lib/smoke.mjs"
if command -v cygpath >/dev/null 2>&1; then SCRIPT="$(cygpath -m "$SCRIPT")"; fi
exec node "$SCRIPT" "${1:-${BASE_URL:-http://localhost:18080}}"
