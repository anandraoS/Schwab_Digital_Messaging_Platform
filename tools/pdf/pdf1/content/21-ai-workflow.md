# 21. AI-Assisted Development Workflow

> [!IMPORTANT] Provenance, stated plainly
> This repository was authored with **Claude Code** (an agentic CLI), orchestrated by a lead agent that delegated file-disjoint sections to sub-agents, all working from one shared contract (`docs/design/CONTRACT.md`). **No GitHub Copilot session, Copilot agent mode, Copilot code review or any other third-party agent was run to produce these artifacts.** The files `.github/copilot-instructions.md`, `.github/instructions/`, `.github/prompts/` and `AGENTS.md` are *authored configuration* showing how such tools would be configured; they have not been exercised by those tools here. Where a workflow below describes an independent security review, it describes the **intended process**, not a record that it happened.

## 21.1 What "AI-assisted" means here

AI coding assistants are fast and fluent and will happily produce plausible code that violates the invariants a messaging platform lives on: a missing idempotency check, a status written without the compare-and-set, a retry on an uncertain outcome, a log line with an address in it. The workflow in this repository is therefore built on one idea: **make the invariants explicit, make the checks mechanical, and keep humans on the decisions.**

{{diagram:aiWorkflow}}

| Principle | Mechanism in the repository |
|---|---|
| One source of truth | `docs/design/CONTRACT.md` is the build contract; deviations are appended to `docs/design/DEVIATIONS.md`, never silently changed |
| Load only what the task needs | `ai-engineering/context-map.md` and seven short `ai-engineering/context/*.md` modules (api, architecture, db, lifecycle, observability, security, testing), each with a "load when" header |
| Specify before building | `specs/spec-template.md`; worked specs `0001-priority-aging.md` and `0002-new-provider-adapter.md` with acceptance criteria first |
| Reusable playbooks | `ai-engineering/skills/`: add-endpoint, add-provider-adapter, add-state-transition, diagnose-queue-backlog, review-security, write-failure-test, write-migration |
| Standards that a model can follow | `ai-engineering/standards/`: Java and Spring, Angular, API design, DB migrations |
| Checklists as gates | `ai-engineering/checklists/`: code review, security review, test generation, performance review, definition of done, release readiness |
| Learn from realistic mistakes | `ai-engineering/ai-code-review/`: ten worked examples of flawed AI-style code, the fix, the rationale and the regression test |
| Independent review of sensitive areas | `ai-engineering/multi-agent-workflow.md`: authors never approve their own authentication, tenancy, callback, template, state-machine or retry changes; `.github/CODEOWNERS` encodes it |
| Honesty rules | Never claim execution that did not occur; label "not executed"; no secrets; no real data; mark illustrative numbers |

## 21.2 The invariants an assistant must not break

`ai-engineering/context-engineering.md` keeps a short invariants list that every prompt includes. The version below is the working summary:

| Invariant | Why it exists |
|---|---|
| Every status change goes through `NotificationService.transition` with the expected `from` | CAS ownership; the state machine is the only authority |
| Accepted means persisted: state, outbox and audit in one transaction | No message lost between database and broker |
| The envelope carries identifiers only | No content or PII on the bus |
| Never retry a `TIMEOUT` or `AMBIGUOUS` outcome blindly for EMAIL and SMS | Duplicate customer messages |
| No transaction is held across a provider call | Pool exhaustion |
| Tenant comes from the token, resources of other tenants are 404 | Isolation without disclosure |
| No rendered body, address or secret in logs, audit or Mongo | Data minimisation |
| Fallback channels pass the full policy chain | Consent is never bypassed |
| Everything bounded | Threads, queues, retries, body sizes, bulk concurrency |

## 21.3 Spec-driven loop (idea to production in 11 steps)

`ai-engineering/spec-driven-workflow.md` walks one change from idea to production with a worked example. The shape:

| Step | Output | Gate |
|---|---|---|
| 1 Problem and personas | Spec sections 1 to 3 | Human sign-off |
| 2 Acceptance criteria (Given, When, Then) | AC list with ids | Testable |
| 3 Design and ADR if cross-cutting | ADR, diagrams | Design review (Gate A) |
| 4 Context selection | Modules loaded | Token-lean |
| 5 Task breakdown | Tasks with dependencies | Small, reviewable |
| 6 Implement with tests | Code and tests together | Tests executed |
| 7 Self-review with checklist | Checklist ticked | `checklists/code-review.md` |
| 8 Independent review for sensitive areas | Review notes | Gate B and security gate |
| 9 Performance review for hot paths | Findings | `checklists/performance-review.md` |
| 10 Documentation and honesty labels | Docs, deviations | No invented behaviour |
| 11 Release readiness | Checklist | Gate C |

## 21.4 How the agents were organised for this repository

A lead agent fixed the contract first, then delegated sections whose files do not overlap (backend foundation, messaging core, batch module, Angular console, infrastructure, documentation, PDFs). Each agent recorded where it had to differ from the contract in `DEVIATIONS.md`, which is why that file doubles as the authoritative record of implemented behaviour (Appendix B). Two concrete benefits are visible in the deviations: agents discovered real design flaws by implementing (the consumer must check before it claims; the outbox publisher must tolerate an already `QUEUED` message; Micrometer gauges are weak references), and the record kept those discoveries from being lost.

## 21.5 What AI assistance is good and bad at here

| Good at | Needs a human or a check |
|---|---|
| Boilerplate controllers, DTO records, repositories, migrations in the portable subset | Choosing the transaction boundary |
| Exhaustive table-driven tests (state matrix, policy table) | Deciding what must never happen (the invariants) |
| Consistent documentation and diagrams from code | Judging whether a number is measured or illustrative |
| Spotting N+1 queries and missing indexes when asked | Security review of its own changes (must be independent) |
| Drafting runbooks | Validating runbooks against a real incident |

## 21.6 Prompts and skills

The five Copilot-format prompt files in `.github/prompts/` (`debug-queue-backlog`, `generate-tests`, `perf-review`, `review-security`, `spec-to-tasks`) and the plain-Markdown playbooks in `ai-engineering/skills/` carry the same guidance in tool-neutral form so that any assistant, or a human, can follow them. Path-specific instruction files under `.github/instructions/` (`angular`, `java`, `security`, `sql-migrations`, `tests`) scope the rules to the files they govern.

## Reference material

{{embed:ai-engineering/README.md|title=AI engineering workspace}}

{{embed:ai-engineering/spec-driven-workflow.md|title=Spec-driven workflow}}

{{embed:ai-engineering/context-engineering.md|title=Context engineering}}

{{embed:ai-engineering/multi-agent-workflow.md|title=Multi-agent workflow}}

{{embed:ai-engineering/debugging-workflows.md|title=Debugging workflows}}

{{embed:ai-engineering/context-map.md|title=Context map}}
