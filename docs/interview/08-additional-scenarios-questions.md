# Question Bank 8: Caching, Consistency, Migrations, Performance Testing and Safe AI Change (Q162-Q172)

Reference-implementation study material. These questions close coverage gaps found while assembling the PDF edition: caching, eventual consistency, schema migration under rolling deployment, performance-test planning, pipeline design and AI-assisted refactoring or upgrades. Nothing here claims that Docker, Kubernetes, Cloud Foundry, k6, RabbitMQ, Redis or a real PostgreSQL were executed.

## Caching, Consistency, Migrations, Performance, Pipelines and Safe AI Change

### Q162. Template lookups are cached, and after a deployment many pods start with an empty cache; the database CPU spikes and latency climbs. What happened and how do you fix it?
**Tags:** Caching, Senior, Scenario
**Analogy:** Thirty children all lose their sticky note at the same moment and run to the one phone book; the phone book gets crowded and everyone waits longer.
**Model answer:**
This is a cold-start cache stampede. Every pod has an empty cache, every request misses, and the misses reach the database together. If the connection pool is also small, requests queue on the pool and clients time out and retry, which produces more misses.

Diagnose: compare cache hit ratio and database query rate over the deployment time; check `hikaricp_connections_pending` and acquire time; confirm the repeated query is the template-version lookup.

Fix in layers:
1. Single-flight loading: one loader per key per pod, other callers wait for that load (`LoadingCache`, or `computeIfAbsent`).
2. Immutable keys: a published template version never changes (ADR-0007), so the key `(code, locale, version)` can be cached with a long TTL and never invalidated; only the mutable "latest approved" pointer needs a short TTL.
3. Warm the cache before taking traffic: load the hot working set in a startup task and gate readiness on it, bounded by a timeout.
4. Jitter the TTL so keys do not expire together.
5. Protect the database: bounded pool, short `connection-timeout`, `503` with `Retry-After` on exhaustion, and staggered rollout (`maxSurge`, readiness) so not all pods are cold at once.

Trade-off: a warm-up step slows rollout slightly; a stale "latest" pointer can delay the use of a newly approved version by up to the TTL, which is acceptable when approval is not time critical and unacceptable for an emergency withdrawal, which should evict explicitly.

Measure: cache hit ratio, load duration, pool pending, p99 latency during deployment.
**Follow-ups:**
- Why is caching a mutable opt-out flag more dangerous than caching a template version?
- How do you bound cache memory?
- What does readiness have to say about a cold cache?
**Pitfalls:**
- Raising the pool size instead of reducing misses.
- Unbounded caches.
- Invalidating by guesswork instead of using immutable keys.

### Q163. Explain eventual consistency using this platform. Where is it strongly consistent, where is it eventually consistent, and how do you tell clients?
**Tags:** Distributed Systems, Senior, Concept
**Analogy:** Posting a letter: the moment you drop it in the box you know it is yours to track (strong), but when it reaches the other person is "eventually", and you only learn by the tracking slip.
**Model answer:**
Strong consistency means a read after a write sees that write. Eventual consistency means replicas or stages converge if no new updates arrive, and in between a reader may see an older state.

In the platform:
- Strong: the accept transaction. Message row, outbox row, idempotency record and audit event commit together, so once the client has `202` the message exists durably and `GET /messages/{id}` returns it. The `status` change is a compare-and-set update, so two actors cannot both win.
- Eventual: everything after the outbox. The broker publish, the consumer claim, the provider call and above all the provider's delivery receipt arrive later and possibly out of order. The status shown can lag reality: the provider may have delivered an email while the row still says `SENT_TO_PROVIDER`.
- Reconciled: `DELIVERY_UNKNOWN` is the explicit "we do not know yet" state, and `ReconciliationService` plus callbacks converge it to a terminal state.

Telling clients: document that `202` means accepted and durable, not delivered; expose `status`, `statusChangedAt` and history; recommend polling with backoff or callbacks instead of assuming delivery; never imply `SENT_TO_PROVIDER` is delivery. Internally, use monotonic rules (the rank rule for receipts) so convergence never moves backwards, and idempotent operations so repeated convergence steps are harmless.

CAP framing: during a partition between the application and the broker the platform stays available for accepting (the outbox buffers) and gives up immediate delivery, which is an AP-style choice for notifications; for a trade confirmation the business accepts the delay but not a loss.
**Follow-ups:**
- How would a read replica change what you promise?
- What is read-your-writes and when do you need it?
- How do you test convergence?
**Pitfalls:**
- Saying "eventually consistent" without naming the convergence mechanism.
- Telling clients that `202` equals delivered.

### Q164. Right after an operator requeues a message, the console still shows `FAILED` for several seconds, and the operator clicks Requeue again. What do you do?
**Tags:** Consistency, Intermediate, Scenario
**Analogy:** You press the lift button, the light does not come on for a moment, so you press it again and again.
**Model answer:**
Two things can cause it: a read replica or cache lagging behind the write, or the UI rendering before the asynchronous part finished. The requeue itself is a compare-and-set `FAILED|DEAD_LETTERED` to `QUEUED` plus an outbox event, so a second click is already safe on the server: the second request loses the compare-and-set and receives `409 ILLEGAL_STATE_TRANSITION`.

Improve the experience without weakening safety:
1. Return the new state in the requeue response (`QUEUED`) and update the UI from that response, not from a later read (read-your-writes by construction).
2. Disable the button while the request is in flight and show a pending state; use an `Idempotency-Key` for operator actions so a double click is a replay.
3. If a replica is used for lists, read the single message from the primary after a write, or send the version and wait until the replica catches up.
4. Show history entries (`requeued by`, time) so the operator sees evidence.
5. Map `409` to a friendly "already requeued" instead of an error.

Do not "fix" it by removing the server-side guard: the guard is what keeps a double click from creating two sends.
**Follow-ups:**
- Why is a 409 here a success from the operator's point of view?
- How do Angular Signals help show pending state?
**Pitfalls:**
- Relying on the UI to prevent double actions.
- Reading from a replica immediately after a write.

### Q165. A provider starts answering `429 Too Many Requests` with a `Retry-After` header. How should the dispatch pipeline react?
**Tags:** Messaging, Senior, Scenario
**Analogy:** A bus driver says "wait two minutes, the bus is full"; you wait the two minutes instead of shouting at the door every second.
**Model answer:**
A `429` is not a failure of the message and not a failure of the provider: it is a request to slow down. Treating it as a normal transient failure would burn the attempt budget and add load; treating it as a circuit-breaker failure would open the breaker for a healthy provider.

Behaviour:
1. Classify it as a rate-limit outcome (retryable, no attempt consumed, not counted as provider ill-health) and honour `Retry-After` as a lower bound on the next delay, with jitter on top.
2. Reduce the sending rate: the provider token bucket should adapt downwards (additive increase, multiplicative decrease) or be capped by configuration.
3. Requeue with delay rather than blocking a worker thread; blocking threads while waiting turns provider slowness into consumer exhaustion.
4. Keep priorities: critical messages should keep their share of the reduced rate, bulk campaigns yield first.
5. Alert on sustained `429` rate; it usually means the configured rate is above the contract.

This platform's design already has the pieces: a token bucket per provider placed before the claim so a rate-limit requeue reuses the same `dispatchId` and consumes no attempt (DEVIATIONS stage 2, item 1), a circuit breaker for real failures, and bulk throttling. The simulators do not emit `Retry-After`, so that header handling is a design answer rather than implemented behaviour.
**Follow-ups:**
- Where would `Retry-After` be stored?
- How does this interact with quiet hours?
**Pitfalls:**
- Immediate retry loops.
- Counting 429 toward the circuit breaker.

### Q166. You must add a `NOT NULL` column with a default to a 200-million-row table while old and new application versions run together. Walk through it.
**Tags:** Schema Migration, Senior, Scenario
**Analogy:** Changing the wheels of a moving train: add the new wheel first, move the weight, only then take the old wheel away.
**Model answer:**
Do not run one `ALTER TABLE ... ADD COLUMN ... NOT NULL DEFAULT` and hope. Depending on the engine and version it can rewrite the table or hold a long lock, and the previous application version does not know the column.

Plan (expand, migrate, contract):
1. Expand migration (release N): `ADD COLUMN new_col ... NULL`, no default or a metadata-only default where the engine supports it. Old code ignores it.
2. Application release N writes the column for new rows; reads tolerate null.
3. Backfill as a separate resumable job: `UPDATE ... WHERE new_col IS NULL` in chunks (for example 1,000 to 10,000 rows by primary key range), a pause between chunks, committed per chunk, idempotent, with a progress metric and a stop switch. Watch replication lag and lock waits.
4. Verify: `SELECT count(*) WHERE new_col IS NULL` is zero.
5. Contract migration (release N+2, after N is retired): add the constraint without a long lock (on PostgreSQL `ADD CONSTRAINT ... CHECK (new_col IS NOT NULL) NOT VALID`, then `VALIDATE CONSTRAINT`, then `SET NOT NULL`), update the application to rely on it.
6. Rollback: before step 5 the application can roll back freely because the schema is expand-only; step 5 is the point of no return and is released separately.

Test the migration on a snapshot with production-like volume and time it. Flyway runs the DDL; the backfill is a job, not a Flyway script. This repository documents the policy in `docs/database/schema-evolution.md` and ran its migrations only on H2; no production-sized run exists.
**Follow-ups:**
- Why not put the backfill inside the Flyway migration?
- How do you create the index on the new column without blocking writes?
- What changes on Oracle?
**Pitfalls:**
- One big `UPDATE` in a single transaction.
- Contracting in the same release as the expand.

### Q167. An AI assistant proposes "simplifying" `MessageStateMachine` by replacing the transition table with a switch and removing "unreachable" edges. How do you handle it?
**Tags:** AI-Assisted Development, Staff, Scenario
**Analogy:** A helper offers to tidy your toolbox and throws away the tools he has never seen used; you only find out when the pipe bursts.
**Model answer:**
Treat it as a sensitive change: the state machine defines the lifecycle guarantees (invariant 1 in `AGENTS.md`), and an edge that looks unreachable in static code may be used by operator actions, reconciliation or callbacks (for example `DELIVERY_UNKNOWN` to `FAILED`, or `FAILED` to `QUEUED` for requeue).

Process:
1. Pin behaviour first: an exhaustive test that checks every (from, to) pair against an explicit expected table, plus the helper methods (`canCancel`, `canRequeue`, `resolveReceipt`). This repository has `MessageStateMachineTest`.
2. Ask the assistant for the refactor with the rule that the table test must pass unchanged. A change to the table is a behaviour change and requires an ADR or spec update and a human decision.
3. Search for callers of each edge (consumer, sweeper, reconciliation, operator endpoints) and require evidence in the PR.
4. Independent review by someone who did not drive the assistant.
5. Merge only if the diff is smaller or equal in risk to the status quo; here a table is already the simplest correct form, so the correct answer may be to reject the proposal.

The lesson to state: AI optimises for plausibility, not for the invariants only you know, so the tests and invariants must be written down where it can read them.
**Follow-ups:**
- What would you add to the repository context so the assistant would not propose this?
- When is a sealed-type state machine better than a table?
**Pitfalls:**
- Accepting a diff because the unit tests were green when the tests do not pin the edges.
- Editing the tests to match the new code.

### Q168. An AI-assisted Spring Boot upgrade makes the build green, but an integration test for callbacks now fails and the assistant suggests loosening the assertion. What do you do?
**Tags:** AI-Assisted Development, Senior, Scenario
**Analogy:** The new car part fits, but the dashboard light is on; the helper suggests taping over the light.
**Model answer:**
Never loosen an assertion to pass. A failing test after an upgrade is information: either the test depended on an incidental behaviour or the framework changed something that matters.

Steps:
1. Reproduce and read the failure; find the smallest failing case.
2. Read the release notes and the migration guide for the changed area (here likely JSON handling, servlet filter order, or request body caching, because signature verification needs the raw bytes).
3. Decide: is the new behaviour acceptable and the test wrong, or is the application broken? For a signature check the answer is almost certainly "application broken": a changed body-reading or filter order can break HMAC verification on raw bytes.
4. Fix the application code, add a regression test that pins the raw-body behaviour, keep the assertion as strict as it was.
5. If a test truly needs a change, make it a separate commit with the reason, reviewed by a human.
6. Record the finding in the upgrade PR.

Upgrade hygiene: one upgrade per PR, compiler first, full tests, dependency scan, staged rollout, human approval. Do not accept "the assistant says it is fine" as evidence; evidence is a test report from an actual run.
**Follow-ups:**
- How do you detect a silently changed JSON serialisation?
- Which tests are your safety net for security-sensitive code during an upgrade?
**Pitfalls:**
- Loosening assertions.
- Bundling several upgrades.

### Q169. Design a performance test plan for the platform that is credible, and say what you would not claim from it.
**Tags:** Performance Testing, Senior, Scenario
**Analogy:** A driving test on a quiet road at noon tells you little about rush hour in the rain; you test the route and conditions that will actually happen.
**Model answer:**
Start from questions, not tools: What accept rate must the API sustain (for example 500 per second transactional with p99 under a stated bound)? How long to drain a 1,000,000 message campaign? What happens when a provider is slow or fails? Does a campaign delay security alerts?

Plan:
1. Environment: production-like database engine and sizing, broker, number of instances, provider simulators with configurable latency and fault modes. State differences from production openly.
2. Data: realistic distribution of tenants, templates, recipients; warm and cold cache runs.
3. Scenarios: steady state, spike (ten times for five minutes), soak (hours, for leaks), backlog recovery (pause consumers, build backlog, resume, measure drain), provider degradation, retry storm, bulk plus transactional mix, failover.
4. Load model: open model (arrival rate) for the API so that slow responses do not reduce the offered load; avoid coordinated omission by measuring from intended start times.
5. Metrics: accept p50/p95/p99, error ratio by code, queue depth and age, accepted-to-terminal time, pool and thread saturation, GC pauses, provider call latency, database CPU and locks.
6. Pass criteria written beforehand as SLO-linked thresholds; run each scenario more than once and report variance.
7. Analysis: find the first bottleneck, fix it, repeat; record the exact commit, configuration and command.

Do not claim: a number that was not measured, production capacity from a laptop run, or provider behaviour from a simulator.

In this repository: eight k6 scenarios in `performance/k6/` and a Node load tool exist, designed from this plan, but k6 was not installed and none was executed; no throughput or latency number is recorded here. Any such number must come from a document in `docs/validation/` that records the command and the output.
**Follow-ups:**
- What is coordinated omission?
- How do you size the test generator itself?
- How do you test in production safely?
**Pitfalls:**
- Closed-loop tests that hide queueing.
- Reporting averages.
- Testing against a mock that is faster than the real dependency.

### Q170. The CI pipeline takes 48 minutes and developers start skipping it. How do you redesign it in Jenkins or Bamboo without lowering the gates?
**Tags:** CI/CD, Intermediate, Scenario
**Analogy:** A school entrance with one slow door; opening several doors and checking the quick things first gets everyone in faster without letting strangers in.
**Model answer:**
First measure: stage durations from the build history, flaky test rate, queue time waiting for agents, dependency download time.

Moves, in order of value:
1. Fail fast: compile, lint and unit tests first; expensive stages only if those pass.
2. Parallelise independent stages (backend verify, frontend lint/test/build, static analysis; then security scans and integration tests), as the project's `Jenkinsfile` does with `parallel` blocks.
3. Cache dependencies (Maven repository, npm cache) and Docker layers; do not cache test results across commits unless the key is the content hash.
4. Split by risk: on pull requests run the fast gate plus changed-module tests; on main run the full suite and the image scan; nightly run the slow container integration and load smoke tests.
5. Remove flakiness: quarantine with an owner and a deadline, never retry silently forever; fix async test synchronisation.
6. Use right-sized agents and concurrency (`disableConcurrentBuilds(abortPrevious: true)` for the same branch).
7. Keep the gates that protect production: dependency scan, tests, contract test, migration test, image scan, manual approval for deploy.

Jenkins versus Bamboo: both express stages and parallelism (declarative `Jenkinsfile` versus Bamboo YAML specs in `bamboo-specs/bamboo.yaml`); Jenkins is more extensible through plugins and pipeline libraries, Bamboo integrates tightly with Jira and Bitbucket and has built-in deployment projects. Both pipelines in this repository are unexecuted: no Jenkins or Bamboo instance was available.
**Follow-ups:**
- How do you decide what to move out of the pull-request path?
- How do you version pipeline code?
**Pitfalls:**
- Lowering coverage or removing scans to save time.
- Retrying flaky tests until green.

### Q171. The MongoDB diagnostics store is down. Should callback processing fail, and how do you make sure it does not?
**Tags:** Databases, Intermediate, Scenario
**Analogy:** A security camera breaks; the shop should keep selling, and tell someone the camera needs fixing.
**Model answer:**
No. Diagnostics are an operator convenience (what exactly did the provider tell us), not part of the correctness of delivery state. The relational database holds the lifecycle and the unique `delivery_event` row; if the store is unavailable, callback processing must still verify the signature, apply the state change and answer the provider quickly, otherwise the provider retries and a diagnostics outage becomes a delivery-tracking outage.

Design:
1. Best-effort write: catch every exception, increment a metric (`messaging.diagnostics.write.failures`), log the exception class only.
2. Bounded effort: short timeout; consider an asynchronous bounded queue so even slow Mongo does not add latency to the callback.
3. Redact before storing; never store message content.
4. Alert on the failure counter rather than on callback errors.
5. TTL index so the collection cleans itself; no business query depends on it.

The project does exactly this in `ProviderDiagnosticsStore` ("never takes part in the message flow: every write failure is swallowed and counted"). The general lesson is to classify each dependency as critical-path, best-effort or optional and to design failure handling accordingly. Real MongoDB was not executed; locally an in-memory Mongo-compatible server stands in.
**Follow-ups:**
- When would you choose to fail the request because an audit write failed?
- What is the difference between audit and diagnostics here?
**Pitfalls:**
- Letting an optional dependency fail a critical path.
- Swallowing errors without a metric.

### Q172. A large bulk job runs and the interactive API starts timing out on database connections. How do you isolate them?
**Tags:** Connection Pools, Senior, Scenario
**Analogy:** One class takes all the bicycles for a long field trip and nobody else can get to school.
**Model answer:**
The bulk writer holds connections for whole chunks (chunk transactions can last one to two seconds) and runs several workers in parallel, so it competes with short API transactions for the same pool. By Little's Law its long hold times consume a disproportionate share of connections.

Controls:
1. Bound bulk concurrency (`messaging.bulk.concurrency`, a fixed `ThreadPoolTaskExecutor` that aborts when saturated) and the number of parallel jobs (`max-parallel-jobs`, a bounded launch queue that answers `503` instead of piling up).
2. Throttle the job rate (token bucket) so it cannot flood the outbox, the broker or the providers.
3. Give batch a separate, small pool (for example 4 to 5 connections) so it cannot starve the API; this needs a second `DataSource` and transaction manager, which adds configuration weight and is an option rather than something this repository implements.
4. Smaller chunks reduce hold time at the cost of more commits; tune with measurement.
5. Run heavy jobs off-peak or with a lower priority category so interactive traffic wins.
6. Alert on `hikaricp_connections_pending` and the age of the oldest queued message.

Verify by correlating pool metrics with job start times, then repeat under test with the job and the API load together.
**Follow-ups:**
- What does the separate pool cost?
- Why does a fail-fast connection timeout help the API here?
**Pitfalls:**
- Raising the shared pool size.
- Unbounded job concurrency.
