# Skill: Add or Change a State Transition

**When**: lifecycle change. **Load**: `context/lifecycle.md`, ADR-0003. **Gate**: requires ADR (new or superseding) and independent review.

## Steps
1. Justify: which actor owns it, trigger, why existing edges cannot express it.
2. Update ADR-0003 table (via superseding ADR) and CONTRACT 3.1 via `docs/design/DEVIATIONS.md` entry.
3. Update `MessageStateMachine` table; keep it data-driven.
4. Implement the actor using CAS update; define loser behavior (ack/skip or 409).
5. Update: audit action, `messaging.transitions` tags (automatic), UI status badge and timeline, OpenAPI enum, docs (`lifecycle.md`, flows, ER if columns).
6. Tests: extend full-pair transition matrix test; concurrency test with the competing actor; API 409 test; metric assertion.
7. Check terminal/soft-terminal semantics and cancel/replay rules remain consistent.
8. Check retention/sweeper handling of the new state (never purged if non-terminal).

## Guardrails
Never add an edge to "make a test pass"; never bypass the machine for data fixes.
