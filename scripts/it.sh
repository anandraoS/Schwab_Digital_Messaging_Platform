#!/usr/bin/env bash
# Fast integration-test loop for local iteration: compiles, then runs only the given failsafe tests (no jar/repackage).
# Usage: bash scripts/it.sh DispatchFlowIT[,OtherIT]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bash "$ROOT/scripts/mvn.sh" -o jacoco:prepare-agent test-compile failsafe:integration-test -Dit.test="$1" -DfailIfNoSpecifiedTests=false
