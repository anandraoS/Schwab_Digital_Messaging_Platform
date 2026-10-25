# ADR-0003: Message lifecycle and state machine

Status: Accepted (2026-10-18)

## Context
A message crosses an API thread, a broker, a consumer thread, an external provider and an asynchronous callback. Without an explicit state model, concurrent actors (consumer, sweeper, callback processor, operator) corrupt each other's state, and "sent" gets confused with "delivered".

## Decision
Model the lifecycle as an explicit finite state machine (`domain.MessageStateMachine`, pure Java) over `MessageStatus`. Every transition goes through it; illegal transitions throw `IllegalStateTransitionException` (HTTP 409 `ILLEGAL_STATE_TRANSITION` for API-driven actions; logged + audited for event-driven ones). `SCHEDULED` is **not** a status: scheduled messages are `VALIDATED` with a future `scheduled_at`.

### Transition table (authoritative: CONTRACT 3.1)
| From | Allowed to | Owner / actor |
|---|---|---|
| ACCEPTED | VALIDATED, REJECTED | submission service (synchronous) |
| VALIDATED | QUEUED, CANCELLED, REJECTED | outbox publisher / poller; API cancel (a dispatch-time policy rejection is applied from PROCESSING after the claim, see `DEVIATIONS.md`) |
| QUEUED | PROCESSING, CANCELLED | dispatch consumer; API cancel |
| PROCESSING | SENT_TO_PROVIDER, RETRY_PENDING, FAILED, DELIVERY_UNKNOWN, REJECTED | dispatch consumer |
| RETRY_PENDING | QUEUED, DEAD_LETTERED, CANCELLED | retry scheduler; operator |
| SENT_TO_PROVIDER | DELIVERED, FAILED, DELIVERY_UNKNOWN | callback processor; sweeper |
| DELIVERY_UNKNOWN | DELIVERED, FAILED | callback processor; `ReconciliationService` |
| FAILED | DEAD_LETTERED, QUEUED (operator requeue) | operator |
| DEAD_LETTERED | QUEUED (operator replay) | operator |
| DELIVERED, REJECTED, CANCELLED | none (terminal) | |

Terminal classes: **hard terminal** = DELIVERED, REJECTED, CANCELLED. **Soft terminal** = FAILED (until operator requeue), DEAD_LETTERED (until operator replay). `terminal` in API responses is true for hard terminals and soft terminals.

### Ownership and concurrency
- Exactly one actor owns each state (table column 3). Cross-actor races are resolved by **compare-and-set** updates (`UPDATE ... SET status=:to WHERE id=:id AND status=:from`), plus `version` optimistic locking on `message_request`. A CAS loser acks and skips (consumer) or returns 409 (API).
- `DELIVERED` receipts are ranked by the monotonic rule in CONTRACT 3.4: a lower-rank event never overwrites a higher one; `DELIVERED` vs later `BOUNCED` keeps the first terminal and audits `DELIVERY_EVENT_CONFLICT`.
- Provider acceptance (`SENT_TO_PROVIDER`) never implies delivery.
- Cancellation allowed only from VALIDATED, QUEUED, RETRY_PENDING.
- Every transition emits `messaging.transitions{from,to}` and an `audit_event`.

## Alternatives considered
| Option | Why not |
|---|---|
| Free-form status strings updated ad hoc | Illegal jumps (e.g. DELIVERED to QUEUED) become possible |
| Spring Statemachine library | Heavy; the table is small and benefits from being a plain testable enum map |
| Event sourcing | Strong audit but high complexity; we keep `audit_event` + `message_attempt` + `delivery_event` as an append-only history instead |
| Pessimistic row locks | Throughput cost, deadlock risk across consumer/sweeper/callback |

## Consequences
- Positive: exhaustive transition tests; operators and the UI render one timeline; failure modes are named states (`DELIVERY_UNKNOWN`).
- Negative: any new state needs an ADR, table update, UI badge, metrics and test matrix update.
- Risk: code paths that update `status` directly (bypassing the machine). Guard: a repository-level test greps/asserts no direct status setter outside the machine (see `ai-engineering/ai-code-review/01-incorrect-state-transition.md`).

## Validation
- `MessageStateMachineTest` should enumerate the full `MessageStatus x MessageStatus` matrix against the table above (see source).
- Failure scenarios: `docs/architecture/reliability-scenarios.md`.
