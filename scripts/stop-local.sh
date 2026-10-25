#!/usr/bin/env bash
# Stops the backend started by scripts/run-local.sh (graceful SIGTERM first). Never uses rm; truncates the pid file instead.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_FILE="$ROOT/.run/backend.pid"

if [ ! -s "$PID_FILE" ]; then echo "No pid file (nothing to stop)."; exit 0; fi
PID="$(cat "$PID_FILE")"
if ! kill -0 "$PID" 2>/dev/null; then echo "Process $PID not running."; : >"$PID_FILE"; exit 0; fi

echo "Sending SIGTERM to $PID (graceful shutdown, up to 35s)"
kill "$PID" 2>/dev/null || true
for _ in $(seq 1 35); do
  if ! kill -0 "$PID" 2>/dev/null; then echo "Stopped."; : >"$PID_FILE"; exit 0; fi
  sleep 1
done
echo "Still running; sending SIGKILL" >&2
kill -9 "$PID" 2>/dev/null || true
# Git Bash on Windows: also try the Windows process table (the JVM is a native process)
if command -v taskkill >/dev/null 2>&1; then taskkill //PID "$PID" //F >/dev/null 2>&1 || true; fi
: >"$PID_FILE"
echo "Killed."
