#!/usr/bin/env bash
# Runs every infrastructure validation that is possible on this machine. Honest by construction:
#   PASS    = the check ran and succeeded
#   FAIL    = the check ran and failed (exit code 1)
#   SKIPPED = the required tool is absent; the check did NOT run (never counted as success)
# Usage: bash scripts/validate-infra.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 2
PASS=0; FAIL=0; SKIP=0
SKIPPED_LIST=()

p() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
pass() { echo "PASS    $1"; PASS=$((PASS+1)); }
fail() { echo "FAIL    $1"; FAIL=$((FAIL+1)); }
skip() { echo "SKIPPED $1 -- $2 (NOT executed)"; SKIP=$((SKIP+1)); SKIPPED_LIST+=("$1"); }
have() { command -v "$1" >/dev/null 2>&1; }

echo "== 1. bash -n on all shell scripts =="
SH_FAIL=0; SH_COUNT=0
while IFS= read -r f; do
  SH_COUNT=$((SH_COUNT+1))
  if ! bash -n "$f" 2>/tmp/validate-infra-bash.err; then echo "  syntax error in $f"; cat /tmp/validate-infra-bash.err; SH_FAIL=1; fi
done < <(find scripts cloud-foundry infrastructure performance -name '*.sh' -not -path '*/node_modules/*' 2>/dev/null | sort)
if [ "$SH_FAIL" = 0 ] && [ "$SH_COUNT" -gt 0 ]; then pass "bash -n on $SH_COUNT scripts"; else fail "bash -n ($SH_COUNT scripts)"; fi

echo "== 2. YAML/JSON parse (in-repo yaml-lite parser; Helm templates excluded) =="
VF="$(p "$ROOT/scripts/lib/validate-files.mjs")"
if have node; then
  if node "$VF" infrastructure observability cloud-foundry .github bamboo-specs >/tmp/validate-infra-yaml.out 2>&1; then pass "YAML/JSON syntax parse ($(tail -n 1 /tmp/validate-infra-yaml.out))"; else grep '^FAIL' /tmp/validate-infra-yaml.out; fail "YAML/JSON syntax parse"; fi
  if [ -f Jenkinsfile ]; then pass "Jenkinsfile present (Groovy not parsed here; see SKIPPED jenkins-linter)"; else fail "Jenkinsfile missing"; fi
else
  skip "YAML/JSON parse" "node not installed"
fi

echo "== 3. Cloud Foundry manifest validator =="
if have node; then
  if node "$(p "$ROOT/cloud-foundry/validate-manifest.mjs")"; then pass "cloud-foundry/validate-manifest.mjs"; else fail "cloud-foundry/validate-manifest.mjs"; fi
else
  skip "CF manifest validator" "node not installed"
fi

echo "== 4. Static repo checks (alerts, metric names, compose/K8s heuristics, helm balance, JS syntax, secret scan) =="
if have node; then
  if node --experimental-vm-modules --no-warnings "$(p "$ROOT/scripts/lib/static-checks.mjs")" "$(p "$ROOT")"; then pass "scripts/lib/static-checks.mjs (all sub-checks)"; else fail "scripts/lib/static-checks.mjs (see FAIL lines above)"; fi
else
  skip "static checks" "node not installed"
fi

echo "== 5. Python syntax of make-zip.py =="
if have python; then
  if python -I -c "import ast,sys; ast.parse(open(sys.argv[1],encoding='utf-8').read())" "$(p "$ROOT/scripts/make-zip.py")"; then pass "python -I ast.parse scripts/make-zip.py"; else fail "make-zip.py syntax"; fi
else
  skip "make-zip.py syntax" "python not installed"
fi

echo "== 6. Tool-dependent checks (skipped when the tool is absent) =="
if have docker; then
  if docker compose --env-file .env.example -f infrastructure/compose/docker-compose.yml config -q; then pass "docker compose config -q"; else fail "docker compose config -q"; fi
else skip "docker compose config" "docker not installed"; fi

if have kubectl; then
  if kubectl kustomize infrastructure/kubernetes/overlays/dev >/dev/null && kubectl kustomize infrastructure/kubernetes/overlays/prod-like >/dev/null; then pass "kubectl kustomize dev + prod-like"; else fail "kubectl kustomize"; fi
else skip "kustomize build" "kubectl not installed"; fi

if have kubeconform && have kubectl; then
  if kubectl kustomize infrastructure/kubernetes/overlays/dev | kubeconform -strict -ignore-missing-schemas -summary; then pass "kubeconform on dev overlay"; else fail "kubeconform"; fi
else skip "kubeconform" "kubeconform and/or kubectl not installed"; fi

if have helm; then
  if helm lint infrastructure/helm/messaging-platform && helm lint infrastructure/helm/messaging-platform -f infrastructure/helm/messaging-platform/values-prod-like.yaml && helm template t infrastructure/helm/messaging-platform >/dev/null; then pass "helm lint + template"; else fail "helm lint/template"; fi
else skip "helm lint/template" "helm not installed"; fi

if have shellcheck; then
  if find scripts cloud-foundry -name '*.sh' -print0 | xargs -0 shellcheck -S warning; then pass "shellcheck"; else fail "shellcheck"; fi
else skip "shellcheck" "shellcheck not installed"; fi

if have promtool; then
  if promtool check rules observability/prometheus/alerts.yml observability/prometheus/recording-rules.yml && promtool check config observability/prometheus/prometheus.yml; then pass "promtool check rules/config"; else fail "promtool"; fi
else skip "promtool check rules/config" "promtool not installed"; fi

if have hadolint; then
  if hadolint infrastructure/docker/Dockerfile.backend infrastructure/docker/Dockerfile.frontend; then pass "hadolint"; else fail "hadolint"; fi
else skip "hadolint (Dockerfile lint)" "hadolint not installed"; fi

# Always skipped here: they need external systems.
skip "docker build (backend/frontend images)" "no Docker daemon"
skip "docker compose up + healthchecks" "no Docker daemon"
skip "Trivy image scan" "no Docker/trivy"
skip "k6 load scripts" "k6 not installed"
skip "cf push / CF smoke" "no Cloud Foundry foundation"
skip "GitHub Actions / Jenkins / Bamboo pipeline execution" "no CI runner; YAML syntax only"
skip "Jenkinsfile Groovy lint" "no Jenkins linter"

echo
echo "== SUMMARY =="
echo "PASS=$PASS FAIL=$FAIL SKIPPED=$SKIP"
if [ "$SKIP" -gt 0 ]; then echo "Skipped (not executed):"; for s in "${SKIPPED_LIST[@]}"; do echo "  - $s"; done; fi
[ "$FAIL" -eq 0 ] || exit 1
