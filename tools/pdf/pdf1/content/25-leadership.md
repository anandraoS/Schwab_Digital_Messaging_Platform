# 25. Technical-Leadership Artifacts

## 25.1 What a technical lead produces besides code

Leading a platform like this is mostly **making decisions legible**: why RabbitMQ rather than Kafka, why the outbox, why 202 for a policy rejection. The artifacts in this chapter are the written form of that leadership: decision records, a delivery plan, a risk register, review practice, mentoring and incident learning. Each is a real file in the repository, and each links back to evidence in code and tests.

| Artifact | Repository location | Purpose |
|---|---|---|
| Architecture decision records | `docs/adr/` | Record why, so decisions are not re-litigated or silently reversed |
| Build contract and deviations | `docs/design/CONTRACT.md`, `DEVIATIONS.md` | One source of truth; every divergence recorded, append only |
| PRD, feature spec, acceptance criteria | `docs/leadership/` | What and for whom, testable |
| HLD and LLD | `docs/leadership/hld.md`, `lld.md` | Structure and class level design |
| Sprint plan | `docs/leadership/sprint-task-breakdown.md` | Delivery order and dependencies |
| Risk register | `docs/leadership/risk-register.md` | Scored risks with owners and mitigations |
| Test strategy and PR checklist | `docs/leadership/test-strategy.md`, `pr-checklist.md` | Quality gates |
| Review scenarios and mentoring | `docs/leadership/code-review-scenarios.md`, `mentoring-examples.md` | Teaching by realistic case |
| Post-incident review template | `docs/operations/post-incident-review-template.md` | Blameless learning |
| Specs | `specs/` | Spec-driven change |

## 25.2 ADR summary

All twelve ADRs are **Accepted**, dated 2026-10-18. Changing an accepted ADR requires a new ADR that supersedes it; a silent edit or a silent override by an agent is a process violation. The full text of each ADR appears in the chapter named in the last column.

| ADR | Title | Decision | Main trade-off accepted | Chapter |
|---|---|---|---|---|
| 0001 | Asynchronous messaging | RabbitMQ behind a `MessageBus` port; Kafka documented for replay and stream workloads | Weaker replay and fan-out than Kafka, simpler operations and priorities | 7 |
| 0002 | Provider abstraction | Adapter per channel plus strategy routing through `ProviderRegistry` | An abstraction layer to maintain; lowest common denominator risk | 8 |
| 0003 | Lifecycle state machine | Explicit transition table, CAS ownership, terminal states | Every new behaviour must fit the table | 6 |
| 0004 | Idempotency | Four layers: API key, consumer dedupe, callback event id, provider reference | More tables and code paths than a single check | 5 |
| 0005 | Database | PostgreSQL primary, Oracle-mappable portable SQL, Mongo only for diagnostics | No PostgreSQL-specific features (partial indexes, JSONB) | 15 |
| 0006 | Retry and dead letter | Exponential backoff with full jitter, caps, poison threshold, operator replay | Slower recovery of some messages for safety | 13 |
| 0007 | Template versioning | Pin version at accept time | Rendering twice (check at accept, render at dispatch) | 10 |
| 0008 | Cloud Foundry versus Kubernetes | Platform-neutral twelve-factor app; both manifests shipped | Two sets of deployment assets, neither executed here | 17 |
| 0009 | Outbox | Same-transaction outbox row, scheduled publisher with CAS claim | Publication latency up to the poll interval (200 ms) | 7 |
| 0010 | Ambiguous outcomes | `DELIVERY_UNKNOWN` plus reconciliation; no blind resend for EMAIL and SMS | Some messages wait for evidence | 12 |
| 0011 | Multi-tenancy and authz | JWT tenant claim, shared schema, 404 on foreign resource, role matrix | Shared schema needs row-level security for production | 16 |
| 0012 | Consent and suppression | Ordered policy; fallback never bypasses consent | Policy runs twice (accept and dispatch) | 11 |

### Template of an ADR

Context, Decision, Alternatives considered, Consequences (positive, negative, risks), Status, Validation (the code and tests that confirm it). The "Validation" section is what stops an ADR from being aspirational: each one points at classes and tests by name.

## 25.3 Decisions that implementation changed

Leadership includes updating the record honestly when reality teaches something. The deviations file shows decisions refined by implementation, each recorded rather than hidden:

| Original expectation | Implemented | Why |
|---|---|---|
| Policy rejection might be a 4xx `POLICY_SUPPRESSED` | 202 with `status=REJECTED` | Idempotent replay and audit stay consistent |
| Replayed callback is 409 `REPLAYED_EVENT` | 200 `replayed` | The provider must stop retrying |
| Consumer claims then checks | Checks first, claim is one transaction | A rate-limit requeue must not consume an attempt or hit the dedupe table |
| Circuit permit taken at call time | Reserved before the claim, released if unused | A recovering provider must not burn attempt budgets |
| Callback duplicate recorded as a `DUPLICATE` row | No second row | The unique `(provider_id, provider_event_id)` constraint forbids it |
| Gauges bound directly | Strong references kept | Micrometer holds gauge state weakly; gauges turned `NaN` |

## 25.4 Sprint plan

The example plan is three two-week sprints for about five engineers plus QA. Estimates are relative story points, not measurements.

{{diagram:sprintPoints}}

| Sprint | Goal | Demo |
|---|---|---|
| 1 Walking skeleton and safe acceptance | Submit, persist, queue, dispatch to a simulator, see status | End-to-end happy path locally |
| 2 Reliability | Failures are safe | Fault injection (timeout, outage, duplicate callbacks) with correct states |
| 3 Scale, operability, UX | Bulk, console, observability, deployment assets, docs | Console walk-through; static validation of assets |

Working agreements: trunk-based development with short-lived branches; pull requests under about 400 lines where possible; every PR uses `.github/pull_request_template.md`; security-sensitive PRs need an independent reviewer (`.github/CODEOWNERS`); an ADR for every cross-cutting decision.

## 25.5 Risk register highlights

{{diagram:riskScores}}

Risks are scored likelihood times impact (1 to 5 each), with an owner role and a status. The register is reviewed at each sprint planning. Several risks are honest about what is **open** in this repository: R5 (H2 versus PostgreSQL divergence; container tests not executed here), R12 (runbooks untested), R13 (capacity assumptions; k6 not executed) and R16 (deployment assets never executed, accepted for a reference implementation).

## 25.6 Review examples

Review is where leadership scales. Two collections are included in full below. **Code review scenarios** (CR-1 to CR-6) each state the problem, the investigation, the solution, the trade-off and the regression test. The **AI code review** examples show ten defect classes that AI-generated code commonly introduces in this domain.

| Defect class | Example | Invariant protected |
|---|---|---|
| Retry on timeout "to improve reliability" | CR-1, AI example 4 | Never blind-retry an uncertain outcome |
| Status written without compare-and-set | CR-2, AI examples 1 and 3 | CAS through the state machine |
| `findById` without tenant | CR-3, AI example 6 | Tenant isolation |
| Unbounded executor | CR-4, AI example 7 | Everything bounded |
| N+1 in a list endpoint | CR-5, AI example 5 | Query count bounded |
| Missing idempotency | AI example 2 | One effect per intent |
| Resource leak | AI example 8 | Pool and stream hygiene |
| Insufficient tests | AI example 9 | Tests prove the invariant |
| Sensitive data in logs | AI example 10 | Data minimisation |

## Reference material

{{embed:docs/adr/README.md|title=ADR index and process}}

{{embed:docs/leadership/sprint-task-breakdown.md|title=Sprint task breakdown}}

{{embed:docs/leadership/risk-register.md|title=Dependency and risk register}}

{{embed:docs/leadership/code-review-scenarios.md|title=Code review scenarios}}

{{embed:docs/leadership/mentoring-examples.md|title=Mentoring examples}}

{{embed:ai-engineering/ai-code-review/README.md|title=AI code review: worked examples}}

{{embed:ai-engineering/ai-code-review/01-incorrect-state-transition.md|title=AI review example 1: Incorrect state transition}}

{{embed:ai-engineering/ai-code-review/02-missing-idempotency.md|title=AI review example 2: Missing idempotency}}

{{embed:ai-engineering/ai-code-review/03-race-condition.md|title=AI review example 3: Race condition}}

{{embed:ai-engineering/ai-code-review/04-unsafe-retry.md|title=AI review example 4: Unsafe retry}}

{{embed:ai-engineering/ai-code-review/05-n-plus-one.md|title=AI review example 5: N+1 queries}}

{{embed:ai-engineering/ai-code-review/06-incorrect-authorization-cross-tenant.md|title=AI review example 6: Cross-tenant authorization}}

{{embed:ai-engineering/ai-code-review/07-unbounded-concurrency.md|title=AI review example 7: Unbounded concurrency}}

{{embed:ai-engineering/ai-code-review/08-resource-leak.md|title=AI review example 8: Resource leak}}

{{embed:ai-engineering/ai-code-review/09-insufficient-tests.md|title=AI review example 9: Insufficient tests}}

{{embed:ai-engineering/ai-code-review/10-sensitive-data-in-logs.md|title=AI review example 10: Sensitive data in logs}}

{{embed:docs/operations/post-incident-review-template.md|title=Post-incident review template}}
