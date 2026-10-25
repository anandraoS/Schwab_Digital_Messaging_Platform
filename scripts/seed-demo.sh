#!/usr/bin/env bash
# Seeds demo messages + one synthetic bulk job through the API. Usage: bash scripts/seed-demo.sh [baseUrl] [count]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$ROOT/scripts/lib/seed-demo.mjs"
if command -v cygpath >/dev/null 2>&1; then SCRIPT="$(cygpath -m "$SCRIPT")"; fi
exec node "$SCRIPT" "${1:-${BASE_URL:-http://localhost:18080}}" "${2:-24}"
