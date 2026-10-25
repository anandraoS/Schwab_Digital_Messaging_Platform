# AI-Assisted Safe Refactoring and Dependency Upgrades

AI assistants are fast at mechanical change across many files, which makes them tempting for refactors and upgrades and makes their failures expensive: a plausible-looking diff that changes behaviour, an import of a library that does not exist, a "fix" that weakens a test. This chapter is the playbook for using an assistant on change work in a regulated messaging platform: small steps, behaviour pinned by tests, a human approving every merge. It complements the general deep dive on AI-assisted engineering. No claim is made that the project's own refactors or upgrades were performed by an assistant under this exact process: `AGENTS.md` records that the repository was authored with Claude Code, and the playbook below is the discipline to apply, not a log of events.

## Kid-friendly analogies

| Concept | Analogy |
|---|---|
| Characterisation test | Taking a photo of your room before a helper tidies it, so you can check that nothing was lost. |
| Small safe step | Moving one book at a time and checking the shelf, instead of emptying the whole bookcase onto the floor. |
| Strangler fig | Growing a new tree around the old one; when the new tree can stand alone, the old trunk is removed. |
| Dependency upgrade | Replacing a car part: read the manual first, change one part, take a test drive. |
| Hallucinated API | A helper confidently handing you a tool from a toolbox that does not exist. You find out when you reach for it. |
| Human approval gate | A grown-up signing the permission slip before the trip, after reading it. |

## The rule: behaviour first, structure second

Refactoring means changing structure without changing behaviour. An assistant cannot know what behaviour you care about unless tests say so. The order of work is therefore fixed:

1. Pin the behaviour: write or locate tests that fail if the behaviour changes (characterisation tests for legacy code, property tests for pure logic).
2. Run them green on the untouched code and record that.
3. Ask the assistant for one small, named transformation (extract method, replace conditional with table, move class, rename).
4. Run the same tests; they must be green without being edited. If a test must change, stop: that is a behaviour change and needs a separate, explicit decision.
5. Review the diff as if a stranger wrote it, then merge, then repeat.

### What makes a good prompt for refactoring

| Include | Why |
|---|---|
| The goal in behavioural terms ("no change in observable behaviour") | Prevents helpful "improvements" |
| The files in scope and the files off limits | Limits blast radius and token cost |
| The invariants (for the project: state changes only via `MessageStateMachine`, idempotency at every write path, tenant scoping) | The assistant cannot infer them reliably |
| The test command and the rule "do not edit tests" | Makes the tests the judge |
| "Stop and ask if a public method signature or a SQL statement must change" | Creates an escalation point |

## Refactoring risk map for a messaging platform

| Area | Why risky | Extra control |
|---|---|---|
| State machine and transitions | A silently added or removed edge changes lifecycle guarantees | Exhaustive transition-table test (every pair from every state) plus review by a second person |
| Idempotency and dedupe | Looks like redundant code to a model ("this check is unnecessary") | A test that submits the same key twice and delivers the same envelope twice |
| Transaction boundaries | Moving a call inside or outside `@Transactional` changes atomicity | Tests with fault injection between steps; review of every proxy-sensitive change |
| Retry and backoff | Constants and classification affect provider load | Unit tests on `RetryPolicy`, named constants kept |
| Security filters and tenant scoping | A "simplified" query can drop the tenant predicate | Cross-tenant tests returning 404 |
| SQL and indexes | An ORM-friendly rewrite can cause N+1 or full scans | Query count assertions, plan review |
| Concurrency | Replacing a CAS update with read-modify-write introduces a race | Concurrency tests, review checklist `07-unbounded-concurrency` |

> [!NOTE] In this project
> `ai-engineering/ai-code-review/` holds ten flawed-code exercises with corrected versions (`01-incorrect-state-transition.md`, `02-missing-idempotency.md`, `03-race-condition.md`, `04-unsafe-retry.md`, `05-n-plus-one.md`, `06-incorrect-authorization-cross-tenant.md`, `07-unbounded-concurrency.md`, `08-resource-leak.md`, `09-insufficient-tests.md`, `10-sensitive-data-in-logs.md`). `ai-engineering/skills/` contains task recipes such as `add-state-transition.md`, `write-migration.md` and `write-failure-test.md`, and `ai-engineering/checklists/code-review.md` is the human gate. These are documents; the exercises were not executed against a model in a measured study.

## Refactoring patterns an assistant does well, with the guardrail

### 1. Replace nested conditionals with a table (easy)

The project's `MessageStateMachine` is the end state of this refactor: a table (`EnumMap` of `EnumSet`) replaced scattered `if` chains. Guardrail: generate the old-versus-new truth table for all state pairs and assert equality before deleting the old code.

### 2. Extract a class from a long service (medium)

`DispatchConsumer` coordinates claim, render, call and outcome. Extracting the outcome handling into its own collaborator is reasonable, provided the transaction template and claim stay together and the "no transaction across the provider call" rule is checked after the move.

### 3. Parallel change (strangler) for an API or a provider adapter (hard)

Introduce the new interface beside the old, route a small share of traffic, compare, then remove the old one. The platform's `ChannelProvider` port with simulators and the `MessageBus` port with in-memory and RabbitMQ implementations are examples of seams that make this safe.

### 4. Mechanical migrations: Java records, `var`, switch expressions, sealed interfaces

Low risk with a compiler and tests behind them. Review each for equality semantics (records change `equals`), serialisation shape (Jackson) and Hibernate compatibility (entities must not be records).

## Dependency and framework upgrades with an assistant

### Process

| Step | Action | Evidence required |
|---|---|---|
| 1. Inventory | List direct dependencies and versions (`mvn dependency:tree`, `npm ls`) | Output attached to the PR |
| 2. Read the notes | Release notes and migration guide of the target version, read by a human; the assistant may summarise but the links are checked | Links in the PR description |
| 3. One upgrade per PR | Spring Boot minor, then libraries, never both | Small diff |
| 4. Let the compiler lead | Fix compile errors first, then deprecations, then behaviour | Build green |
| 5. Run the full test suite | Unit, integration, contract (OpenAPI), migration | Test report from an actual run |
| 6. Security scan | Dependency check for new CVEs and for new transitive dependencies | Scan output |
| 7. Behavioural diff | Compare startup logs, bean list, health, metrics names, JSON shape | Diff reviewed |
| 8. Canary or staged rollout | Roll out gradually with a rollback plan | Release checklist |

### Hallucination and supply-chain controls

AI-generated change can contain a method that does not exist in your version, a deprecated API, or a package name that is a typo-squat of a real one.

1. The build is the first reviewer: if it does not compile against the pinned version, the suggestion is wrong.
2. Every new dependency in a diff needs a named human reason, a check that it is the intended publisher and licence, and approval; lock files are reviewed.
3. Prefer the framework's own feature over a new library.
4. Pin versions; do not let an assistant run `latest`.
5. Treat install scripts and scripts fetched from the internet as untrusted (the same rule as for any downloaded content).
6. Keep secrets out of prompts and out of the repository the assistant reads.

### Example upgrade: Spring Boot 3.4 to 3.5

1. The assistant lists changes from the official notes (property renames, deprecations).
2. It changes the parent version and fixes compile errors only.
3. Failing tests are investigated: is the test wrong, or has behaviour changed? A test is never edited to make it pass without understanding why.
4. Property renames are applied with the `spring-boot-properties-migrator` during the upgrade, then removed.
5. The OpenAPI contract test, the Flyway migration run and the security tests (401, 403, 404 cross-tenant) are re-run.
6. A human approves after reading the diff of `pom.xml` and the test results.

The reference implementation is on Spring Boot 3.5.x and Java 21 (`AGENTS.md`); this chapter does not claim an upgrade was run by an assistant.

### Example upgrade: Angular 19 to 20

The Angular console uses standalone components and Signals. `ng update` performs most migrations; an assistant helps with the leftovers (template control flow, deprecated APIs). Gate: lint, unit tests and a production build must pass, and the console's role-based behaviour is spot-checked manually because the UI guards are UX only and the backend is the authority.

## Human approval and validation

| Control | Purpose |
|---|---|
| Branch protection and required reviewers (CODEOWNERS) | An assistant cannot merge its own change |
| Independent review for sensitive areas (author is not reviewer, fresh context) | Catches author-blind errors |
| Required checks: tests, lint, dependency scan, OpenAPI contract | Machine-verifiable gate |
| No silent override of an accepted ADR | Architecture decisions are changed by people, in writing |
| Evidence in the PR: what ran, with output | Prevents unexecuted claims |
| Scoped, least-privilege agent permissions | A refactoring agent needs read and write on a branch, not production credentials |

> [!NOTE] In this project
> `.github/CODEOWNERS`, `.github/pull_request_template.md`, `.github/copilot-instructions.md` and `.github/instructions/` exist; `ai-engineering/multi-agent-workflow.md` defines roles and three gates and states that "the author cannot approve" a sensitive change. `AGENTS.md` rule 1 to 6 are the invariants an agent must preserve. Whether GitHub branch protection is enabled is a repository setting that is not recorded here.

## Measuring whether it helped

Track change failure rate, review time, escaped defects, size of AI-assisted PRs and time to merge, comparing before and after, and watch for the failure modes: larger diffs, green tests that restate the implementation, and reviewers rubber-stamping long diffs. If an assistant makes PRs bigger, limit PR size rather than reviewing faster.

## Interview Q and A snippets

**Q: How do you let an AI assistant refactor a critical service safely?**
A: Pin behaviour with tests first, give the assistant one named transformation at a time with the invariants and the rule not to touch tests, run the same tests after each step, and have a human review the diff. Sensitive areas such as the state machine or idempotency get an independent reviewer.

**Q: The assistant proposes upgrading five libraries in one pull request. What do you do?**
A: Reject the batching. One upgrade per PR, read the release notes, compiler first, full tests and a vulnerability scan, then a staged rollout. Batching removes the ability to find which change broke what.

## Key takeaways

- Tests are the judge: behaviour is pinned before any assistant touches the code, and tests are never edited to pass.
- Small steps, one transformation or one upgrade at a time, each ending green.
- The build, the lock file review and a human are the controls against hallucinated APIs and unwanted dependencies.
- Sensitive logic (state, idempotency, security, transactions) always gets an independent human review.
- Claim only what was run; say plainly what was not.
