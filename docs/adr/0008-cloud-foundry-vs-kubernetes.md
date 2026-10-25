# ADR-0008: Cloud Foundry vs Kubernetes

Status: Accepted (2026-10-18)

## Context
Enterprises run Java services on either Cloud Foundry (buildpack push, platform-managed routing/services) or Kubernetes (container, declarative manifests). The reference must demonstrate that the application is portable and show operational thinking for both. **No Docker, kubectl, Helm or `cf` CLI is available in the authoring environment**, so all deploy assets are statically validated only.

## Decision
- Build a **platform-neutral 12-factor application**: config via env vars, `/actuator/health/liveness|readiness`, SIGTERM-triggered graceful shutdown (30 s `SmartLifecycle`), stateless instances, logs to stdout, backing services via URLs.
- Ship **both** deployment descriptors: `cloud-foundry/manifest.yml` (+ per-environment var files) and `infrastructure/{docker,compose,kubernetes,helm}`. Neither is executed here.
- The Kubernetes path is the primary documented target for the reference (HPA, PDB, NetworkPolicy, probes); CF is documented as an equivalent for teams on that platform.
- Platform-specific code is avoided: no Spring Cloud Kubernetes, no `VCAP_SERVICES` parsing in business code (CF binds are mapped to the same env vars in the manifest).

| Concern | Cloud Foundry | Kubernetes |
|---|---|---|
| Packaging | Java buildpack, JAR | OCI image (multi-stage, non-root) |
| Scaling | `cf scale`, autoscaler service | HPA on CPU/custom queue-depth metric (KEDA optional) |
| Zero-downtime | blue-green via route mapping / rolling `--strategy rolling` | rolling update + PDB + readiness |
| Health | `health-check-type: http` on readiness | liveness/readiness/startup probes |
| Secrets | user-provided service / CredHub | Secret + external secrets operator (see secrets-management) |
| Network policy | Container networking policies | NetworkPolicy |
| Batch (bulk jobs) | same app instances; restartable | same; optional separate Deployment with `bulk` profile |

## Alternatives considered
| Option | Why not |
|---|---|
| Kubernetes only | Ignores teams on CF; also acceptable but less illustrative |
| CF only | Less flexibility for sidecars, KEDA, policy tooling |
| Serverless functions | Long-lived consumers and connection pools fit poorly |

## Consequences
- Positive: portability; one JAR/image; same runbooks.
- Negative: two descriptor sets to keep in sync (drift risk, mitigated by a checklist item in `release-readiness-checklist.md`).
- Honest limits: probes, resource requests, HPA targets are starting points that have **not** been load-tested on a cluster.

## Validation
- Static: YAML parse/lint of manifests (see `docs/validation/VALIDATION_REPORT.md` when generated). Runtime: NOT executed.
- Graceful shutdown is testable in-JVM (`DispatchConsumer` lifecycle test, see source).
