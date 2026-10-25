# Architecture Decision Records

Binding companion: `docs/design/CONTRACT.md`. ADRs explain *why*; the contract states *what*. Changing an accepted ADR requires a new ADR that supersedes it (never a silent edit, never a silent override by an agent; see `ai-engineering/multi-agent-workflow.md`).

ADRs 0001-0012: status **Accepted** (ADR-0013 is **Proposed**), dated 2026-10-18, authored with Claude Code for this independent reference implementation. "Validation" sections point at code locations by package/class name from CONTRACT section 2; where behavior is not confirmed in this document the text says "see source".

| ADR | Title | One-line decision |
|---|---|---|
| [0001](0001-asynchronous-messaging-rabbitmq-vs-kafka.md) | Asynchronous messaging: RabbitMQ vs Kafka | RabbitMQ behind a `MessageBus` port; Kafka is the documented alternative for replay/stream workloads |
| [0002](0002-provider-abstraction.md) | Provider abstraction | Adapter per channel + strategy-based routing via `ProviderRegistry`; new provider = new bean + config |
| [0003](0003-message-lifecycle-state-machine.md) | Lifecycle / state machine | Explicit transition table in `MessageStateMachine`, CAS ownership, terminal states |
| [0004](0004-idempotency.md) | Idempotency | Four layers: API key, consumer dedupe, callback event id, provider idempotency ref |
| [0005](0005-database-postgresql-vs-mysql-oracle-and-nosql.md) | Database choice | PostgreSQL primary, Oracle-mappable SQL, Mongo only for provider diagnostics |
| [0006](0006-retry-and-dead-letter-policy.md) | Retry and dead-letter | Exponential backoff + full jitter, caps, poison threshold, operator replay |
| [0007](0007-template-versioning-and-pinning.md) | Template versioning | Pin version at accept time; accepted messages are immune to later template changes |
| [0008](0008-cloud-foundry-vs-kubernetes.md) | Cloud Foundry vs Kubernetes | Platform-neutral 12-factor app; both manifests shipped, neither executed here |
| [0009](0009-outbox-pattern.md) | Outbox pattern | Same-transaction outbox row, scheduled publisher with CAS claim |
| [0010](0010-ambiguous-outcome-and-reconciliation.md) | Ambiguous outcomes | `DELIVERY_UNKNOWN` + reconciliation lookup; no blind resend for EMAIL/SMS |
| [0011](0011-multi-tenancy-and-authorization.md) | Multi-tenancy and authz | JWT tenant claim, shared schema, 404-on-foreign-resource, role matrix |
| [0012](0012-consent-and-suppression-policy.md) | Consent and suppression | Ordered policy; fallback never bypasses consent |
| [0013](0013-priority-aging.md) | Priority aging | **Proposed, not implemented**: bounded wait for LOW/NORMAL via effective-priority promotion (spec 0001 is a worked example) |

## Template used

Context, Decision, Alternatives considered, Consequences (positive/negative/risks), Status, Validation (code and tests).

## Process rules

1. New cross-cutting decision: copy the structure above, number sequentially, add a row here.
2. Superseding: set the old ADR status to `Superseded by ADR-NNNN` and link both ways.
3. Deviations from CONTRACT go to `docs/design/DEVIATIONS.md` (append only) and must reference the ADR.
