# Context Engineering Guide

How to give an AI assistant (or a new engineer) the right information at the lowest token cost, and keep it correct over time.

## 1. Selection: load the minimum sufficient context
- Start from the task type in `context-map.md`; load 1-3 modules (each is ~300-600 tokens), not the whole docs tree.
- Prefer the *authoritative* short artifact over a long explanation: transition table (lifecycle.md) over the ADR; ADR only when rationale is needed.
- Load source files only for the classes being changed, plus their tests.
- Pull large sources (CONTRACT, OpenAPI) by section/`grep`, not whole.
- Order matters: put invariants and task goal first, reference material after.

## 2. Token optimization
| Technique | Example |
|---|---|
| Tables over prose | transition table instead of paragraphs |
| Names over descriptions | `ResilientProviderInvoker` rather than re-explaining circuit breaking |
| Link, don't paste | "see ADR-0010" |
| One canonical home per fact | metric names only in CONTRACT 8 + observability.md |
| Summarize history | handoff notes, not transcripts |
| Strip noise | exclude generated files, lockfiles, build output, `node_modules`, `target` from context |
| Chunk big tasks | one spec task per session |
| Ask for diffs | request patch-sized outputs |

## 3. Repetition avoidance
Tool-specific files (`AGENTS.md`, `.github/copilot-instructions.md`, path instructions) are *thin*: top rules + pointers to `ai-engineering/context/*`. Do not copy tables into three places. If a fact appears in two files, one of them must be a link.

## 4. Drift prevention
1. Source-of-truth map in `context-map.md` ("Where truth lives").
2. Contract tests tie docs to code where possible: OpenAPI vs springdoc; transition matrix test vs `MessageStateMachine`; metric names test vs CONTRACT 8.
3. Deviations recorded in `docs/design/DEVIATIONS.md`, ADR superseded rather than edited.
4. PR checklist item: "docs/context updated".
5. Periodic audit: grep context modules for each class name and metric name; failing lookups are drift.
6. Labelled uncertainty: docs say "see source" instead of guessing behavior.
7. Every doc mentioning a result cites the command and whether it ran.

## 5. Invariants list (the things that must always be true)
Reviewers and assistants must verify these against any change:
1. Status changes only via `MessageStateMachine` + CAS; transitions match the table (CONTRACT 3.1).
2. Accepted means durable: message + outbox + idempotency + audit commit in one transaction; publish happens after commit.
3. Consumer processing is idempotent (`processed_message`, CAS QUEUED->PROCESSING); callbacks idempotent by `(provider_id, provider_event_id)`.
4. No transaction spans a provider call; every remote call has a timeout; pools and queues are bounded.
5. Provider acceptance never implies delivery; timeouts/ambiguous never become failure or blind resend for EMAIL/SMS; `DELIVERY_UNKNOWN` resolves only via provider-originated evidence.
6. Retries are bounded, jittered, re-evaluate policy; poison messages go to DLQ.
7. Consent: suppression > category rules > quiet hours; fallback and replay never bypass consent; evaluated at accept and dispatch.
8. Tenant comes from the verified JWT only; every tenant-owned read/write is tenant-scoped; foreign resource => 404.
9. Rendered body, full addresses, secrets never stored/logged/returned/put on the bus; variables stored not body.
10. Template version pinned at accept; published versions immutable; approver != author.
11. Callback signature verification never bypassed; replay protection on.
12. Dev-only endpoints exist only in `local`/`test`.
13. Audit is append-only and written for every privileged action and transition.
14. Docs never claim unexecuted validation.

## 6. Prompting patterns that work here
- "Given invariants 1, 3, 5, review this diff for violations; list each with file:line and a failing test idea."
- "Write the failing test first for scenario N from reliability-scenarios.md."
- "Propose 2 designs with trade-offs, recommend one, cite ADRs affected; do not implement."
Anti-patterns: pasting the entire repo; asking for "production ready" without acceptance criteria; letting the assistant rewrite tests to pass; accepting invented APIs without compiling.

## 7. Session hygiene
New session per spec task; end with a handoff note; verify by running tests, not by assistant assertion.
