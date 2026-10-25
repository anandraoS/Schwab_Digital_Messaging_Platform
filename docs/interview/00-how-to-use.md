# How to Use This Interview Bank

This bank prepares you for a senior Java full-stack interview on an enterprise omnichannel messaging and notification role. It is built around a **reference implementation** (see `docs/design/CONTRACT.md`, called "CONTRACT" below). The reference implementation is inspired by such a role. It does not reproduce any employer's internal systems, all data is synthetic, and no real provider is ever called.

## 1. Honesty note

- Everything here is study material tied to a reference design. Nothing in these files claims that a command, test, build, load run, or deployment was executed. Where a doc elsewhere in the repo reports a validation result, it must cite the exact command and whether it ran (CONTRACT section 12).
- Docker, Kubernetes, Cloud Foundry, k6, Testcontainers, and real PostgreSQL/RabbitMQ/Redis/MongoDB are labelled "not executed" in CONTRACT section 0.
- Numbers in capacity examples are worked arithmetic for practice, not measurements.
- A PDF edition (`docs/pdf/Schwab_Senior_Java_Engineer_Deep_Dive_and_Interview_Guide.pdf`) is built from these Markdown files by `tools/pdf/pdf2/build-pdf2.mjs`. The Markdown files remain the source of truth.
- Project code is referenced only by the package and class names listed in CONTRACT section 2. Design claims link to "CONTRACT section X" or an ADR (for example ADR-0001 RabbitMQ choice, ADR-0005 PostgreSQL, ADR-0007 template version pinning).

## 2. File index

| File | Purpose |
|---|---|
| `00-how-to-use.md` | This guide: study plans, formats, answer frameworks |
| `01-java-spring-questions.md` | Java, JVM, Spring, JPA questions |
| `02-rest-and-channels-questions.md` | REST API design, errors, email/SMS/push channels |
| `03-distributed-messaging-questions.md` | Queues, idempotency, retries, delivery semantics |
| `04-batch-and-data-questions.md` | Spring Batch, Oracle/PostgreSQL/MongoDB, data modelling |
| `05-platform-and-security-questions.md` | Cloud Foundry, Kubernetes, CI/CD, OAuth2, OWASP |
| `06-performance-observability-ai-questions.md` | Performance, SRE, metrics, AI-assisted engineering |
| `07-leadership-and-fintech-questions.md` | Leadership, stakeholder, regulated-industry behaviour |
| `deep-dive/java-jvm-gc-concurrency.md` | Deep dive: JVM, GC, concurrency |
| `deep-dive/spring-di-transactions-hibernate.md` | Deep dive: Spring DI, transactions, Hibernate |
| `deep-dive/rest-api-design-and-errors.md` | Deep dive: REST design and error model |
| `deep-dive/messaging-and-distributed-systems.md` | Deep dive: messaging and distributed systems |
| `deep-dive/databases-oracle-mongo.md` | Deep dive: Oracle, PostgreSQL, MongoDB |
| `deep-dive/cloud-devops-pcf-k8s.md` | Deep dive: Cloud Foundry, Kubernetes, DevOps |
| `deep-dive/security-oauth-owasp.md` | Deep dive: OAuth2, JWT, OWASP |
| `deep-dive/observability-sre-performance.md` | Deep dive: observability, SRE, performance |
| `deep-dive/ai-assisted-engineering.md` | Deep dive: working with AI coding assistants |
| `08-additional-scenarios-questions.md` | Caching, eventual consistency, migrations, performance-test planning, pipelines, safe AI change |
| `deep-dive/caching-and-connection-pools.md` | Deep dive: caching patterns, Redis fallback, pool sizing |
| `deep-dive/schema-migrations-retention.md` | Deep dive: Flyway, expand and contract, retention, archival |
| `deep-dive/ai-safe-refactoring-and-upgrades.md` | Deep dive: AI-assisted refactoring and dependency upgrades |
| `system-design-walkthroughs.md` | Four full whiteboard walkthroughs with capacity math |
| `cheat-sheets.md` | One-page tables for last-minute review |
| `glossary.md` | 90+ terms, one line each |

## 3. Question format

Every question in the banks follows the same five blocks, so you can drill by covering one block at a time.

| Block | What it contains | How to practise |
|---|---|---|
| Tags | Topic, level (junior/mid/senior/staff), and type (concept, scenario, coding, behavioural) | Filter by weak topics |
| Analogy | A kid-friendly one-line analogy for harder concepts | Say it aloud first, then go technical |
| Model answer | A structured answer, usually 60 to 120 seconds spoken, tied to CONTRACT where relevant | Cover it, answer, compare |
| Follow-ups | The 2 to 4 questions an interviewer is likely to ask next | Answer each in 30 seconds |
| Pitfalls | Common wrong or shallow answers, and claims that sound confident but are false | Check you avoided each |

## 4. Study plans

### 4.1 Two-week plan (about 2 hours per weekday, 3 hours per weekend day)

| Day | Focus | Files |
|---|---|---|
| 1 | Orientation, lifecycle, glossary skim | CONTRACT sections 3, 5, 6; `glossary.md` |
| 2 | Java and JVM | `01-java-spring-questions.md`, `deep-dive/java-jvm-gc-concurrency.md` |
| 3 | Spring, transactions, JPA | `deep-dive/spring-di-transactions-hibernate.md`, finish `01` |
| 4 | REST and channels | `02-rest-and-channels-questions.md`, `deep-dive/rest-api-design-and-errors.md` |
| 5 | Messaging and idempotency | `03-distributed-messaging-questions.md` |
| 6 | Walkthrough 1 (notification platform), practise aloud | `system-design-walkthroughs.md` |
| 7 | Rest, review `cheat-sheets.md` | `cheat-sheets.md` |
| 8 | Batch and data | `04-batch-and-data-questions.md`, `deep-dive/databases-oracle-mongo.md` |
| 9 | Distributed systems depth | `deep-dive/messaging-and-distributed-systems.md` |
| 10 | Platform and security | `05-platform-and-security-questions.md`, `deep-dive/security-oauth-owasp.md` |
| 11 | Cloud and DevOps | `deep-dive/cloud-devops-pcf-k8s.md`; walkthrough 2 and 3 |
| 12 | Performance, observability, AI | `06-performance-observability-ai-questions.md`, two deep dives |
| 13 | Leadership and behavioural (write 6 STAR stories) | `07-leadership-and-fintech-questions.md` |
| 14 | Mock interview: one design (walkthrough 4), one scenario, two behavioural | All cheat sheets |

### 4.2 Four-week plan (about 1 hour per weekday, 2 hours on one weekend day)

| Week | Theme | Goals |
|---|---|---|
| 1 | Language and framework | Banks 01 and 02, deep dives on JVM, Spring, REST. Be able to explain transactions, proxies, N+1, and the error model without notes |
| 2 | Messaging and data | Banks 03 and 04, deep dives on messaging and databases. Draw the outbox flow and the state machine from memory |
| 3 | Platform, security, operations | Banks 05 and 06, deep dives on cloud, security, observability. Explain OAuth2 flows, probes, and golden signals |
| 4 | Integration and rehearsal | Bank 07, all four walkthroughs aloud with a timer, two full mocks, daily cheat-sheet review, glossary self-quiz |

Spaced repetition tip: on day N, also revisit questions you missed on day N-1, N-3, and N-7.

## 5. Answer frameworks

### 5.1 STAR / CAR for leadership and behavioural questions

STAR: **Situation, Task, Action, Result**. CAR is the compact form: **Context, Action, Result**. Use CAR when the interviewer is short on time.

| Step | Content | Time |
|---|---|---|
| Situation / Context | One or two sentences: team, system, constraint | 10 s |
| Task | Your specific responsibility (skip in CAR) | 10 s |
| Action | What you did, in first person, three concrete steps, including a trade-off you weighed | 45 s |
| Result | A measurable outcome, then what you would do differently | 20 s |

Rules: say "I", not "we", for your actions. Use only outcomes you can defend. Never invent metrics. If you lack a number, describe the direction and how it was observed.

### 5.2 Whiteboard framework for system design

Use the same eight headings every time. The walkthroughs file follows this order.

| Step | Question to answer | Typical time |
|---|---|---|
| 1. Requirements | Functional and non-functional, in and out of scope | 5 min |
| 2. Capacity | Messages per day, per second, peak factor, storage, in-flight concurrency (show arithmetic) | 5 min |
| 3. API | Endpoints, idempotency, async acknowledgment (202), error model | 5 min |
| 4. Data | Tables or collections, keys, indexes, partitioning, retention | 5 min |
| 5. Flow | Happy path, step by step, with the diagram | 10 min |
| 6. Failure modes | What breaks, how it is detected, how it recovers | 10 min |
| 7. Metrics | SLIs, SLOs, alerts, dashboards | 3 min |
| 8. Evolution | What changes at 10x, multi-region, or a new channel | 5 min |

Always state trade-offs explicitly: "I chose X over Y because Z, and the cost is W."

### 5.3 Scenario-answer framework

For "what would you do if..." questions (an incident, a design dilemma, a conflict):

1. **Clarify**: ask two or three scoping questions (blast radius, data at risk, deadline).
2. **Approach**: state the plan in order, safest and most reversible step first.
3. **Failure modes**: name what could go wrong with your own plan.
4. **Measure**: say which metric or signal proves it worked (use names from CONTRACT section 8 when relevant).
5. **Follow-ups**: volunteer the next question (prevention, runbook, post-incident review).

Worked mini-example: "Customers report delayed SMS."
Clarify: all tenants or one, one provider or all, since when. Approach: check `messaging.queue.depth` and `messaging.dispatch.delay`, then provider circuit state and `messaging.provider.calls` by outcome. Failure modes: provider rate-limiting versus our own limiter versus a stuck consumer. Measure: dispatch delay returns to its SLO. Follow-ups: add an alert and a runbook entry, consider a fallback provider.

## 6. Tips for delivery

- Lead with the analogy for hard concepts, then give the precise definition, then connect to the reference design.
- Give a number whenever you can, and show the arithmetic.
- Say what you would not do and why (for example, "never claim exactly-once across the provider boundary", CONTRACT section 6).
- When you do not know, say so, then reason from first principles.
- Practise out loud with a timer. Reading answers silently is not rehearsal.
