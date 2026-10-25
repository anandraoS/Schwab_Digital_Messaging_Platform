#!/usr/bin/env bash
# Starts the packaged backend with profile local (JDK 21 by full path, no global exports).
# Port: SERVER_PORT env (default 18080; 8080 is occupied on the dev machine). Example when 18080 is taken too:
#   SERVER_PORT=18280 bash scripts/run-local.sh
# Extra arguments are passed to Spring Boot, e.g.  bash scripts/run-local.sh --messaging.dispatch.backoff-base-ms=100
# Writes the JVM pid to .run/backend.pid (used by scripts/stop-local.sh) when started with RUN_PID_FILE=1.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA="/c/Users/sahukari.rao/.jdks/graalvm-jdk-21.0.7/bin/java"
PORT="${SERVER_PORT:-18080}"
JAR="$ROOT/backend/target/messaging-platform.jar"
if [ ! -f "$JAR" ]; then echo "Missing $JAR - build first: bash scripts/mvn.sh -DskipTests package" >&2; exit 1; fi
if [ "${RUN_PID_FILE:-0}" = "1" ]; then
  mkdir -p "$ROOT/.run"
  echo $$ >"$ROOT/.run/backend.pid"   # exec below keeps the same pid
fi
exec "$JAVA" -jar "$JAR" --spring.profiles.active=local --server.port="$PORT" "$@"
