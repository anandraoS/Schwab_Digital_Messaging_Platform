#!/usr/bin/env bash
# Serves the React console (frontend-react/messaging-console-react) with the API proxy pointing at a running backend.
# Usage: bash scripts/serve-react.sh [ui-port=15200] [api-port=18080]
#   MODE=dev      Vite dev server with hot reload (default is MODE=preview: serves the production build, building it first if missing)
#   Angular console: scripts/serve-ui.sh (port 14200). Both talk to the same backend and the same contract.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UI_PORT="${1:-15200}"
export API_PORT="${2:-18080}"
APP="$ROOT/frontend-react/messaging-console-react"
cd "$APP"
if [ ! -d node_modules ]; then
  echo "node_modules missing: run 'npm ci' in $APP first" >&2
  exit 1
fi
if [ "${MODE:-preview}" = "dev" ]; then
  exec npx vite --port "$UI_PORT" --strictPort --host 127.0.0.1
fi
if [ ! -f dist/index.html ]; then
  npm run build
fi
exec npx vite preview --port "$UI_PORT" --strictPort --host 127.0.0.1
