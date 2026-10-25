# Spec NNNN: <Title>

- Status: Draft | Review | Approved | Implemented | Superseded by NNNN
- Owner (role): <requirements/architecture/backend...>
- Date: YYYY-MM-DD
- Related: ADRs <links>, CONTRACT sections <n>, issues <links>

## 1. Context and problem
What hurts, for whom, evidence. Link existing behavior (code paths, docs).

## 2. Goals and non-goals
Goals (measurable). Non-goals (explicit).

## 3. Requirements
R1... (numbered, testable, one behavior each). Mark MUST/SHOULD.

## 4. Acceptance criteria
Given/When/Then for each requirement (AC-1...). Include negative and failure cases.

## 5. Design
- Overview and diagram (Mermaid if useful).
- Components touched (packages/classes per CONTRACT section 2).
- Data model changes (expand-only migration plan, Oracle impact).
- API changes (additive within v1; OpenAPI diff).
- Config keys (names, defaults, validation).
- State machine impact (table changes need ADR).
- Alternatives considered (at least two) with trade-offs.

## 6. Failure modes and recovery
Crash/duplicate/ordering/timeout analysis per step; interaction with idempotency, outbox, retries, reconciliation.

## 7. Security and privacy
Threat-model rows affected; authz and tenant impact; data stored/logged; abuse cases. Independent review required? (yes/no, why)

## 8. Performance and capacity
Expected load, queries and indexes, pool/thread impact, measurement plan (executed only).

## 9. Observability
Metrics (names), logs, traces, alerts, dashboard changes, runbook updates.

## 10. Test plan
Named tests (unit, integration, failure, security, perf) mapped to ACs. Data and fault injection needs.

## 11. Task decomposition
Ordered, independently mergeable tasks with owner role, dependency, DoD.

## 12. Rollout and rollback
Flags, canary, success/abort criteria, rollback steps, compatibility notes.

## 13. Documentation updates
List files to update.

## 14. Open questions
Owner and due date for each.
