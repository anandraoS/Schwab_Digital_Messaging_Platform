#!/usr/bin/env bash
# Manual blue-green using route mapping. NOT executed here. Usage: blue-green.sh <dev|test>
# Flow: push "-green" with a temporary route -> smoke -> map the production route -> unmap from "-blue" -> keep blue (stopped) for rollback.
set -euo pipefail

ENV_NAME="${1:?usage: blue-green.sh <dev|test>}"
HERE="$(dirname "${BASH_SOURCE[0]}")"
CF_DIR="$(dirname "$HERE")"
VARS="$CF_DIR/vars/${ENV_NAME}.yml"
DOMAIN="$(sed -n 's/^apps_domain:[[:space:]]*//p' "$VARS" | tr -d '\r')"
LIVE="messaging-backend-${ENV_NAME}"
GREEN="${LIVE}-green"
HOST="messaging-backend-${ENV_NAME}"

command -v cf >/dev/null 2>&1 || { echo "ERROR: cf CLI not installed" >&2; exit 2; }

echo "1. Push green app (no start traffic on prod route)"
cf push "$GREEN" -f "$CF_DIR/manifest.yml" --vars-file "$VARS" --no-route
cf map-route "$GREEN" "$DOMAIN" --hostname "${HOST}-green"

echo "2. Smoke test green on its temporary route"
bash "$HERE/smoke.sh" "$ENV_NAME" "https://${HOST}-green.${DOMAIN}"

echo "3. Switch: map production route to green, then unmap from live (blue)"
cf map-route "$GREEN" "$DOMAIN" --hostname "$HOST"
cf unmap-route "$LIVE" "$DOMAIN" --hostname "$HOST"
cf unmap-route "$GREEN" "$DOMAIN" --hostname "${HOST}-green"

echo "4. Blue kept running for fast rollback; stop it after the observation window:  cf stop $LIVE"
echo "   Rollback: cf map-route $LIVE $DOMAIN --hostname $HOST && cf unmap-route $GREEN $DOMAIN --hostname $HOST"
