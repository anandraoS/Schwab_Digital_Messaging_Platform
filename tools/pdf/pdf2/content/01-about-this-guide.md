# About This Guide

## What this document is

This is the second of two PDFs for the **Digital Messaging Platform** reference project: a deep-dive and interview study guide for a senior Java engineer role on an enterprise omnichannel messaging and notification platform. The first PDF describes the system; this one teaches the engineering knowledge behind it and drills it with a bank of interview questions with detailed model answers.

The role is only the context. The platform is an **independent reference implementation**: it is not a copy of any employer's internal system, every record in it is synthetic, and no real e-mail, SMS or push provider is ever called.

## How the guide is organised

| Part | Content | Use it to |
|---|---|---|
| Front matter | This chapter, study plans, project map with a ten-minute demo, talking points, diagram atlas | Orient yourself and plan your time |
| I. Java and Spring Boot | JVM and garbage collection, concurrency and thread pools, Spring DI, transactions and Hibernate, REST API design and errors | Build the language and framework base |
| II. Messaging and distributed systems | Queue semantics, at-least-once delivery, idempotency, duplicates, ordering, retry and DLQ, backpressure, circuit breakers, provider timeouts, eventual consistency, reconciliation | Reason about reliability |
| III. Databases and data | Relational modelling, Oracle, MongoDB, indexes and plans, isolation, connection pools, caching, migrations, retention and archival | Defend data decisions |
| IV. Cloud, DevOps and operations | Docker, Cloud Foundry, Kubernetes, CI/CD with Jenkins and Bamboo, deployment and rollback, observability, SRE, performance testing | Talk about running software |
| V. Security | OAuth2, OIDC, JWT validation, OWASP API Security, webhook validation, secrets, data minimisation, audit, safe templates | Show a security mindset |
| VI. AI-assisted engineering | Copilot workflows, spec-driven development, reusable context, token optimisation, reviewing AI code, multi-agent work, safe refactoring and upgrades | Answer the AI questions credibly |
| VII. System design and revision | Four whiteboard walkthroughs, cheat sheets | Rehearse and revise |
| VIII. Question bank | 172 questions organised by topic, with model answers, follow-ups and pitfalls | Practise out loud |
| Appendix | Glossary | Look up terms |

## How to study with it

1. Read a chapter, then close it and explain the main idea to an imaginary child using the analogy at the top of the chapter. If you cannot, read it again.
2. Do the questions of that topic. Cover the model answer, speak for 90 seconds, then compare. The follow-ups are the questions an interviewer asks next.
3. In every answer look for the **In this project** boxes. They name a real file in the repository and show a real excerpt. In an interview, one concrete sentence such as "the outbox publisher claims a row with a compare-and-set update, so three instances cannot publish the same row" is worth more than a paragraph of theory.
4. Use the pitfalls as a checklist of things not to say.

### How a question is laid out

| Block | Meaning |
|---|---|
| Question band with a Q number and tags | Topic, level (foundational to staff) and type (concept or scenario) |
| Analogy | A one-line, plain-language picture; say it first, then go technical |
| Model answer | Structured answer, roughly 60 to 120 seconds spoken, with trade-offs and measures |
| Follow-ups | The next questions to expect |
| Pitfalls | Shallow or false statements to avoid |

Scenario questions start from a symptom ("the queue is growing", "customers received two emails") and test whether you clarify, form hypotheses, and move to a safe fix. The same pattern works every time: **clarify, measure, hypothesise, fix safely, prevent, explain the trade-off**.

## Honesty note: what was and was not executed

A guide that overstates its evidence is worse than none. These statements are deliberately plain.

| Statement | Status |
|---|---|
| The code excerpts and file paths in the **In this project** boxes | Copied from the repository source at the time of writing |
| Behaviour described as implemented | Taken from the code and from `docs/design/DEVIATIONS.md`, which records where the implementation differs from the original contract (for example a policy rejection returns `202` with status `REJECTED`, and a replayed callback returns `200`). Where an older answer disagreed, the implemented behaviour wins |
| Docker images, Kubernetes manifests, Helm chart, Cloud Foundry manifests | Written and statically checked only. **Not executed**: no Docker, Kubernetes or Cloud Foundry environment was used |
| Jenkins and Bamboo pipelines | Written, **not executed** |
| k6 load scripts | Written, **not executed** (k6 was not installed). The guide quotes no throughput or latency measurement |
| RabbitMQ, Redis, real PostgreSQL, real MongoDB | **Not executed**. Local and test profiles use in-memory substitutes and H2 in PostgreSQL mode |
| Capacity numbers in worked examples | Arithmetic for practice, not measurements |
| Test results | This guide claims none. A result may be quoted only if a report under `docs/validation/` records the command and output |

When you talk about the project in an interview, use the same wording: "designed and unit and integration tested against simulators; container and cluster assets validated statically and not deployed".

## Conventions

- `CONTRACT section N` refers to `docs/design/CONTRACT.md`; `ADR-nnnn` refers to `docs/adr/`.
- Class and file names are real unless a code block is labelled illustrative.
- Illustrative values (retention days, pool sizes) are examples of a reference system, not regulatory or vendor advice.
