#!/usr/bin/env bash
# Post-deploy smoke check. NOT executed here. Usage: smoke.sh <dev|test> [base-url]
# base-url defaults to https://messaging-backend-<env>.<apps_domain> read from vars file.
set -euo pipefail

ENV_NAME="${1:?usage: smoke.sh <dev|test> [base-url]}"
HERE="$(dirname "${BASH_SOURCE[0]}")"
VARS="$(dirname "$HERE")/vars/${ENV_NAME}.yml"

if [ -n "${2:-}" ]; then
  BASE="$2"
else
  DOMAIN="$(sed -n 's/^apps_domain:[[:space:]]*//p' "$VARS" | tr -d '\r')"
  BASE="https://messaging-backend-${ENV_NAME}.${DOMAIN}"
fi

command -v curl >/dev/null 2>&1 || { echo "ERROR: curl required" >&2; exit 2; }

check() {
  local path="$1" expected="$2" code
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "${BASE}${path}" || echo 000)"
  if [ "$code" = "$expected" ]; then echo "OK   ${path} -> ${code}"; else echo "FAIL ${path} -> ${code} (expected ${expected})"; return 1; fi
}

fail=0
check /actuator/health/liveness 200 || fail=1
check /actuator/health/readiness 200 || fail=1
check /api/v1/me 401 || fail=1      # unauthenticated must be rejected
exit "$fail"
