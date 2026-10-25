#!/usr/bin/env bash
# Serves the Angular console with the dev proxy pointing at a locally running backend.
# Usage: bash scripts/serve-ui.sh [ui-port=14200] [api-port=18080]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UI_PORT="${1:-14200}"
export API_PORT="${2:-18080}"
cd "$ROOT/frontend/messaging-console"
exec npx ng serve --port "$UI_PORT" --proxy-config proxy.conf.js --host 127.0.0.1
