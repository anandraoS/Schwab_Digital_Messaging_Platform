# 18. Docker and Kubernetes Architecture

> [!WARNING] Execution status: NOT executed
> Docker, `kubectl`, `helm` and a cluster were not available. Dockerfiles, the Compose file, kustomize overlays and the Helm chart were reviewed statically and parsed by `scripts/validate-infra.sh` (which prints SKIPPED for absent tools). `docker build`, `docker compose up`, `kubectl apply` and `helm install` were never run.

## 18.1 Container images

| Image | File | Highlights |
|---|---|---|
| Backend | `infrastructure/docker/Dockerfile.backend` | Multi-stage: `maven:3.9.9-eclipse-temurin-21` build, then `eclipse-temurin:21.0.5_11-jre-jammy` runtime; Spring Boot **layered** jar extraction for cache-friendly layers; non-root user `10001`; `tini` as init; OCI labels `version` and `revision` from build args `VERSION`, `GIT_SHA` |
| Frontend | `infrastructure/docker/Dockerfile.frontend`, `nginx.conf` | Angular production build served by `nginx-unprivileged`; proxies `/api` to the backend |

Tags are immutable: `<version>-<git sha7>` (for example `0.1.57-3fa9c21`), never `latest`.

## 18.2 Docker Compose stack

{{diagram:compose}}

`infrastructure/compose/docker-compose.yml` declares postgres 16.4, rabbitmq 3.13.7 (management, definitions imported), redis 7.4.1, mongo 7.0.14, the backend, the frontend, prometheus v2.54.1, grafana 11.2.2 and otel-collector-contrib 0.111.0, with named volumes `pg-data`, `rabbit-data`, `mongo-data`, `prom-data`, `grafana-data`. Images are pinned to explicit tags; services have resource limits and health checks; backend and frontend run with a read-only root filesystem and dropped capabilities. The compose backend runs profile `prod-like,rabbit`, so the dev token is **disabled** there. Environment values come from `.env` (copied from the example file, with dummy `CHANGE_ME_LOCAL_ONLY` placeholders). The Compose host ports are API 18080, UI 14200, Prometheus 19090 and Grafana 13000.

## 18.3 Kubernetes architecture

{{diagram:k8s}}

### Manifests (`infrastructure/kubernetes/base`)

| File | Content (as written) |
|---|---|
| `deployment.yaml` | 2 replicas; `terminationGracePeriodSeconds: 60` (above the 30 s application drain plus a preStop sleep); `runAsNonRoot`, `readOnlyRootFilesystem`; requests `500m` CPU and `768Mi`, limits `2` CPU and `1Gi`; startup, liveness (`/actuator/health/liveness`) and readiness (`/actuator/health/readiness`) probes; Prometheus scrape annotations |
| `hpa.yaml` | HorizontalPodAutoscaler 2 to 8 replicas at 70 percent average CPU |
| `pdb.yaml` | `minAvailable: 1` |
| `service.yaml`, `ingress.yaml` | ClusterIP service; ingress with TLS |
| `configmap.yaml`, `secret.template.yaml` | Non-secret configuration; **dummy** secret template (values replaced by a secret manager in real use) |
| `networkpolicy.yaml` | Ingress only from the ingress controller; egress to PostgreSQL, RabbitMQ, Redis, MongoDB, providers and the OTel collector |
| `serviceaccount.yaml`, `servicemonitor.yaml` | Dedicated service account; Prometheus Operator scrape definition |

Overlays: `overlays/dev` (adds in-cluster dependencies in `dependencies.yaml` for experiments) and `overlays/prod-like`. A Helm chart (`infrastructure/helm/messaging-platform`) mirrors the base with `values.yaml` and `values-prod-like.yaml`.

### Scaling the right thing

The API and the consumers run in the same pods, which is simplest. If consumer saturation must not affect API latency, split them into separate Deployments by profile; scale consumers on queue depth (KEDA or a custom metrics adapter) and the API on CPU or request rate. More consumers only help while the provider rate limit allows; the token bucket still caps throughput.

### Graceful termination

Rollouts and node drains send `SIGTERM`. The application stops intake, drains in-flight deliveries for up to 30 seconds (`spring.lifecycle.timeout-per-shutdown-phase`), and unacknowledged deliveries return to the queue. The 60 second grace period leaves room for the drain and the preStop sleep that lets the endpoint drop out of the load balancer first.

## 18.4 Environments

| Env | How | Profile |
|---|---|---|
| Local, no Docker | `bash scripts/run-local.sh --wait` | `local` (the only one executed here), API on 18080, Angular dev server on 14200 |
| Local containers | Docker Compose | `prod-like,rabbit` |
| Kubernetes | kustomize or Helm | `prod-like,rabbit` |
| Cloud Foundry | `cf push` | `prod-like,rabbit,cloud` |

## Reference material

{{embed:docs/operations/deployment/README.md|title=Deployment guide (Docker Compose, environments, checklists)}}

{{embed:docs/operations/kubernetes-guide.md|title=Kubernetes guide}}

{{embed:docs/architecture/deployment-views.md|title=Deployment views}}
