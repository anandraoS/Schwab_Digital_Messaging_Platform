# 26. Limitations and Future Enhancements

This chapter is deliberately blunt. A reference implementation earns trust by being exact about its boundaries.

## 26.1 What is simulated

| Real-world element | What stands in for it here | Consequence |
|---|---|---|
| Email, SMS and push vendors | `SimulatedEmailProvider`, `SimulatedSmsProvider`, `SimulatedPushProvider`, `AcmeSmsProvider` (all extend `SimulatedProviderBase`) | Vendor behaviour is a model: a compliant vendor that deduplicates on the idempotency reference and offers `lookup`. Real vendors may not, which is exactly the residual risk named in ADR-0010 |
| Vendor webhooks | `CallbackEmitter` posts signed callbacks to the platform itself (`POST /dev/simulator/callbacks/emit`) | Realistic in format and signature, not in timing or failure distribution |
| Provider faults | `FaultInjector` modes with probabilities | Deterministic and random faults, not real network behaviour |
| Identity provider | `POST /dev/token` mints RS256 JWTs from an in-memory key (profiles `local`, `test` only) | No real IdP, no token revocation, no key rotation exercise |
| RabbitMQ | `InMemoryMessageBus` (bounded priority queue) in `local` and `test` | Same semantics, no network partitions, no persistence, no real Rabbit priority behaviour |
| Redis | In-memory coordination | The Lua token bucket and Lettuce adapter exist but did not run against a real Redis |
| MongoDB | `de.bwaldvogel:mongo-java-server` in-memory | Diagnostics store logic is tested; real Mongo operational behaviour is not |
| PostgreSQL | H2 2.x in `MODE=PostgreSQL` | Portable SQL runs; query plans, lock behaviour, vacuum, replicas and failover are not exercised |
| Customers and addresses | Synthetic: `recipient001@example.test`, `+1555010xxxx`, `SYN-0000001` | No real data anywhere |

## 26.2 What was not executed

The honest list, repeated from the evidence legend. Every item below exists as a file in the repository and was reviewed or parsed, **not run**.

| Asset | What was done | What was not |
|---|---|---|
| Docker images and Compose stack | Dockerfiles and compose parsed by `scripts/validate-infra.sh` | `docker build`, `docker compose config`, `docker compose up` |
| Kubernetes manifests and Helm chart | YAML parsed and structurally checked | `kubectl apply`, `kubeconform`, `helm lint`, `helm install`, any cluster |
| Cloud Foundry manifest and scripts | `validate-manifest.mjs`, `bash -n` on scripts | `cf push`, bindings, routes, rolling deploy, blue-green |
| CI pipelines (GitHub Actions, Jenkins, Bamboo) | YAML parsed locally; Jenkinsfile not linted | Any pipeline run, Trivy, SBOM, OWASP dependency-check, npm audit in CI |
| k6 scripts (8) | Authored | Execution (k6 not installed); no k6 number exists |
| Prometheus rules and Grafana dashboards | Parsed for syntax and structure; names cross-checked against the meter catalogue | `promtool` evaluation; dashboards never rendered; nothing scraped from a running backend by these files |
| RabbitMQ adapter | Pure parts and topology versus `definitions.json` | `RabbitMessageBusContainerIT` (needs Docker), any broker run |
| Testcontainers tests (`-Pcontainers`) | Compile | Run |
| Real PostgreSQL, Redis, MongoDB | Not available | Any run |
| Oracle reference DDL | Written (`backend/src/main/resources/db/oracle/`) | Executed on Oracle; V200 additions were not mirrored into it |
| Angular console in a real browser against a real deployment | Unit tests and build per the validation report; `tests/end-to-end/ui-smoke.md` is a manual script | Automated browser end-to-end |

What **was** executed on the authoring machine is recorded with exact commands in `docs/validation/`. As of this build, the executed end-to-end HTTP run (`docs/validation/e2e-run-output.txt`, reproduced in Appendix E) shows **254 checks passed, 0 failed, 0 skipped in 267.9 s** against the packaged jar on profile `local` (H2, in-memory bus, in-memory Mongo, simulators, Windows 11, JDK 21); the consolidated `docs/validation/VALIDATION_REPORT.md` is generated at the end of the project from real outputs. If a report is absent or silent on an item, treat the item as not executed. The end-to-end run, like every `local` run, says nothing about PostgreSQL, RabbitMQ, Redis or MongoDB behaviour.

## 26.3 Known gaps and technical debt (recorded in the repository)

| Area | Gap | Source |
|---|---|---|
| Frontend | The history page's `HistoryEntry` model expects a flat shape; the API returns `TimelineEntry` with an object `detail`. The mapping change was recorded as still needed | DEVIATIONS, OpenAPI and frontend reconciliation |
| Frontend | `GET /api/v1/me` is not called (roles and tenant are read from the dev JWT for UX gating); diagnostics are not surfaced in the UI because raw payloads could contain content | DEVIATIONS |
| Retention | The `replay_guard` table is not purged by the callback module; the retention job deletes rows older than `replay-window` | DEVIATIONS item 8 |
| Bulk | Jobs run on the node that accepted the request (single-node assumption for stop and orphan recovery); rows rejected inside a rolled-back chunk are not individually retained; duplicate-reference winner is non-deterministic with several workers | DEVIATIONS, bulk section |
| Schema | `V200` (row ledger, legal hold) is not in the Oracle reference DDL | DEVIATIONS |
| Specs | `callback-flow.md` and `specs/0002-new-provider-adapter.md` still describe the earlier plan; `callbacks-normalization.md` is current | DEVIATIONS item 7 |
| Rabbit | Only the total ready count is available for `/ops/queues` (reported under NORMAL); delays are rounded to TTL tiers | DEVIATIONS item 10 |
| Cloud Foundry | The backend POM must include java-cfenv for automatic `VCAP_SERVICES` binding in the `cloud` profile | `docs/operations/cloud-foundry-guide.md` |
| CI | Profiles `static-analysis`, `security-scan`, `containers` must exist in the POM; no Checkstyle or SpotBugs configuration files; third-party actions tag-pinned, not SHA-pinned; jar name must match `finalName` | `docs/operations/ci-cd.md` |
| Observability | Alert thresholds and SLOs are illustrative; whether `messaging.bulk.progress` is a fraction or a count must be confirmed against `BulkJobStalled` | `docs/operations/observability.md` |
| Redis outage | Across several instances the token bucket may over-admit slightly while falling back to per-instance in-memory buckets | Reliability scenario 7 |
| Ordering | No global or per-recipient ordering guarantee; callbacks may be out of order | Delivery semantics |
| Duplicates | Still possible: provider ignores the idempotency reference; operator replays a delivered message; client reuses a different key; key expiry then reuse | Delivery semantics |

## 26.4 Not production-ready: what production would add

`docs/security/prod-vs-reference.md` is the authoritative list. In summary: a real identity provider and token lifecycle, row-level security or schema-per-tenant, mutual TLS between services, a WAF and edge rate limiting, a secret manager with rotation, hardened and scanned images with signed provenance, backup and disaster-recovery design that has been exercised, penetration testing, a compliance review of the consent policy, and real vendor contracts with their quotas, bounce semantics and webhook specifications.

## 26.5 Future enhancements

| Enhancement | What it adds | Where it hooks in |
|---|---|---|
| Priority aging | Starvation protection for LOW traffic | `specs/0001-priority-aging.md`; the bus and `ScheduledDispatchPoller` |
| Kafka event stream sink | Analytics and replay of the event history without changing the transactional path | CDC or outbox consumer beside RabbitMQ (ADR-0001, HLD section 9) |
| CDC-based outbox | Removes the polling interval | Debezium reading `outbox_event` |
| Split consumers from API | Independent scaling and failure domains | Profiles controlling consumers and schedulers; separate Kubernetes Deployments |
| Autoscaling on queue depth | Scale on what actually saturates | KEDA or a custom metrics adapter |
| Row-level security | Defence in depth for tenant isolation | PostgreSQL RLS plus the tenant claim |
| Per-tenant quotas and fair queuing | Prevent one tenant from crowding others | `RateLimitFilter`, bus topology |
| Multi-region active/passive | Disaster recovery | Replicated database, regional brokers; idempotency keys already make client retry across regions safe |
| Real provider adapters | Production delivery | New `ChannelProvider` beans following the worked Acme example |
| Template canary rollout | Reduce blast radius of a bad template | Percentage-based version selection at pin time |
| Delivery analytics | Open and click funnels, deliverability dashboards | Engagement events already stored in `delivery_event` |
| Chaos and game days | Prove runbooks against real incidents | `tests/failure-recovery/`, runbooks |

## 26.6 How to keep this document honest

When anything changes: update `DEVIATIONS.md` first, regenerate the validation report from real outputs, rebuild this PDF with `npm run pdf1` from `tools/pdf`, and re-run `node verify-pdf.mjs`. The rule from the build contract applies to every document: **never claim something ran unless it ran**.
