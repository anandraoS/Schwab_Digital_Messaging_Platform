# Question Bank 7: Technical Leadership, Agile, Financial-Services Reliability and Full-Stack Delivery (Q143-Q161)

Reference-implementation study material. Leadership answers are templates built on the CAR/STAR frameworks described in `00-how-to-use.md`; substitute your own real experiences. Financial-services statements are general engineering practice, not legal advice and not a description of any employer's internal systems.

## Leadership and Delivery

### Q143. How would you mentor a developer whose implementation passes unit tests but fails under production load?
**Tags:** Leadership, Senior, Scenario
**Analogy:** A driver who passed the parking-lot test but panics on the motorway: they need to practise on real roads, with a calm passenger beside them.
**Model answer:**
Clarify first, with curiosity rather than blame: what failed (latency, errors, timeouts, duplicates), under what load, what they already tried, and how they understand the system. Separate the technical diagnosis from the coaching.

Technical diagnosis together (pair, do not take over):
- Reproduce: a load test or the in-JVM harness at the failing rate; capture metrics (latency percentiles, pool saturation, GC, queue depth).
- Typical gaps that unit tests miss: N+1 queries and missing indexes with realistic data volume, connection pool exhaustion from long transactions or nested `REQUIRES_NEW`, unbounded thread pools or queues, no timeouts on provider calls, lock contention and hot rows, non-idempotent consumers under redelivery, memory growth from unbounded caches or high-cardinality metrics, serialization of large payloads.
- Use tools as teachers: thread dump, JFR flame graph, `EXPLAIN ANALYZE`, Hikari metrics. Let the developer drive and narrate what they see.

Coaching approach:
1. Normalize it: production load exposes problems no unit test can; it is a rite of passage and a team testing gap, not personal failure.
2. Teach a mental model: Little's Law, bottleneck thinking, "what happens at 10x, with a slow dependency, with duplicates, with a crash halfway".
3. Add the missing safety nets to the team's process: a non-functional checklist in the definition of done (timeouts, bounds, idempotency, indexes, observability), realistic-data integration tests, query-count assertions, a performance smoke test in CI, canary releases.
4. Provide a small follow-up challenge on a safe task (tune a slow endpoint with a profile), then gradually increase scope; celebrate the learning in the retro and have them present the post-mortem findings.
5. Check in regularly; ask them what support they need; recognize progress.

Success measures: they can explain bottlenecks unaided, their next designs include load considerations, fewer production regressions from their area, and team-wide checklist adoption.
**Follow-ups:**
- How do you balance coaching with fixing the production issue quickly?
- What if they become defensive?
- How do you prevent this class of bug from recurring across the team?
**Pitfalls:**
- Taking over and fixing it silently.
- Public blame.
- Treating it as an individual issue and not a process gap.

### Q144. How do you lead technical design decisions, and what is the role of Architecture Decision Records?
**Tags:** Leadership, Senior, Concept
**Analogy:** A ship's log of important turns: why we changed course, what we considered, and what we expected, so the next crew understands.
**Model answer:**
Leading design is facilitation plus accountability: frame the problem, make options visible, decide with evidence, and record the decision.

Process:
1. Define the problem and constraints: requirements, quality attributes (latency, availability, compliance), timeline, team skills, cost.
2. Explore options, including "do nothing", with trade-offs and a spike or prototype for the riskiest assumption.
3. Seek input broadly: design review with the people who will build, operate, secure, and depend on it; a short written proposal is better than a meeting without a document.
4. Decide: consensus where possible, a clear decider when not ("disagree and commit"), and a revisit trigger (what evidence would change our mind).
5. Record as an ADR: title, status (proposed, accepted, superseded), context, decision, consequences (positive and negative), alternatives considered. ADRs are short, immutable once accepted (superseded by new ones), and stored with the code (`docs/adr`, CONTRACT section 11). Examples in this platform: RabbitMQ as primary bus (ADR-0001), PostgreSQL as primary database (ADR-0005), template version pinning (ADR-0007).
6. Communicate and enforce lightly: fitness functions, tests like contract checks, and review checklists.

Principles: decide at the last responsible moment; prefer reversible decisions made quickly and irreversible ones made carefully; isolate volatile choices behind ports (`MessageBus`, provider interfaces); document trade-offs honestly including what we gave up.

Measure: decision lead time, number of reversals due to missed information, onboarding feedback on documentation.
**Follow-ups:**
- How do you handle a decision you disagree with?
- When is an ADR overkill?
- How do you keep ADRs discoverable?
**Pitfalls:**
- Decisions made in meetings and lost.
- ADRs written after the fact with no alternatives.
- Architect-by-decree without input.

### Q145. How do you run effective code reviews, and what do you look for beyond style?
**Tags:** Leadership, Intermediate, Concept
**Analogy:** A second pair of eyes before a bridge opens: checking the load, not the paint.
**Model answer:**
Purpose: catch defects early, share knowledge, maintain consistency, and mentor. Automate the mechanical parts (formatting, linting, static analysis, tests) so humans focus on design and risk.

Review priorities, in order:
1. Correctness and requirements: does it do what the spec says, edge cases, error handling.
2. Design: right abstraction level, coupling, alignment with architecture and patterns; is it testable.
3. Reliability and concurrency: idempotency, retries and timeouts, transactions and their boundaries, race conditions, resource cleanup, backpressure, failure behavior.
4. Security and privacy: authorization checks (tenant isolation), input validation, secrets, PII in logs, dependency additions.
5. Performance: N+1 queries, unbounded results, indexing, hot paths.
6. Tests: do they test behavior and failure modes; would they fail if the code were wrong.
7. Observability: metrics, logs with correlation id, no PII.
8. Readability and maintainability, then style nits (label as `nit:`).

Practices: small PRs (under about 400 lines) with a clear description (why, what, how tested, risks); review within a day (flow matters); distinguish blocking from optional comments; ask questions rather than dictate ("what happens if this runs twice?"); praise good work; two reviewers for risky areas (security, migrations); the author owns resolving; reviewers verify they understand, not rubber-stamp; use checklists; AI-generated code gets the same standards (Q135).

Culture: reviews are about the code not the person; rotate reviewers to spread knowledge; track review turnaround and PR size.

Measure: review turnaround, defects found in review versus escaped, PR size distribution, rework.
**Follow-ups:**
- How do you handle a very large PR?
- What if two reviewers disagree?
- How do you review a senior's PR as a junior?
**Pitfalls:**
- Bike-shedding on style while missing race conditions.
- Slow reviews creating batch work.
- Harsh tone.

### Q146. You and a respected peer disagree strongly on a technical approach (for example Kafka versus RabbitMQ). How do you resolve it?
**Tags:** Leadership, Senior, Scenario
**Analogy:** Two hikers who prefer different trails: you compare maps and conditions instead of arguing about whose instinct is better.
**Model answer:**
Use a structured approach (STAR/CAR when telling it in an interview: Situation, Task, Action, Result).

Situation example: the team debated Kafka versus RabbitMQ for dispatch. A peer championed Kafka for scale and replay; I leaned toward RabbitMQ for priorities and delayed retries.

Actions:
1. Seek to understand: restate their position until they agree you have it right; ask what problem they are optimizing for (replay, throughput, existing skills).
2. Agree on criteria before comparing options: required features (priorities, per-message TTL and DLQ, replay), expected throughput and growth, operational capacity, team experience, cost, and risks.
3. Make it evidence-based: quantify load (peak messages per second, retention need), list the requirements that each option meets natively or with workarounds, run a time-boxed spike on the riskiest requirement (for example delayed retries and priority handling on Kafka), and write a short options document.
4. Involve stakeholders (operations, security) and, if still split, escalate to the designated decider with a concise summary, or use a documented tie-break rule.
5. Decide, record in an ADR including the revisit trigger ("move to Kafka if we need replay or analytics fan-out"), and commit as a team ("disagree and commit"). Preserve the relationship: acknowledge good points, adopt part of their proposal (for example a Kafka event stream for analytics later, behind the `MessageBus` port).

Result: a decision everyone understood, with a clear path to revisit; avoid winning at the expense of trust.

Measure: decision lead time, team alignment, whether the revisit trigger is tracked.
**Follow-ups:**
- What if your peer is more senior and insists?
- How do you handle a decision you lost?
- When would you escalate?
**Pitfalls:**
- Arguing from preference or authority.
- Dragging the debate on without a decision date.
- Taking the outcome personally.

### Q147. A fixed deadline is at risk three weeks before release. How do you handle scope, quality and communication?
**Tags:** Leadership, Senior, Scenario
**Analogy:** A packed suitcase for a fixed departure time: you decide what to leave behind, you do not sit on the lid and hope.
**Model answer:**
Clarify: what is fixed (date, scope, budget), what the business truly needs on day one, and how big the gap is. Use data: remaining work estimates, velocity, risk list.

Actions:
1. Re-baseline honestly: break remaining work into small items, identify the critical path and dependencies (providers, security reviews, environment readiness), include testing, hardening and deployment work that is often forgotten.
2. Prioritize ruthlessly with the product owner: must-have (the happy path, security, consent rules, audit), should-have, could-have; cut or defer the last category. Protect non-negotiables: security, data correctness, observability and rollback ability. Do not trade away quality gates; cut scope instead.
3. Reduce risk: de-risk the riskiest items first (integration with the provider simulator, migration rehearsal), use feature flags to ship partially complete features dark, plan a phased release (pilot tenant first).
4. Add capacity carefully: more people late rarely helps (Brooks's law); remove distractions, simplify meetings, pair on blocked work, swarm on the bottleneck.
5. Communicate early and clearly: share the status with stakeholders weekly with options ("ship all in six weeks, or the core in three with these items deferred"), not surprises at the end; highlight risks and decisions needed.
6. Prepare launch: runbooks, rollback plan, load and failure testing, on-call readiness, go/no-go checklist.
7. After release: retrospective on estimation and planning.

Metrics: burn-down trend, scope delivered, defects, readiness checklist completion.
**Follow-ups:**
- What would make you recommend moving the date?
- How do you keep the team motivated?
- How do you handle a stakeholder demanding all scope?
**Pitfalls:**
- Silent heroics and burnout.
- Skipping testing to hit the date.
- Hiding the problem until the last week.

### Q148. How do Agile practices work in a regulated financial-services environment? What do you adapt?
**Tags:** Agile, Intermediate, Concept
**Analogy:** Fast cars on a racetrack with rules: speed and safety are both designed in.
**Model answer:**
Agile values (short feedback loops, working software, collaboration) are compatible with regulation when compliance is built into the flow rather than bolted on at the end.

Adaptations:
- Definition of Done includes compliance and security criteria: threat model for new data flows, peer review, automated security scans, audit logging, documentation updated, traceability from requirement to test, and operational readiness (runbook, alerts).
- Change management: automate evidence generation in the pipeline (test reports, approvals, deployment records, SBOM) so change approval (CAB) uses data; standard (pre-approved) changes for low-risk deployments; emergency change process for incidents; separation of duties (the author cannot be the only approver; four-eyes on template approval and deployments).
- Backlog and refinement: capture non-functional and compliance stories (retention, consent, audit), spikes for unknowns, explicit acceptance criteria and Given/When/Then.
- Cadence: sprints of 2 weeks, planning, daily standup, review/demo with stakeholders, retrospective; Kanban for operations. Release trains or frequent small releases with feature flags and release freezes around critical business periods.
- Risk-based quality: higher scrutiny (extra reviews, load and failure testing) on money-moving, consent, and security paths.
- Documentation: lightweight but sufficient: ADRs, API specs, runbooks, and traceability matrix kept in the repository, living documents.
- Metrics: DORA metrics, defect escape rate, SLO attainment, audit findings.

Pitfall: treating Agile as "no documentation" or as an excuse to skip controls; and waterfall gates that serialize everything. Aim for continuous compliance.

Measure: lead time, change failure rate, audit findings, compliance evidence completeness.
**Follow-ups:**
- How do you demonstrate separation of duties in a pipeline?
- How do you handle a release freeze?
- How do you estimate work with unknown compliance review time?
**Pitfalls:**
- "Agile means no documentation."
- Manual evidence collection at release time.
- Compliance as a final gate only.

### Q149. Tell me about a time you led a team through ambiguity. How would you structure the answer using STAR or CAR?
**Tags:** Leadership, Senior, Scenario
**Analogy:** Telling a short story with a beginning, a middle where you act, and an end showing what changed, so the listener can see your judgment at work.
**Model answer:**
Use STAR (Situation, Task, Action, Result) or CAR (Context, Action, Result), about 2 minutes, with specifics and numbers, and keep the focus on your decisions ("I"), crediting the team where appropriate.

Template with an illustrative (fictional) structure you should replace with a real story:
- Situation: "We needed to build a notification capability for several business units with unclear requirements: channels, volumes, and compliance rules were still being negotiated."
- Task: "I was the technical lead responsible for a design the team could start building within two weeks."
- Actions (the core, 60 percent of the answer):
 1. Reduced ambiguity: ran short discovery sessions with product, compliance and operations; wrote down assumptions and open questions; ranked them by risk.
 2. Created a thin end-to-end slice (accept, queue, simulated provider, status) to learn early, and isolated volatile decisions behind interfaces (provider ports, message bus port).
 3. Recorded decisions as ADRs with revisit triggers; made reversible decisions fast.
 4. Organized the team: clear ownership, short feedback loops, a risk burndown, weekly demos to stakeholders.
 5. Handled conflict: aligned on criteria before debating options.
- Result: "We delivered the pilot in six weeks (use real numbers), two assumptions proved wrong and were cheap to change because of the interfaces, the team reported higher clarity in the retro, and the approach became our template for new initiatives."
- Reflection: what you would do differently (for example engaging compliance earlier).

Tips: choose a story with real stakes; quantify the result (time saved, incidents avoided); show learning; prepare 5 to 6 stories that cover conflict, failure, mentoring, delivery under pressure, influencing without authority, and a technical deep dive; avoid vague "we" answers or blaming others.

Measure of a good answer: the interviewer can repeat what you did and why.
**Follow-ups:**
- What would you do differently now?
- How did you know the approach was working?
- How did you handle someone who resisted?
**Pitfalls:**
- Spending most of the time on the situation.
- All "we", no personal contribution.
- No measurable result.

### Q150. How do you decide what technical debt to pay down, and how do you get buy-in from product?
**Tags:** Leadership, Senior, Scenario
**Analogy:** Fixing the leaking roof before repainting the fence: debt is worth paying when the interest (ongoing cost and risk) is high.
**Model answer:**
Clarify what the debt is (outdated library, missing tests, tangled module, manual deployment, missing idempotency in a consumer) and what it costs today.

Prioritization framework: estimate for each item the interest (extra time per change, incident frequency, risk exposure such as security or data correctness), the principal (cost to fix), and the blast radius. Prioritize items with high interest and risk relative to cost: for example the poor test coverage of the lifecycle logic that every feature touches, or a deprecated dependency with a known CVE. Defer debt in areas rarely changed.

Make it visible: maintain a debt register in the backlog with impact statements in business terms ("each change to dispatch takes 2 extra days; two incidents last quarter"); use data (DORA metrics, defect and incident history, build times, DB slow queries).

Getting buy-in: translate to outcomes: faster delivery, fewer incidents, lower cost, reduced regulatory risk; propose a sustainable allocation (for example 15 to 20 percent of capacity each sprint or one hardening sprint per quarter); tie debt work to feature work ("when we touch it, we improve it", the boy-scout rule) and show before/after results; use error budgets (when reliability SLOs are burned, debt work takes priority).

Execution: small incremental refactors with tests (strangler pattern for larger rewrites), avoid big-bang rewrites, define done and measure benefits, and communicate wins.

Honesty with stakeholders: some shortcuts are deliberate and acceptable (prototype scope); record them as conscious debt with a plan and a trigger.

Measure: cycle time on the affected area, incident rate, build and test time, number of items closed with measured benefit.
**Follow-ups:**
- How do you estimate interest?
- When is a rewrite justified?
- What do you do when product refuses any debt work?
**Pitfalls:**
- Presenting debt as developer preference.
- Big-bang rewrites.
- No measurement of benefits.

### Q151. Your platform depends on an external provider team and an internal identity team who miss commitments. How do you manage cross-team dependencies?
**Tags:** Leadership, Senior, Scenario
**Analogy:** Coordinating a relay race where another runner is late: you do not wait at the line, you plan for the baton to arrive at an uncertain time.
**Model answer:**
Clarify the dependency, the date it is needed, the consequence of delay, and the commitment status (formal or informal).

Actions:
1. Make dependencies explicit early: map them in planning, with owners, dates, interfaces, and acceptance criteria; track them on a visible board and review in a regular sync of leads.
2. Define the contract first: agree on the API or event format and sample payloads, so work proceeds in parallel using mocks and simulators (the platform uses simulated providers and a dev token issuer to avoid blocking, CONTRACT sections 1 and 2). Contract tests detect drift.
3. De-risk: integrate the riskiest dependency earliest in a thin slice; time-box assumptions; keep alternatives (secondary provider, local fallback identity setup).
4. Communicate: regular lightweight check-ins, shared channel, escalate early through management when a date is at risk, with impact framed in business terms and options (reduce scope, accept a workaround, reprioritize).
5. Build for isolation: ports and adapters so a late dependency affects only an adapter; feature flags to ship without it.
6. Relationship building: understand their priorities and constraints; offer help (documentation, test environments, joint debugging); recognize contributions.
7. Contractual dependencies (external provider): SLAs, support contacts, sandbox access, and escalation paths; track provider incidents and review in vendor meetings.

Learning loop: after delivery, review which dependency issues occurred and improve intake (earlier engagement, shared roadmaps).

Measure: number of blocked days due to dependencies, early-warning escalations, contract test failures.
**Follow-ups:**
- When do you escalate and how?
- How do you handle an external vendor who will not prioritize you?
- What does your contract-first approach look like in practice?
**Pitfalls:**
- Waiting passively.
- Last-minute integration.
- Escalation without options or data.

## Financial Services Engineering

### Q152. Why is reliability engineering for financial-services messaging different from consumer notifications?
**Tags:** Financial Services, Senior, Concept
**Analogy:** Delivering a restaurant flyer versus delivering a bank statement and a fraud warning: nobody audits the flyer, but everyone depends on the warning.
**Model answer:**
Differences, and how they show up in design:
- Consequences of failure: a missed or late security alert, trade confirmation or account-change notice can cause financial loss, regulatory breaches or customer harm. Prefer "never lose" and "be explicit about uncertainty" (`DELIVERY_UNKNOWN`, reconciliation) over silent best-effort (CONTRACT sections 3.1 and 6).
- Message classes: distinguish categories (`SECURITY_ALERT`, `TRANSACTIONAL`, `SERVICE`, `MARKETING`, CONTRACT section 3.2) with different priority, quiet-hours behavior, consent rules, and SLOs. Security alerts must not be starved by marketing campaigns.
- Compliance and consent: marketing needs documented consent and opt-out; records of consent changes; suppression lists; communications rules around content approval (template four-eyes approval workflow, versioning and pinning so what was sent is reconstructable).
- Audit and record keeping: append-only audit trail of who changed what and when, message lifecycle history, retention periods dictated by regulation, ability to retrieve evidence of what was sent to whom and when (without storing excessive content: store variables and template version pins, CONTRACT section 4).
- Security and privacy: strict authentication and authorization, tenant isolation (business lines), data minimization and masking, encryption, vendor risk management of providers, incident reporting duties.
- Availability and resilience: defined RPO/RTO, tested disaster recovery, multi-provider redundancy, change management with approvals, release freezes around critical periods, evidence for auditors.
- Exactness: duplicates of financial notices are confusing and can trigger customer calls; idempotency matters.
- Operational discipline: runbooks, on-call, post-incident reviews, SLOs with error budgets, capacity planning for peak days (market events, statement cycles).

Trade-off: stronger controls add latency and process overhead; mitigate by automation (continuous compliance evidence).

Measure: SLO attainment per class, audit findings, mean time to detect and restore, duplicate and loss rates.
**Follow-ups:**
- Which design choice here most reflects "never lose a message"?
- How does four-eyes approval help?
- How do you prove what was sent months later?
**Pitfalls:**
- Treating all messages equally.
- Storing full message bodies and PII unnecessarily.
- Ignoring auditability.

### Q153. What does an audit trail for a messaging platform need to contain, and how do you make it trustworthy?
**Tags:** Financial Services, Senior, Concept
**Analogy:** A bank's ledger: entries are added in ink, never erased, and each says who did what and when.
**Model answer:**
Purpose: demonstrate who did what, when, and why: for compliance, incident investigation, dispute resolution and security forensics.

Content (CONTRACT section 4 `audit_event`): `id`, `tenant_id`, `actor` (user or service client), `actor_role`, `action` (a constant such as message accepted, cancelled, template approved, preference changed, suppression added, consumer paused), `entity_type` and `entity_id`, `correlation_id`, `detail_json` (what changed, reasons, before/after summaries without sensitive data), `occurred_at`. Delivery events are separate (`delivery_event`) and merged in the history view (`GET /messages/{id}/history`, CONTRACT section 5).

Trustworthiness:
- Append-only: no update or delete through the application; database privileges deny UPDATE/DELETE to the application role; retention-based purging only through a controlled job; consider write-once storage or exporting to immutable storage (WORM) for long-term retention.
- Written in the same transaction as the change where possible (the submission transaction writes audit with the message, CONTRACT section 6), so there is no change without a record and no record without a change.
- Tamper evidence: hash chaining (each record includes the hash of the previous record) or periodic signed digests for high-assurance requirements.
- Time: server-generated timestamps from a trusted clock (injected `Clock`), synchronized via NTP.
- Identity: record the authenticated principal from the token, never a client-provided name.
- Completeness: audit security-relevant reads (unmasking data), admin actions, authorization failures.
- Privacy: no secrets, minimal PII, masked identifiers; access to audit data restricted to `AUDITOR`, tenant admins and platform admins (CONTRACT section 5).
- Searchability: indexes `(entity_type, entity_id, occurred_at)` and `(tenant_id, occurred_at)` (CONTRACT section 4), exports for auditors.
- Retention: configured (`messaging.retention.audit-days`) and aligned with regulations.

Measure: audit coverage tests (each sensitive action produces an event), write failures (must be zero or alert), retrieval time.
**Follow-ups:**
- Why write audit in the same transaction?
- How do you prove the audit log was not altered?
- What should never be in an audit record?
**Pitfalls:**
- Audit via logs only.
- Editable audit rows.
- Sensitive data in `detail_json`.

### Q154. Legal asks you to preserve all messages for one customer because of a dispute, while your retention job deletes data after 90 days. What do you do?
**Tags:** Financial Services, Senior, Scenario
**Analogy:** A librarian told to put one customer's records in a locked box before the routine shredding day.
**Model answer:**
Clarify: scope (which customer, tenants, channels, date range), what data counts (message metadata, delivery events, audit, diagnostics, backups), the legal hold's start date, and who authorizes release.

Immediate actions:
1. Place a legal hold flag: a mechanism that exempts matching records from retention deletion. In the platform design the retention job (`RetentionJob`, CONTRACT sections 2 and 9) deletes by age; extend it to skip held entities, for example a `legal_hold` table keyed by tenant and recipient (or message id) which retention checks before deleting. If the mechanism does not exist yet, pause the job for the affected tenant right away, then implement a targeted exclusion.
2. Preserve related data: `message_request`, `message_attempt`, `delivery_event`, audit events, diagnostics documents (Mongo TTL index would delete them: copy to a hold collection or exempt via a separate collection), idempotency records if relevant; consider backups and archives.
3. Export evidence in a controlled format with checksums and a chain of custody; access restricted and audited.
4. Document who requested the hold, scope, approver, timestamps; implement release procedure with legal approval.

Design points:
- Partition-based retention (drop of partitions) conflicts with holds: need to copy held rows out before dropping or detach partitions to archive.
- Data minimization still applies: hold only what is in scope; do not copy more PII than necessary.
- Deletion requests from the data subject (privacy rights) may conflict with a hold: legal and privacy teams decide; the platform supports flags to restrict deletion.

Testing: unit test that held records survive a retention run; audit that holds and releases are logged.

Measure: hold coverage verification, time to implement a hold, number of active holds.

<!-- in-project:b23 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/scheduler/RetentionJob.java`
>
> Every purge query includes this predicate. A hold (scope TENANT or MESSAGE) is active while `released_at IS NULL`; table `legal_hold` comes from migration `V200__bulk_ledger_legal_hold.sql`.
>
> ```java
> private static final String NOT_HELD = "not exists (select 1 from legal_hold h where h.released_at is null "
>         + "and h.tenant_id = m.tenant_id and (h.scope = 'TENANT' or (h.scope = 'MESSAGE' and h.entity_id = m.id)))";
> ```

**Follow-ups:**
- How do you handle TTL indexes in Mongo?
- What about database backups?
- How do you prove the preserved data was not altered?
**Pitfalls:**
- Letting the retention job delete evidence.
- Holding more data than required.
- No audit of hold and release.

### Q155. Design disaster recovery for the messaging platform. How do you choose RPO and RTO, and what happens to in-flight messages?
**Tags:** Financial Services, Staff, Scenario
**Analogy:** A backup power plant and an evacuation plan: you decide how much work you can afford to lose and how long the lights may stay off, then rehearse.
**Model answer:**
Clarify business requirements per message class: security alerts and transactional messages might require RTO of minutes and RPO near zero; marketing can tolerate hours. Cost grows steeply as targets tighten.

Options (summarized; see the system design walkthrough on multi-region DR):
- Backup and restore: cheapest, RPO of hours, RTO of hours.
- Warm standby (pilot light): replicated database and a scaled-down second region; RTO tens of minutes, RPO seconds to minutes with asynchronous replication.
- Active-active: lowest RTO, highest complexity and conflict handling; for messaging consider active-passive with a fast failover rather than multi-writer databases.

Components:
- Database (PostgreSQL 16, ADR-0005): streaming replication to the standby region (asynchronous gives RPO of seconds, may lose the last commits; synchronous gives zero RPO at latency cost); managed failover, tested promotion; WAL archiving for point-in-time recovery.
- Broker (RabbitMQ): messages in queues are not necessarily replicated across regions. The outbox in the database is the durability backstop: after failover, unpublished `outbox_event` rows are published, and messages in `QUEUED` state without progress are re-enqueued by the sweeper/replay; idempotent consumers make this safe (CONTRACT section 6).
- Redis: rebuild; fallback paths exist (`ResilientCoordination`).
- Providers and callbacks: callback endpoints and secrets configured for the standby region (DNS or provider-side URL failover); provider rate limits must be shared or split between regions to avoid exceeding them.
- Config and secrets replicated; infrastructure as code for the standby region.

In-flight messages after failover: possible loss of the last RPO window of commits (accepted messages the client believes were accepted): clients must use idempotency keys and retry on connection failures; reconciliation compares provider reports with our records to find messages sent but not recorded (duplicates risk) and recorded but not sent (stuck).

Practice: regular DR drills with measured RTO/RPO, runbooks, failback plan, and monitoring of replication lag.

Honesty: DR assets in this repository are design artifacts; no real multi-region failover was executed here.

Measure: replication lag, drill RTO/RPO results, reconciliation exceptions after failover.
**Follow-ups:**
- How do you avoid split brain?
- What do you do about provider rate limits across regions?
- How often do you test failover?
**Pitfalls:**
- Untested DR plans.
- Assuming the broker data survives.
- Ignoring provider and callback configuration.

### Q156. For a trade confirmation message, would you accept a rare duplicate rather than risk a lost message? How do you decide and document the policy?
**Tags:** Financial Services, Staff, Scenario
**Analogy:** If a fire alarm might ring twice or not at all, you choose twice; if a restaurant flyer might arrive twice or not at all, you choose not at all.
**Model answer:**
Clarify the business impact of each failure, with product, compliance and operations: a lost confirmation might breach regulatory timeliness or leave the customer unaware; a duplicate causes confusion and extra support contacts but is typically harmless if the content is identical and clearly the same event.

Decision framework per category (CONTRACT section 3.2):
- `SECURITY_ALERT`: favor delivery over uniqueness: duplicates tolerated; retries allowed even after ambiguous outcomes after a short wait; multiple channels can be used if consent permits.
- `TRANSACTIONAL` (trade confirmations): at-least-once with strong deduplication: avoid duplicates by idempotency refs and reconciliation first; if the provider cannot confirm status within the time bound, allow a retry rather than leaving it unsent, since regulatory timeliness is typically stricter than duplicate tolerance; include the trade reference in the content so duplicates are recognizable as the same.
- `MARKETING`: favor no duplicates and no sending when uncertain: miss rather than double-send; compliance and reputational risk.

Mechanics: ambiguous outcomes land in `DELIVERY_UNKNOWN`, then `ReconciliationService` queries the provider using the stable idempotency ref; a per-category `ambiguousOutcomePolicy` and time bound decide whether to wait, retry, or escalate (CONTRACT section 6 sets email and SMS to wait and reconcile, push to retry). For categories where late is worse than duplicate, a configurable "retry after N seconds if still unknown" option exists as a documented policy, not hidden code.

Documentation and governance: write the policy in an ADR, get sign-off from compliance and business owners, expose the policy in runbooks and support tooling, track duplicates and misses as metrics, and review after incidents.

Measure: duplicate rate and loss rate per category, time in `DELIVERY_UNKNOWN`, customer contacts about duplicates.
**Follow-ups:**
- Who owns this decision?
- How do you let support see why a duplicate occurred?
- Would you ever send a transactional message on two channels at once?
**Pitfalls:**
- One global retry policy.
- Hiding the policy in code with no sign-off.
- Claiming zero duplicates and zero loss.

### Q157. How do change management and release freezes work in finance, and how do you keep delivery fast?
**Tags:** Financial Services, Intermediate, Concept
**Analogy:** Airports allow flights continuously but slow takeoffs during storms, using checklists so safe flying is routine, not exceptional.
**Model answer:**
Regulated firms use change management to control risk: changes are classified by risk (standard, normal, emergency), reviewed (CAB or automated policy), scheduled, and recorded with evidence. Release freezes (quarter end, tax season, market events) restrict non-essential production changes.

Keeping delivery fast within controls:
- Automate evidence: the CI/CD pipeline produces test results, security scan reports, approvals, and deployment records attached to change tickets automatically.
- Standard changes: pre-approve low-risk, well-understood patterns (for example config toggles, patch releases passing all gates) so they flow without a meeting.
- Small, frequent releases reduce risk per change versus infrequent big-bang deployments; DORA data supports this.
- Feature flags decouple deploy from release: deploy dark during the freeze window with no behavior change, enable after the freeze; or enable for a pilot tenant only.
- Progressive delivery: canary and blue-green with automated rollback and SLO gates (Q107).
- Separation of duties: author, approver and deployer roles distinct; protected branches, required reviews, environment approvals in the pipeline.
- Emergency path: documented fast lane for incident fixes with after-the-fact review.
- Freeze handling: allow fixes for severity incidents and security patches; plan capacity ahead; use the freeze for work that does not need deployment (testing, documentation, DR drills).
- Risk-based scope: heavier controls for money-moving and consent logic, lighter for UI text.
- Communication: calendars of freezes, release notes, stakeholder notifications.

Measure: lead time within controls, change failure rate, percentage of standard changes, emergency change count.
**Follow-ups:**
- How do feature flags interact with audit requirements?
- What is a standard change?
- How do you make approvals not a bottleneck?
**Pitfalls:**
- Manual evidence gathering.
- Batching many changes before a freeze.
- Using the emergency path routinely.

## Onboarding, Architecture and Full-Stack Delivery

### Q158. How do you onboard new engineers onto this platform and build a documentation and knowledge-sharing culture?
**Tags:** Leadership, Intermediate, Concept
**Analogy:** Giving a new hire a map, a first easy trail, and a buddy, so they reach the summit sooner without getting lost.
**Model answer:**
Goals: productive contribution in weeks, shared understanding, and resilience against single points of knowledge.

Onboarding plan (30-60-90 days):
- Before day 1: access, laptop, repository clone instructions that work, a buddy assigned.
- Week 1: read the architecture overview, the contract and ADRs, run the system locally (profile `local`: H2, in-memory bus, simulators; a script-driven start), send a first message through the UI and API, and trace it via logs and the message history view. A documented "golden path" with a verification checklist.
- Weeks 2 to 4: first small production change (a bug fix or a simple endpoint) with a mentor, learning the pipeline, code review norms, and observability tools; shadow on-call.
- Days 30 to 90: a medium feature end to end, a design doc authored with review, join the on-call rotation with a runbook-guided first incident; present learning to the team.

Documentation culture: docs live next to the code (`docs/` with design, ADRs, runbooks, API spec); each PR updates docs when behavior changes (checklist); runbooks tested during drills; diagrams as code (Mermaid) to ease updates; a glossary for domain terms; honest labeling of what has and has not been verified (CONTRACT section 12).

Knowledge sharing: pair and mob programming for complex areas; brown-bag talks and demo days; rotation of code ownership; blameless post-mortems shared widely; internal Q&A channels with answers captured into docs; records of design decisions; avoid hero culture and bus-factor-1 modules (track ownership coverage).

Measure: time to first PR merged, time to first on-call shift, onboarding survey, documentation freshness (last-updated audits), bus factor per component.
**Follow-ups:**
- How do you keep docs from going stale?
- What would be in the first-week checklist?
- How do you onboard remote hires?
**Pitfalls:**
- Docs in a wiki disconnected from code.
- Throwing new hires into on-call without support.
- Tribal knowledge held by one person.

### Q159. Should the company build its own messaging orchestration platform or buy a vendor product? How do you decide?
**Tags:** Architecture, Staff, Scenario
**Analogy:** Cook at home or hire a caterer: depends on how often you need it, how special the menu is, and what else your time could create.
**Model answer:**
Clarify: strategic importance (is messaging a differentiator or commodity), volume and growth, compliance and data-residency constraints, integration needs (many internal systems, tenant model, templates, approval workflows), time to market, team capacity, and existing vendor relationships.

Options: (1) use channel providers directly from each application, (2) buy a communications platform (CPaaS or customer-engagement suite), (3) build a thin orchestration layer (what this reference implements) on top of commodity channel providers, (4) full custom including delivery infrastructure (almost never sensible).

Criteria and trade-offs:
- Differentiation: orchestration policies (consent, quiet hours, fallback, priority, audit) are often company-specific and regulated; owning them gives control. Delivery infrastructure (SMTP reputation, carrier relationships, APNs/FCM integration) is commodity; buy it.
- Total cost: build cost, ongoing maintenance, on-call, security reviews; versus license fees that scale with volume, customization limits and integration effort. Include opportunity cost.
- Compliance and data: data residency, audit requirements, vendor risk assessments, exit options; ability to export data and switch providers (the provider ports in this design reduce lock-in).
- Reliability and scale: vendor SLAs versus your own SRE capacity; multi-provider redundancy.
- Speed: vendor products can be faster for the first release but may hit limits (templates approval workflow, tenant isolation, custom policies).
- Risk: build risk (underestimated edge cases such as ambiguous outcomes, reconciliation, callbacks) versus vendor risk (roadmap, price changes).

Typical recommendation: hybrid: buy delivery channels (multiple providers behind an abstraction), build the orchestration core that encodes policy, audit and integration, and revisit annually with TCO data. Use a time-boxed proof of concept against must-have requirements before deciding, and capture the decision in an ADR.

Measure: TCO, time to onboard a new application, incident count, vendor SLA attainment.
**Follow-ups:**
- What would make you choose a full vendor suite?
- How do you reduce vendor lock-in?
- What are hidden costs of building?
**Pitfalls:**
- Underestimating the long tail (reconciliation, deliverability, compliance).
- Buying without testing must-have requirements.
- Treating build versus buy as permanent.

### Q160. In the Angular console, two operators edit the same recipient preferences. How do you handle the optimistic-lock conflict in the UI, and how do Signals and interceptors fit?
**Tags:** Angular, Intermediate, Scenario
**Analogy:** Two people editing the same shared document: the app must say "someone else changed this, here is what is different" instead of silently overwriting.
**Model answer:**
Clarify what the backend provides (CONTRACT section 5): `GET /recipients/{id}/preferences` returns a `version`; `PUT` must include it; a stale write returns HTTP 409 with code `CONFLICT_OPTIMISTIC_LOCK` in the `ApiError` payload.

Frontend design (CONTRACT section 10, Angular 20 standalone components with Signals and Reactive Forms):
- Keep the loaded preferences and `version` in the component state (signals: `preferences = signal<Preferences | null>(null)`, `saving = signal(false)`, `conflict = signal<Preferences | null>(null)`); the form is a typed Reactive Form patched from the signal.
- On save, send the form values with the version. The error interceptor normalizes `ApiError` into an application error type (`HttpInterceptorFn` that maps status and `code` to actionable messages) while letting the component handle the conflict code specially.
- On conflict, do not discard the user's edits: fetch the latest server version and present a dialog: "These preferences were changed by someone else", show both versions (differences highlighted), and offer choices: reload and discard my changes, apply my changes on top of the latest (re-submit with the new version after the user confirms), or cancel. For safety-critical fields such as opt-outs, bias toward preserving an opt-out: never silently re-enable marketing.
- Use a `computed` signal for dirty state and for the diff; disable the save button while saving to avoid duplicate submissions; use an idempotency-safe approach (version makes double submits fail harmlessly).
- Accessibility and UX: announce the conflict dialog via ARIA live region, keep focus management, and keep text actionable.
- Guards and role-aware UI are UX only; the server enforces roles (CONTRACT section 10).

Testing: Karma/Jasmine unit tests with `HttpTestingController` returning a 409 and asserting the conflict state and dialog; interceptor tests for mapping; component tests for the merge choices.

Measure: conflict rate, how users resolve them, support tickets about lost edits.
**Follow-ups:**
- Why use `If-Match`/ETag as an alternative?
- How do Signals differ from RxJS for this state?
- How would you show field-level diffs?
**Pitfalls:**
- Silently reloading and losing the user's edits.
- Retrying automatically with the new version.
- Relying on UI guards for security.

### Q161. Walk through delivering a new "resend failed message" feature end to end as a full-stack engineer: API, backend, data, UI, tests, and rollout.
**Tags:** Full-Stack Delivery, Senior, Scenario
**Analogy:** Building one new room in a house: the plans, the plumbing, the wiring, the paint, the inspection, and the moving-in day all have to line up.
**Model answer:**
Clarify the requirement with product and compliance: who may resend (operators), which statuses (`FAILED`, `DEAD_LETTERED`), whether consent is rechecked, whether the original message or a new one is created, and audit needs. The platform already defines `POST /api/v1/messages/{id}/requeue` for `OPERATOR` and `PLATFORM_ADMIN` (CONTRACT section 5), so the question is how to extend responsibly, not to duplicate.

Spec first (spec-driven development): acceptance criteria (Given a FAILED message, when an operator requeues, then status becomes QUEUED, attempt history preserved, audit event recorded, policy re-evaluated at dispatch). Update OpenAPI, error codes, the state machine table if needed (`FAILED to QUEUED` is allowed for operators only, CONTRACT section 3.1).

Backend:
- Controller in `web/` with DTO records and role check; `NotificationService` method with `@Transactional`: load with tenant scope (404 for foreign), validate transition via `MessageStateMachine`, CAS update `WHERE status IN (FAILED, DEAD_LETTERED)`, write `outbox_event` and `audit_event` in the same transaction; `OutboxPublisher` enqueues. Idempotency: a repeated click must not enqueue twice (CAS ensures one winner; second returns 409).
- Reason: carry an operator-supplied reason into the audit detail (validated length, no PII).
- Metrics and logs: transitions counter, audit with correlation id.
- Consider rate limiting bulk requeues and a guard for messages older than expiry.

Data: no schema change if existing columns suffice; if adding `requeue_count`, use an additive Flyway migration (expand-and-contract), index review.

Frontend: a "Requeue" action on the failed and dead-letter lists and the message detail page, visible by role (UX only), confirmation dialog with a reason field, optimistic UI avoided (wait for server), handle 409 gracefully (already requeued or state changed), refresh status via signals, accessible controls.

Tests: unit tests for the service and state machine; `@WebMvcTest` for authorization and error mapping (including tenant isolation); integration test through the in-memory bus (requeue then dispatch); concurrency test (two simultaneous requeues, exactly one wins); frontend unit tests with `HttpTestingController`; update API e2e tests.

Rollout: feature flag, deploy behind it, enable for one tenant, watch `messaging.transitions{from="FAILED",to="QUEUED"}`, DLQ depth and error rates, update runbooks and release notes.

Measure: lead time, defects, usage, DLQ depth trend.
**Follow-ups:**
- Should requeue reuse the same message id or create a new message?
- How do you avoid operators causing duplicate sends?
- What would change for a bulk requeue of 100,000 messages?
**Pitfalls:**
- Duplicating an existing endpoint with different semantics.
- UI-only authorization.
- No audit or idempotency on a state-changing action.
