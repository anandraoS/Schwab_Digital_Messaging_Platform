#!/usr/bin/env bash
# Runs Maven for the backend module with the project's pinned JDK 21 and Maven 3.9.12 (no global exports).
# Usage: bash scripts/mvn.sh <maven args>   e.g.  bash scripts/mvn.sh clean verify
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MVN="/c/Users/sahukari.rao/.m2/wrapper/dists/apache-maven-3.9.12-bin/5nmfsn99br87k5d4ajlekdq10k/apache-maven-3.9.12/bin/mvn"
JAVA_HOME=/c/Users/sahukari.rao/.jdks/graalvm-jdk-21.0.7 exec "$MVN" -B -f "$ROOT/backend/pom.xml" "$@"
