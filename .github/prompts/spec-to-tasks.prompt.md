---
mode: agent
description: Decompose a spec in specs/ into ordered, independently mergeable tasks with named tests
---
Read the spec at `${input:specPath:specs/0001-priority-aging.md}` and the files it references (ADRs, CONTRACT sections). Do not implement anything.

Produce:
1. A task table: ID, title, role (backend/angular/testing/security/performance/docs), dependencies, definition of done, estimate (S/M/L), files likely touched (package names per `docs/design/CONTRACT.md` section 2).
2. For each task, the exact test names to write first (follow `docs/architecture/reliability-scenarios.md` naming for failure tests).
3. A dependency-ordered merge plan where every task leaves the build green.
4. The invariants from `ai-engineering/context-engineering.md` each task could violate, and how the task avoids that.
5. Which tasks need independent review (authn/z, tenancy, callbacks, templates, state machine, retry/idempotency, consent).
6. Open questions that block design; do not guess answers.

Rules: do not invent classes or behavior that you have not confirmed in source (say "see source"); keep migrations expand-only; no unexecuted claims.
