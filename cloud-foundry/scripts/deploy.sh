#!/usr/bin/env bash
# Rolling deploy to Cloud Foundry. NOT executed on the authoring machine (no cf CLI / foundation).
# Usage: cloud-foundry/scripts/deploy.sh <dev|test>   (requires `cf login` already done; never pass passwords on the command line)
set -euo pipefail

ENV_NAME="${1:?usage: deploy.sh <dev|test>}"
HERE="$(dirname "${BASH_SOURCE[0]}")"
CF_DIR="$(dirname "$HERE")"
VARS="$CF_DIR/vars/${ENV_NAME}.yml"

command -v cf >/dev/null 2>&1 || { echo "SKIPPED/ERROR: cf CLI not installed" >&2; exit 2; }
[ -f "$VARS" ] || { echo "ERROR: vars file not found: $VARS" >&2; exit 2; }

node "$CF_DIR/validate-manifest.mjs" "$CF_DIR/manifest.yml" "$CF_DIR/manifest-frontend.yml"

echo "Deploying messaging-backend-${ENV_NAME} with rolling strategy"
cf push -f "$CF_DIR/manifest.yml" --vars-file "$VARS" --strategy rolling

echo "Deployment submitted. Running smoke checks"
bash "$HERE/smoke.sh" "$ENV_NAME"
