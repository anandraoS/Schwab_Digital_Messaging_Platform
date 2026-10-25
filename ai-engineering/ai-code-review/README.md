# AI Code Review: Worked Examples

Ten examples of defects that AI-generated (or hurried human) code commonly introduces in this domain. Each file has: **Flawed code/design**, **Why it is wrong**, **Corrected version**, **Rationale**, **Regression test**, and the invariant(s) from `../context-engineering.md` it protects. Snippets are illustrative Java in the style of the project; confirm real signatures in source.

| # | File | Defect class |
|---|---|---|
| 1 | `01-incorrect-state-transition.md` | Illegal transition / bypassing the state machine |
| 2 | `02-missing-idempotency.md` | Duplicate effect on retry |
| 3 | `03-race-condition.md` | Read-modify-write status race |
| 4 | `04-unsafe-retry.md` | Blind retry after ambiguous outcome |
| 5 | `05-n-plus-one.md` | N+1 queries and DB performance |
| 6 | `06-incorrect-authorization-cross-tenant.md` | BOLA / missing tenant scope |
| 7 | `07-unbounded-concurrency.md` | Unbounded executor/queue |
| 8 | `08-resource-leak.md` | Connection/stream leak |
| 9 | `09-insufficient-tests.md` | Tautological or happy-path-only tests |
| 10 | `10-sensitive-data-in-logs.md` | PII/secret leakage |

How to use in a review session: give the assistant the diff plus the invariants list; ask it to cite which invariant each finding violates; require a failing test before accepting a fix. These examples are teaching material authored for this repository; they are not records of defects found by an automated Copilot review.
