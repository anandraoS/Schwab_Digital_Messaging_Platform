# Deployment Views

**Honesty note**: none of the container, Kubernetes, Helm or Cloud Foundry assets below were executed in the authoring environment (no Docker/kubectl/cf). Only the `local` profile (single JVM, H2, in-memory bus) was run. These diagrams describe intended topology.

## 1. Local (executed): single JVM
```mermaid
flowchart LR
  B[Browser :14200 Angular dev server] -- proxy --> A
  subgraph JVM[Spring Boot :18080 profile local]
    A[API + dispatch consumers + scheduler + batch]
    H2[(H2 MODE=PostgreSQL)]
    MB[InMemoryMessageBus]
    MC[in-memory coordination]
    MG[in-memory Mongo mongo-java-server]
    SIM[Simulated providers]
  end
  A --- H2
  A --- MB
  A --- MC
  A --- MG
  A --- SIM
```

## 2. Prod-like on Kubernetes (not executed)
```mermaid
flowchart TB
  U[Clients and console] --> ING[Ingress / TLS termination]
  PRV[Providers webhooks] --> ING
  ING --> SVC[Service messaging-api]
  subgraph NS[namespace messaging]
    SVC --> D1[Deployment api+consumer pods x N, HPA, PDB]
    D2[Deployment bulk-worker pods, profile bulk, optional]
    NP[NetworkPolicy: ingress from ingress-controller only; egress to db, rabbit, redis, mongo, providers, otel]
  end
  D1 --> PG[(PostgreSQL primary + replica)]
  D1 --> RQ[(RabbitMQ quorum queues, 3 nodes)]
  D1 --> RD[(Redis)]
  D1 --> MGD[(MongoDB replica set)]
  D1 --> OTEL[OTel collector]
  PROM[Prometheus] -- scrape /actuator/prometheus --> D1
  PROM --> AM[Alertmanager]
  GR[Grafana] --> PROM
```
Notes: split API and consumers into separate Deployments if consumer saturation should not affect API latency (profile-controlled consumers: see source `messaging.*` config); scale consumers on queue depth (KEDA or custom metric adapter, an option), API on CPU/RPS. Single replica count assumptions in `infrastructure/helm` values are starting points.

## 3. Cloud Foundry (not executed)
```mermaid
flowchart LR
  GR[GoRouter] --> APP[messaging-platform app instances]
  APP --> SPG[(PostgreSQL service instance)]
  APP --> SRM[(RabbitMQ service instance)]
  APP --> SRD[(Redis service instance)]
  APP --> SMG[(MongoDB service instance)]
  APP --> CHD[CredHub / user-provided secrets]
```
`cloud-foundry/manifest.yml`: health check `http` on `/actuator/health/readiness`, env vars map bound services to `SPRING_DATASOURCE_URL` etc.; rolling deploy via `cf push --strategy rolling` (not executed).

## 4. Network and trust zones
| Zone | Components | Rules |
|---|---|---|
| Public edge | Ingress / GoRouter | TLS, WAF/rate limit (prod-vs-reference gap), only `/api/**`, `/actuator/health/**` exposed; `/actuator/prometheus` internal only |
| App | API/consumer pods | No inbound from data zone; egress allow-list |
| Data | PostgreSQL, Rabbit, Redis, Mongo | Inbound only from app zone; credentials from secret store |
| Provider egress | Providers (simulated here) | Egress via allow-list/proxy; callbacks arrive via edge and are HMAC-verified |

## 5. Environments
| Env | Profile | Notes |
|---|---|---|
| dev laptop | `local` | all in-memory, dev token enabled |
| CI | `test` | deterministic, H2; `-Pcontainers` optional |
| staging/prod | `prod-like` + `rabbit` | PostgreSQL, Redis, Mongo, issuer-uri, no dev endpoints |
