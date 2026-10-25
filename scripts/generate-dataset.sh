#!/usr/bin/env bash
# Generates a synthetic recipient CSV using the built jar's SyntheticDatasetGenerator.
# Usage: bash scripts/generate-dataset.sh <rows> <outFile> [dupRate] [invalidRate]
# Example: bash scripts/generate-dataset.sh 100000 .run/recipients-100k.csv 0.02 0.02
set -euo pipefail

ROWS="${1:?usage: generate-dataset.sh <rows> <outFile> [dupRate] [invalidRate]}"
OUT="${2:?usage: generate-dataset.sh <rows> <outFile> [dupRate] [invalidRate]}"
DUP="${3:-0.02}"
INV="${4:-0.02}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_JDK="/c/Users/sahukari.rao/.jdks/graalvm-jdk-21.0.7"
MAIN_CLASS="com.example.messaging.batch.SyntheticDatasetGenerator"

case "$ROWS" in ''|*[!0-9]*) echo "rows must be a positive integer" >&2; exit 2 ;; esac

JAR=""
for candidate in "$ROOT"/backend/target/*.jar; do
  case "$candidate" in *.original|*-sources.jar|*-javadoc.jar|*-tests.jar) continue ;; esac
  [ -f "$candidate" ] && JAR="$candidate"
done
[ -n "$JAR" ] || { echo "No jar in backend/target. Build first: bash scripts/mvn.sh -f backend/pom.xml -DskipTests package" >&2; exit 1; }

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then JAVA_BIN="$JAVA_HOME/bin/java"
elif [ -x "$DEFAULT_JDK/bin/java" ]; then JAVA_BIN="$DEFAULT_JDK/bin/java"
else JAVA_BIN="java"; fi

mkdir -p "$(dirname "$OUT")"
JAR_ARG="$JAR"; OUT_ARG="$OUT"
if command -v cygpath >/dev/null 2>&1; then JAR_ARG="$(cygpath -m "$JAR")"; OUT_ARG="$(cygpath -m "$(cd "$(dirname "$OUT")" && pwd)")/$(basename "$OUT")"; fi

# Spring Boot fat jar: the generator class lives under BOOT-INF/classes, so use PropertiesLauncher with loader.main.
"$JAVA_BIN" -Dloader.main="$MAIN_CLASS" -cp "$JAR_ARG" org.springframework.boot.loader.launch.PropertiesLauncher "$ROWS" "$OUT_ARG" "$DUP" "$INV"
echo "Wrote $OUT ($(wc -l <"$OUT") lines incl. header)"
