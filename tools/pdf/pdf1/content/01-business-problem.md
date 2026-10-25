# 1. Business Problem and Requirements

## 1.1 The problem in plain words

Imagine a large investment firm with dozens of applications: the brokerage website sends trade confirmations, the security team sends login alerts, the marketing team sends newsletters, the onboarding flow sends welcome emails. If every application talks to the email, SMS and push vendors directly, five things go wrong, and they go wrong in exactly the places where regulators, customers and auditors are watching.

| Symptom | Why it hurts | What the platform does about it |
|---|---|---|
| Each team re-implements consent and opt-out | A marketing text to an opted-out customer is a compliance incident | One ordered policy chain, applied at accept time and again at dispatch time (Chapter 11) |
| Naive retries create duplicate messages | A customer receives the same trade confirmation three times | Four layers of idempotency (Chapter 5) and no blind retry after an ambiguous result (Chapter 12) |
| Nobody can answer "was it delivered?" | Support cannot help; auditors cannot trace | An honest lifecycle with distinct accepted, provider-accepted, delivered and unknown states (Chapter 6) |
| Vendor outage stops the business | Password-reset codes do not arrive | Circuit breakers, backoff with jitter, dead-letter queue, operator replay (Chapter 13) |
| Vendor lock-in | Switching an SMS vendor means touching every application | Provider ports and adapters (Chapter 8) |

A kid-friendly analogy: think of the platform as a **post office with a very careful clerk**. You hand over a letter and get a receipt immediately (HTTP 202). The clerk writes the letter into a ledger first (the database), and only then puts it in the outgoing tray (the queue), so a power cut can never lose a letter that you hold a receipt for. The clerk checks that the addressee has not asked for no mail (consent), waits until morning for non-urgent letters (quiet hours), hands letters to a courier (provider), and never sends the same letter twice just because the courier said "I am not sure if I delivered it": the clerk phones the courier and asks first (reconciliation).

## 1.2 Goals and non-goals

| Goals | Non-goals |
|---|---|
| Accept messages fast (202) and never lose an accepted message | Real vendor integrations |
| Honest lifecycle: accepted versus sent-to-provider versus delivered versus unknown | Exactly-once across the provider boundary (impossible) |
| Policy enforcement (consent, suppression, quiet hours) at accept and at dispatch | Marketing campaign authoring UI |
| Operable: metrics, runbooks, replay, reconciliation | A claim of production compliance (see `docs/security/prod-vs-reference.md`) |
| Provider-agnostic and tenant-isolated | Multi-region active/active |

## 1.3 Stakeholders and personas

| Persona | Needs | Role claim |
|---|---|---|
| Application developer (service client) | Simple, idempotent API; clear, stable error codes | `ROLE_SENDER` |
| Template author and approver | Safe preview, versioning, four-eyes approval | `ROLE_TEMPLATE_AUTHOR`, `ROLE_TEMPLATE_APPROVER` |
| Operations engineer | Queue and dead-letter visibility, replay, provider controls | `ROLE_OPERATOR` |
| Tenant administrator | Preferences, suppressions, audit trail | `ROLE_TENANT_ADMIN` |
| Auditor | Read-only audit and delivery events | `ROLE_AUDITOR` |
| Platform administrator | Provider configuration, consumer pause | `ROLE_PLATFORM_ADMIN` |

The seven roles are the constants of `com.example.messaging.security.Roles` (`backend/src/main/java/com/example/messaging/security/Roles.java`):

```java
public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";
public static final String TENANT_ADMIN = "TENANT_ADMIN";
public static final String TEMPLATE_AUTHOR = "TEMPLATE_AUTHOR";
public static final String TEMPLATE_APPROVER = "TEMPLATE_APPROVER";
public static final String OPERATOR = "OPERATOR";
public static final String SENDER = "SENDER";
public static final String AUDITOR = "AUDITOR";
```

## 1.4 Business scenarios that drive the design

| Scenario | Category | Priority | Channel | Design consequence |
|---|---|---|---|---|
| Trade confirmation after an order fills | TRANSACTIONAL | NORMAL | EMAIL with SMS fallback | Idempotent submit, template pinning, fallback evaluated through consent |
| One-time login code | SECURITY_ALERT | CRITICAL | SMS or PUSH | Ignores quiet hours; prioritised queue |
| Account service notice | SERVICE | NORMAL or LOW | Any | Deferred during quiet hours at LOW or NORMAL |
| Monthly market commentary newsletter | MARKETING | LOW | EMAIL | Needs explicit opt-in; suppressed by opt-out and quiet hours; typically a bulk job |
| Fraud alert while the SMS vendor is down | SECURITY_ALERT | CRITICAL | SMS | Circuit breaker, retry, fallback channel, DLQ and replay |
| Late or duplicate vendor delivery receipt | any | n/a | any | HMAC, replay guard, monotonic rank rule |

## 1.5 Constraints that shaped the build

- The authoring machine has no Docker, Kubernetes, Cloud Foundry or k6, so those assets are statically validated only and labelled NOT executed everywhere in this document.
- The code must run with zero infrastructure: a single JVM with an in-memory bus, an H2 database in PostgreSQL mode, and in-memory substitutes for Redis and MongoDB. The same code uses real adapters under the `prod-like` profile.
- SQL must be portable (no PostgreSQL-only features) so that the Flyway migrations run on H2 and PostgreSQL and map to Oracle (`docs/database/oracle-mapping.md`).

## Reference material

{{embed:docs/leadership/prd.md|title=Product requirements document (example)}}

{{embed:docs/leadership/feature-specification.md|title=Feature specification|skip=Acceptance}}
