# Deployment Guide (Docker Compose, environments, checklists)

**Execution status: NOT executed.** Docker is not installed on the authoring machine. The compose file, Dockerfiles, nginx
config and init scripts were reviewed statically and parsed (`bash scripts/validate-infra.sh`); `docker compose config`,
`docker build` and `docker compose up` were SKIPPED. Treat every command below as untested until run on a Docker host.

## Environments
| Env | How | Profile | Notes |
|---|---|---|---|
| Local, no Docker | `bash scripts/run-local.sh --wait` | `local` (H2, in-memory bus/Mongo, dev token) | port 18080; Angular dev server 14200 |
| Local containers | docker compose (below) | `prod-like,rabbit` | real PostgreSQL/RabbitMQ/Redis/Mongo; dev token disabled, so `/dev/token` and the k6 scripts need a real JWT issuer or a local-profile backend |
| Kubernetes | `docs/operations/kubernetes-guide.md` | `prod-like,rabbit` | |
| Cloud Foundry | `docs/operations/cloud-foundry-guide.md` | `prod-like,rabbit,cloud` | |

Profile `rabbit` is added because CONTRACT section 2 names it for `RabbitTopologyConfig`; drop it if the backend only keys off `prod-like`.

## Compose quick start (NOT executed)
```bash
cp .env.example .env          # all values are dummy placeholders (CHANGE_ME_LOCAL_ONLY...)
docker compose --env-file .env -f infrastructure/compose/docker-compose.yml up -d --build
docker compose --env-file .env -f infrastructure/compose/docker-compose.yml ps
# optional load profile
docker compose --env-file .env -f infrastructure/compose/docker-compose.yml --profile load run --rm k6
```
Endpoints: API `http://localhost:18080`, UI `http://localhost:14200`, RabbitMQ UI `http://localhost:15672` (loopback), Prometheus `http://localhost:19090`, Grafana `http://localhost:13000`.
For debugging ports use `infrastructure/compose/docker-compose.override.example.yml` (copy to `docker-compose.override.yml`, add a second `-f`).

## What the stack contains
postgres:16.4, rabbitmq:3.13.7-management (definitions imported), redis:7.4.1, mongo:7.0.14, backend, frontend (nginx-unprivileged), prometheus v2.54.1, grafana 11.2.2, otel-collector-contrib 0.111.0, k6 0.54.0 (profile `load`).
All images are pinned to explicit tags (digest pinning recommended for production). Services have resource limits and healthchecks; backend and frontend run with a read-only root filesystem, dropped capabilities and `no-new-privileges`.
Networks: `data` (internal, no external route), `edge`, `observability`. Volumes persist database, broker and metrics data.

## Data-store initialization
| Component | File | Behavior |
|---|---|---|
| PostgreSQL | `infrastructure/postgres/init.sh` + `init.sql` | creates `messaging_migrator` (DDL, runs Flyway), `messaging_app` (DML only), `messaging_readonly`; passwords from env |
| RabbitMQ | `infrastructure/rabbitmq/definitions.json` + `rabbitmq.conf` | vhost `messaging`; exchanges `messaging.dispatch`, `messaging.retry`, `messaging.dlx`; priority queue `messaging.dispatch.q` (`x-max-priority: 9`), `messaging.dlq`, retry delay queues 5s/30s/2m/10m that dead-letter back to dispatch; policies for backpressure and retention |
| MongoDB | `infrastructure/mongo/init-mongo.js` | app user, `provider_diagnostics` indexes, 30-day TTL on `receivedAt` |

Caveats to verify on first run: RabbitMQ definitions contain no users (the default user from env is created and, because `default_vhost = messaging`, gets permissions on that vhost); the Flyway user wiring uses `SPRING_FLYWAY_USER/PASSWORD`; `audit_event` append-only grants must be tightened after migrations (`REVOKE UPDATE, DELETE ... FROM messaging_app`).

## Backend container
Multi-stage build (Maven 3.9 + Temurin 21 builder, Temurin 21 JRE runtime), layered Spring Boot jar, non-root uid 10001, tini as PID 1 so SIGTERM reaches the JVM for graceful shutdown, `HEALTHCHECK` on `/actuator/health/liveness`, flags `-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError`. Assumes the Boot jar supports `-Djarmode=tools` extraction (Boot 3.3+; project uses 3.5.16).

## Frontend container
Node 22 build (`ng build --configuration production`) then `nginxinc/nginx-unprivileged` on port 8080 with CSP, `X-Frame-Options`, `nosniff`, `Referrer-Policy`, `Permissions-Policy`, SPA fallback, immutable caching for hashed assets, and `/api/` reverse proxy to `backend:18080`. HSTS is commented out: enable it where TLS terminates in front.

## Pre-deployment checklist
- [ ] Image tags are immutable CI versions (`docs/operations/ci-cd.md`), scanned with Trivy
- [ ] Real secrets come from a secret manager; none in `.env` committed (it is git-ignored)
- [ ] `messaging.security.dev-token-enabled=false` and issuer URI configured
- [ ] Flyway migrations backward compatible with the previous release (rolling/blue-green overlap)
- [ ] Alerts and dashboards loaded; runbooks reachable (`docs/operations/runbooks/`)
- [ ] Rollback path rehearsed (compose: previous tag; K8s: `rollout undo`; CF: revisions)
