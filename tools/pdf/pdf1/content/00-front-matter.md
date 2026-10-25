# How to Read This Document

This is **PDF 1** of two companion documents for the Digital Messaging Platform reference implementation. It explains the architecture and the workflows: how a notification request travels from an API call to a delivered (or honestly unresolved) message, which design decisions shape that journey, and how the platform is built, deployed, observed and operated. PDF 2 is the interview preparation guide.

The platform is an **independent reference implementation** of an enterprise omnichannel messaging and notification orchestration platform for EMAIL, SMS and PUSH. All data is synthetic, no real provider is ever called, and provider vendors are replaced by simulators. It is inspired by the kind of messaging role found in large financial-services firms; it does not reproduce any employer's internal systems.

## Reading paths

| If you are | Start with | Then read |
|---|---|---|
| An engineer joining the project | Chapters 3, 5, 6, 7 | 8 to 14, then 22 |
| A solution architect | Chapters 1 to 3, 6 | 15 to 20, 26 |
| An SRE or operator | Chapters 7, 12, 13, 20 | 22, appendix runbooks |
| A security reviewer | Chapters 11, 12, 16 | 26, appendix B |
| A technical lead or manager | Chapters 1, 2, 25, 26 | 21, 22 |
| Preparing for an interview | Chapters 3, 5, 6, 22 | 23 (mind map) and PDF 2 |

## How each chapter is organised

Every chapter opens with a plain-language explanation, then the exact mechanism with real class and package names, then diagrams, tables of states, codes or configuration, and the tests that prove the behaviour. Many chapters end with **Reference material**: the repository's own design documents are included in full so that this PDF is self-contained. Their text is reproduced as written in the repository, with headings demoted and Mermaid diagrams replaced by the native figures of this PDF.

> [!IMPORTANT] Which document wins
> Where an included design document and `docs/design/DEVIATIONS.md` disagree, **the deviations record describes the implemented behaviour**. Examples: a policy rejection at submission returns HTTP 202 with `status=REJECTED` (not a 4xx); a replayed callback returns 200 `replayed` (not 409); the consumer checks policy, provider and circuit state before the claim. The chapters of this PDF always describe the implemented behaviour, and Appendix B reproduces the deviations record.

## Evidence legend: what was executed

This document never claims that something ran unless a source document records it.

| Area | Status | Evidence |
|---|---|---|
| Backend on the `local` profile (single JVM, H2 in PostgreSQL mode, in-memory bus, in-memory Redis and Mongo substitutes) | Executed | Maven unit and integration tests in `backend/src/test`; see `docs/validation/VALIDATION_REPORT.md` for the recorded results |
| Angular console unit tests and production build | Executed per validation report | `docs/validation/VALIDATION_REPORT.md` |
| Real PostgreSQL 16, RabbitMQ, Redis, MongoDB servers | **NOT executed** | Testcontainers tests (`-Pcontainers`) compile but need Docker |
| Docker image build, Docker Compose | **NOT executed** | No Docker on the authoring machine; files parsed by `scripts/validate-infra.sh` |
| Kubernetes manifests, Helm chart | **NOT executed** | Parsed only; no cluster, `kubectl` or `helm` |
| Cloud Foundry manifest and scripts | **NOT executed** | Parsed by `cloud-foundry/validate-manifest.mjs` only |
| GitHub Actions, Jenkins, Bamboo pipelines | **NOT executed** | YAML parsed; Jenkinsfile Groovy not linted |
| k6 load scripts | **NOT executed** | k6 not installed; no throughput figure from k6 exists |
| Prometheus rules, Grafana dashboards, OTel collector | **NOT executed** | Syntax and structure parsed; PromQL not checked by `promtool` |

### Validation evidence that exists

| Evidence | What it records |
|---|---|
| `docs/validation/e2e-run-output.txt` (reproduced in Appendix E) | A real HTTP end-to-end run of the packaged jar (`backend/target/messaging-platform.jar`, profile `local`, H2, in-memory bus, in-memory Mongo, simulators, Windows 11, JDK 21) driven by `node tests/end-to-end/e2e.mjs`: **254 checks passed, 0 failed, 0 skipped in 267.9 s**, across 14 sections (authentication, channels, idempotency, error contract, tenant isolation, consent, templates, fault modes, callbacks, cancel and audit, bulk, ops and metrics, API rate limit, log redaction). The file notes that an earlier run of the same script had 9 failing checks, all caused by the test itself (a seeded suppressed recipient, a wrong audit action name, counter expectations, a 503 from backpressure while a 20,000-row backlog drained, TCP connect timeouts on a 400-way parallel burst); the test was corrected, not weakened, and re-run on a freshly started backend |
| `docs/validation/screenshots/` | Console screenshots from a UI check against the running local backend; a selection appears in Chapter 4 |
| `docs/validation/VALIDATION_REPORT.md` | The consolidated report generated from real outputs at the end of the project; this PDF quotes only what is present in `docs/validation/` when it was built |

Numbers in this document come in three kinds, always labelled: **specified** (taken from configuration or contract, for example a 256 KB body limit), **computed** (derived arithmetically from a formula, for example backoff ceilings), and **illustrative** (assumed for capacity thinking, never measured). Whatever a validation run actually produced is quoted only from `docs/validation/`.

## Conventions

- Paths are relative to the repository root (`schwab-digital-messaging-platform`). Backend paths abbreviate `backend/src/main/java/com/example/messaging` as the package `com.example.messaging`.
- Figure captions end with an **Implementation:** reference naming the file or package the figure documents.
- Status names (`DELIVERY_UNKNOWN`), error codes (`IDEMPOTENCY_KEY_REUSED`) and configuration keys (`messaging.dispatch.max-attempts`) are verbatim from the source.
- The traceability appendix (Appendix A) maps requirements to code and tests; the glossary (Appendix C) defines the vocabulary.

## What is in the box

| Part | Chapters | Question it answers |
|---|---|---|
| A. Problem and shape | 1 to 4 | What are we building and how is it structured? |
| B. Core workflows | 5 to 14 | How does a message move, fail, retry and reconcile? |
| C. Data and trust | 15, 16 | Where does state live and who may touch it? |
| D. Run it | 17 to 20 | How is it deployed, built, observed and measured? |
| E. Engineering practice | 21 to 25 | How was it built, how does it fail safely, how is it led? |
| F. Honesty | 26 | What is simulated, not executed or missing? |
