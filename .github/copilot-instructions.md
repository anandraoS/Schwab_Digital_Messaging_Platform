# Copilot repository instructions

This file configures GitHub Copilot for this repository. It was authored by hand (with Claude Code) as an example of repository-level instructions; no Copilot session has been run against it. Shared rules live in `AGENTS.md` and `ai-engineering/`; this file keeps only the highest-value rules.

## Project
Omnichannel messaging and notification orchestration platform (EMAIL/SMS/PUSH): Java 21, Spring Boot 3.5.16, Maven, PostgreSQL (H2 in PostgreSQL mode for local/test), RabbitMQ, Redis, MongoDB (diagnostics only), Angular 20. Synthetic data only. Binding contract: `docs/design/CONTRACT.md`. Decisions: `docs/adr/`.

## Always
- Follow the message lifecycle in `ai-engineering/context/lifecycle.md`. Change status only through `MessageStateMachine` with compare-and-set updates.
- Make every write path idempotent (API `Idempotency-Key`, consumer dedupe by `dispatchId`, callback dedupe by `(provider_id, provider_event_id)`).
- Never call a provider inside a database transaction. Put timeouts on every remote call. Use bounded thread pools and queues.
- Treat provider timeouts as `DELIVERY_UNKNOWN` (no blind retry for EMAIL/SMS). Provider acceptance is not delivery.
- Take the tenant from the verified JWT only; scope every query by tenant; return 404 for other tenants' resources.
- Never log or return rendered message bodies, full email/phone, secrets, tokens or signatures. Use `Redaction`.
- Do not weaken or bypass HMAC callback verification, even temporarily.
- Use exact metric names from CONTRACT section 8. Use error codes from the CONTRACT list.
- Flyway migrations: expand-only, portable SQL (no partial indexes, JSONB, `ON CONFLICT`), update the Oracle reference DDL.
- Write tests with each change, including failure paths; inject `Clock`; no `Thread.sleep`.
- Only synthetic data; no secrets (dummy `local-dev-secret-not-for-production` for local/test only).

## Never
- `git push`, `git add -A`, global `JAVA_HOME` exports, real network calls to providers.
- Claims that something was run when it was not. Label Docker/Kubernetes/Helm/CF/k6/Testcontainers work "not executed".
- Silent ADR overrides: propose a superseding ADR instead.

## Local facts
API runs on port 18080 locally (8080 is taken); Angular dev server 14200.

## More
Path-specific rules: `.github/instructions/*.instructions.md`. Reusable prompts: `.github/prompts/*.prompt.md`. Checklists: `ai-engineering/checklists/`.
