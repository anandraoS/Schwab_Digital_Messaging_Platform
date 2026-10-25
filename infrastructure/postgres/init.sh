#!/bin/sh
# Docker entrypoint init hook: applies init.sql with passwords from environment (dummy values from .env).
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v migrator_pw="$MESSAGING_MIGRATOR_DB_PASSWORD" \
  -v app_pw="$MESSAGING_APP_DB_PASSWORD" \
  -v ro_pw="$MESSAGING_READONLY_DB_PASSWORD" \
  -f /opt/init/init.sql
