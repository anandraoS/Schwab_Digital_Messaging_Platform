# Skill: Add a Provider Adapter

**When**: onboarding a new vendor or simulator. **Load**: `context/architecture.md`, ADR-0002, `specs/0002-new-provider-adapter.md`, `docs/architecture/callbacks-normalization.md`.

## Steps
1. Choose the channel port (`EmailProvider`, `SmsProvider`, `PushNotificationProvider`).
2. Implement adapter bean: translate request, call vendor (or simulator), map every vendor error to `ProviderOutcome` (unknown/after-send errors => AMBIGUOUS; pre-send connection failure => TRANSIENT_FAILURE; 4xx validation => PERMANENT_FAILURE; 429 => RATE_LIMITED; read timeout => TIMEOUT). No retry inside the adapter.
3. Implement `lookup(idempotencyRef)` (return empty if vendor lacks it, and document the consequence for ambiguous outcomes).
4. Pass `idempotencyRef` to vendor if supported.
5. Callback mapper: payload to `DeliveryEvent`; register in `EventNormalizer`; add status table rows to `docs/architecture/callbacks-normalization.md`.
6. Config: `messaging.providers.<id>.{enabled,rate-per-sec,timeout-ms}`, `provider_config` row via migration (expand-only) or admin API, `messaging.callback.secrets.<keyId>` (dummy locally).
7. Tests: outcome mapping table, lookup, mapper (duplicate/out-of-order/unknown), registry selection and failover, circuit behavior via invoker.
8. Confirm no changes in `DispatchService`, `RetryPolicy`, `MessageStateMachine` (if needed, ADR).
9. Observability: provider tag appears in `messaging.provider.*` metrics.

## Guardrails
No credentials in repo; no real network calls in tests; no vendor-specific types leaking beyond `provider`.
