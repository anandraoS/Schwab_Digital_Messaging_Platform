# Multi-Agent Workflow

A role-based way to split work among AI agents (and humans). **Provenance note**: this repository itself was produced by a Claude Code lead agent delegating file-disjoint sections to sub-agents under a shared contract. The role model below generalizes that practice; it does not claim that every gate was run with separate agents here.

## Roles
| Role | Responsibilities | Deliverables | Depends on | Must not |
|---|---|---|---|---|
| Requirements | clarify problem, personas, acceptance criteria, non-goals | spec sections 1-3 (`specs/`), AC list | stakeholders | design the solution |
| Architecture | design, ADRs, interfaces, failure modes, trade-offs | ADR(s), design section, diagrams, contract updates/DEVIATIONS | requirements | override accepted ADRs silently |
| Backend | implement services, persistence, messaging, providers; unit/integration tests | code, migrations, OpenAPI changes | architecture, contract | change API/schema outside spec |
| Angular | implement console screens, services, interceptors, tests | frontend code and tests | OpenAPI, backend endpoints | trust client-side authorization |
| Testing | test plan, matrices, failure-recovery tests, data builders | tests, scripts, traceability | requirements, backend | weaken assertions to pass |
| Security | threat model update, authz/tenant/callback review, secrets and logging review | review notes, tests, threat-model rows | design, code | approve own authored change |
| Performance | capacity thinking, query plans, load harness, profiling | findings, indexes proposals, benchmark notes (executed only) | backend | claim unmeasured numbers |
| Documentation | docs, runbooks, README accuracy, honesty labels | docs, validation report text | all | invent behavior ("see source") |

## Flow and review gates
```
Requirements -> Architecture -> [Gate A: design review] -> Backend/Angular (parallel by contract)
   -> Testing (continuous) -> [Gate B: code review + checklists] -> Security (sensitive only) -> Performance (hot paths)
   -> Documentation -> [Gate C: release readiness] -> Release
```
| Gate | Entry | Exit criteria |
|---|---|---|
| A design review | spec + ADR draft | invariants list respected; failure modes enumerated; ADR references; open questions resolved |
| B code review | PR with tests | `checklists/code-review.md`; tests green (executed); AC traceable |
| Security gate | sensitive change flagged | independent reviewer completes `security-review.md`, findings fixed or accepted by owner |
| C release readiness | all merged | `docs/operations/release-readiness-checklist.md` |

## Sensitive changes require independent review
Sensitive = authn/authz, tenant scoping, callback verification, template engine, state machine, retry/idempotency/reconciliation, consent policy, secrets/logging of data, deployment of dev endpoints. Rules:
1. The reviewer is a different agent session/person from the author, with fresh context (only the diff, spec, invariants, and checklists: not the author's reasoning).
2. Reviewer attempts to break it with failing tests (`skills/review-security.md`).
3. Author cannot approve; disagreements escalate to the architecture role/owner.
4. Result is recorded in the PR (findings table, verdict).

## No silent ADR override
Any change contradicting an accepted ADR requires: a new ADR that supersedes (status, rationale, consequences), update of the old ADR header, entry in `docs/design/DEVIATIONS.md` if it contradicts CONTRACT, and architecture-role approval. Agents must stop and escalate rather than "work around" an ADR in code.

## Coordination mechanics
- Contract-first: `docs/design/CONTRACT.md` is shared truth; agents write disjoint file sets (as done in this repo) to avoid merge conflicts.
- Handoff notes between sessions (`templates/handoff-note-template.md`).
- Conflict rule: contract wins; if contract is wrong, record deviation, do not fork silently.
- Evidence rule: reports state what was executed (command + result) and what was not.
- Parallelism: Backend and Angular can proceed in parallel against the OpenAPI spec; Testing writes failure tests from the reliability table in parallel with Backend; Documentation trails but reviews continuously.

## Failure modes of multi-agent work (and countermeasures)
| Failure | Countermeasure |
|---|---|
| Divergent assumptions (names, states) | contract + invariants; automated contract tests |
| Duplicated/contradictory docs | single home per fact, thin tool files |
| Overconfident claims | validation report from real outputs only |
| Test weakening | review of test diffs; mutation sanity check |
| Context loss | handoff notes, specs as memory |
| Reviewer shares author's blind spots | fresh-context independent review |
