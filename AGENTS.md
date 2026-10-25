# AGENTS.md

Instructions for AI coding agents working in this repository. Humans: see `README.md` and `docs/`. Longer guidance: `ai-engineering/`. Binding contract: `docs/design/CONTRACT.md`.

## What this is
Independent reference implementation of an omnichannel (EMAIL/SMS/PUSH) messaging and notification orchestration platform. Java 21 / Spring Boot 3.5.16 / Maven backend (`backend/`), Angular 20 console (`frontend/messaging-console/`). **All data is synthetic; no real provider is ever called.** Do not claim it reproduces any company's internal system.

## Provenance
This repository was authored with Claude Code. No Copilot or other agent session produced these files unless a commit says so. Never describe unexecuted work as executed.

## Commands (verify before relying; record results honestly)
- Java: use the JDK at `C:\Users\sahukari.rao\.jdks\graalvm-jdk-21.0.7` by full path; do not export `JAVA_HOME` globally.
- Backend build/test: `mvn -B verify` from `backend/` (Maven 3.9.12 wrapper-dist path in CONTRACT section 0). Containers profile `-Pcontainers` needs Docker (unavailable here).
- Local run: profile `local`, API on port **18080** (8080 is occupied), Angular dev server 14200 with proxy to 18080.
- Frontend: `npm ci`, `ng test --watch=false`, `ng build` in `frontend/messaging-console/`.
- Not available here: Docker, kubectl, Helm, cf CLI, k6, cloud. Assets for these are statically validated only and must be labelled "not executed".
- Git: never `git push`; never `git add -A` (git root is the home directory); stage explicit paths.

## Non-negotiable rules (invariants; details in `ai-engineering/context-engineering.md`)
1. Message status changes only via `MessageStateMachine` with compare-and-set; no direct status setters.
2. Accepted means durable: persist message + outbox + idempotency + audit in one transaction; publish after commit.
3. Idempotency at API, consumer, callback and provider call.
4. Never hold a DB transaction across a provider call; every remote call has a timeout; pools/queues are bounded.
5. Provider acceptance is not delivery; timeouts/ambiguous outcomes become `DELIVERY_UNKNOWN`, never blind retries for EMAIL/SMS.
6. Tenant comes only from the verified JWT; every tenant-owned query is tenant-scoped; foreign resources return 404.
7. No secrets, rendered bodies, full addresses in code, logs, API responses, the bus or diagnostics. Use `Redaction`. Dummy secret value allowed only for local/test: `local-dev-secret-not-for-production`.
8. Callback HMAC verification and replay protection must never be bypassed or loosened.
9. Consent policy order (suppression > category > quiet hours) applies at accept, dispatch and replay; fallback never bypasses consent.
10. Dev-only endpoints exist only in profiles `local`/`test`.
11. Flyway migrations: expand-only, portable (H2 PostgreSQL mode + PostgreSQL): no partial indexes, JSONB, `ON CONFLICT`; update Oracle reference DDL.
12. Do not contradict an ADR silently: write a superseding ADR and a `docs/design/DEVIATIONS.md` entry (append only).

## Where things are
Packages under `backend/src/main/java/com/example/messaging/` (see `ai-engineering/context-map.md`). Metric names: CONTRACT section 8 (keep exact). Config keys: CONTRACT section 9. Tests mirror packages; failure scenarios in `FailureRecoveryIT` (names in `docs/architecture/reliability-scenarios.md`).

## Working agreements
- Read the relevant `ai-engineering/context/*.md` module before editing; load only what you need.
- Small, focused changes; tests with every behavior change, including failure paths; no weakening of assertions.
- Follow `ai-engineering/standards/*` and run the checklists in `ai-engineering/checklists/`.
- Sensitive changes (authn/z, tenancy, callbacks, templates, state machine, retry/idempotency, consent) need independent review: see `ai-engineering/multi-agent-workflow.md`.
- Documentation honesty: every validation claim cites the exact command and whether it ran; unexecuted items listed as NOT executed.
- Use synthetic data only (`Recipient 001`, `recipient001@example.test`, `+1555010xxxx`).
- Avoid `rm`, `cd &&` chains, inline `curl` with credentials, giant heredocs: put logic in script files.

## Boundaries
Do not edit another contributor's area during parallel work (see file ownership in the task). Do not add dependencies outside the Spring Boot BOM without justification in the PR.
