-- Least-privilege role model. Passwords are injected by init.sh via psql variables (dummy values from .env).
-- NOT executed against a real PostgreSQL on the authoring machine.
-- Run as superuser connected to database "messaging":
--   psql -v migrator_pw=... -v app_pw=... -v ro_pw=... -f init.sql

-- Roles (idempotent)
DO $$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'messaging_migrator') THEN CREATE ROLE messaging_migrator LOGIN; END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'messaging_app')      THEN CREATE ROLE messaging_app LOGIN; END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'messaging_readonly') THEN CREATE ROLE messaging_readonly LOGIN; END IF;
END $$;

ALTER ROLE messaging_migrator PASSWORD :'migrator_pw';
ALTER ROLE messaging_app      PASSWORD :'app_pw';
ALTER ROLE messaging_readonly PASSWORD :'ro_pw';

-- Connection hygiene
ALTER ROLE messaging_app      CONNECTION LIMIT 40;
ALTER ROLE messaging_readonly CONNECTION LIMIT 5;
ALTER ROLE messaging_app      SET statement_timeout = '15s';
ALTER ROLE messaging_readonly SET statement_timeout = '30s';

-- Lock down defaults
REVOKE ALL ON DATABASE messaging FROM PUBLIC;
GRANT CONNECT ON DATABASE messaging TO messaging_migrator, messaging_app, messaging_readonly;

-- Schema is owned by the migrator (Flyway runs as migrator): DDL rights only there.
REVOKE ALL ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO messaging_migrator;
GRANT USAGE ON SCHEMA public TO messaging_app, messaging_readonly;

-- Objects created later by the migrator automatically grant DML to the app role and SELECT to read-only.
ALTER DEFAULT PRIVILEGES FOR ROLE messaging_migrator IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO messaging_app;
ALTER DEFAULT PRIVILEGES FOR ROLE messaging_migrator IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO messaging_app;
ALTER DEFAULT PRIVILEGES FOR ROLE messaging_migrator IN SCHEMA public
  GRANT SELECT ON TABLES TO messaging_readonly;

-- NOTE: audit_event is append-only by contract. After migrations, tighten it once the table exists:
--   REVOKE UPDATE, DELETE ON audit_event FROM messaging_app;
-- (kept as a post-migration step because the table does not exist at init time; see docs/operations/deployment)
-- NOTE: Spring Batch tables and the retention job need DELETE on message_request/audit_event(no): retention for audit
-- is run by a separate privileged job role in real deployments.
