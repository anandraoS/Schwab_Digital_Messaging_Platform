# Study Plans

Pick the plan that matches the time you have. Both end with spoken practice, because interviews are spoken.

## One-week plan (about 3 hours per weekday, 4 to 5 hours on weekend days)

| Day | Focus | Read | Practise |
|---|---|---|---|
| 1 | Orientation and the platform story | About this guide, Project map, Talking points, Diagram atlas | Give the 10-minute demo script aloud; draw the state machine from memory |
| 2 | Java and Spring | JVM and GC, Concurrency, Spring DI and transactions | Q01 to Q24 (do the scenario ones first) |
| 3 | REST and messaging | REST API design, Messaging chapter | Q25 to Q46 and Q47 to Q70 (skim concept questions, speak scenario ones) |
| 4 | Data | Databases, Caching and pools, Migrations and retention | Q71 to Q94, Q162, Q166, Q171, Q172 |
| 5 | Cloud, DevOps, observability | Cloud and DevOps, Observability and SRE | Q95 to Q107, Q119 to Q132, Q169, Q170 |
| 6 | Security and AI | Security, AI-assisted engineering, Safe refactoring | Q108 to Q118, Q133 to Q142, Q167, Q168 |
| 7 | Leadership, system design, mock | System design walkthroughs, Cheat sheets | Q143 to Q161, one full mock: design the notification platform in 40 minutes |

Minimum viable version when you have only two evenings: the Talking points chapter, the cheat sheets, the Messaging chapter, and the ten scenario questions you find hardest (for example Q05, Q12, Q29, Q50, Q51, Q66, Q68, Q81, Q111, Q130).

## Three-week plan (about 1.5 to 2 hours per weekday, 3 hours on one weekend day)

### Week 1: foundations and the Java stack

| Day | Focus | Output |
|---|---|---|
| Mon | Platform tour and demo | Run or walk through the demo; list the 7 personas and the 12 lifecycle states |
| Tue | JVM memory and garbage collection | One-page note: G1 versus ZGC, how to read a GC log; Q01, Q02, Q22 |
| Wed | Concurrency and thread pools | Size a pool with Little's Law on paper; Q03 to Q09 |
| Thu | Spring DI, proxies, transactions | Explain why `@Transactional` fails on self-invocation; Q10 to Q13 |
| Fri | Hibernate | Diagnose N+1 and lost update on a whiteboard; Q14 to Q17 |
| Sat | Testing, exceptions, configuration | Q18 to Q21, Q23, Q24 |
| Sun | Review | Re-do the five questions you scored lowest |

### Week 2: distributed systems, data, channels

| Day | Focus | Output |
|---|---|---|
| Mon | REST design and idempotency keys | Draw the idempotency decision flow; Q25 to Q33 |
| Tue | Channels and policy (email, SMS, push, quiet hours) | Q34 to Q46 |
| Wed | Delivery semantics, outbox, duplicates | Q47 to Q54, Q62 |
| Thu | Retry, DLQ, backpressure, circuit breaker, timeouts | Q55 to Q60, Q65, Q68 |
| Fri | Ambiguous outcomes, reconciliation, eventual consistency | Q51, Q63, Q64, Q66, Q163, Q164 |
| Sat | Data: indexes, isolation, Oracle, Mongo, migrations | Q80 to Q94, Q166 |
| Sun | Review and a spoken system design (Q47) | Record yourself; check the pitfalls |

### Week 3: platform, security, SRE, AI, leadership

| Day | Focus | Output |
|---|---|---|
| Mon | Docker, Cloud Foundry, Kubernetes | Compare CF and K8s from memory; Q95 to Q103 |
| Tue | CI/CD, Jenkins, Bamboo, rollback | Q104 to Q107, Q170 |
| Wed | OAuth2, JWT, OWASP, webhooks | Q108 to Q118 |
| Thu | Observability, SRE, performance testing | Q119 to Q132, Q169 |
| Fri | AI-assisted engineering | Q133 to Q142, Q167, Q168 |
| Sat | Leadership and financial services | Prepare two STAR stories; Q143 to Q161 |
| Sun | Full mock interview | One system design, three scenarios, two behavioural |

## Daily routine that works

1. Ten minutes: reread yesterday's cheat sheet.
2. Forty minutes: one chapter.
3. Thirty minutes: questions aloud, covering the answer.
4. Ten minutes: write the three things you got wrong.

## Self-check at the end

- Can you state, in one sentence each, why exactly-once across a provider is impossible, why the outbox exists, and why a timeout becomes `DELIVERY_UNKNOWN` instead of `FAILED`?
- Can you name three places in this project where the database, not application code, decides a race?
- Can you say what was and was not executed, without being asked?
