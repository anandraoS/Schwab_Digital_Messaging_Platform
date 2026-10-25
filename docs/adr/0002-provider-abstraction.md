# ADR-0002: Provider abstraction (adapter + strategy)

Status: Accepted (2026-10-18)

## Context
Three channels (EMAIL, SMS, PUSH) are served by external vendors with different payloads, auth, error vocabularies, rate limits and callback formats. Vendors change over time, and a second SMS vendor ("Acme") must be addable without touching orchestration (`DispatchService`, `RetryPolicy`, state machine). No real provider is ever called in this repo; simulators implement the same ports.

## Decision
1. **Adapter**: one port per channel in package `provider`: `EmailProvider`, `SmsProvider`, `PushNotificationProvider`. Each adapter translates a channel-neutral request into the vendor call and maps vendor results to the closed enum `ProviderOutcome{ACCEPTED, TRANSIENT_FAILURE, PERMANENT_FAILURE, RATE_LIMITED, TIMEOUT, AMBIGUOUS}` wrapped in `ProviderResult`. Providers also expose `Optional<ProviderStatus> lookup(String idempotencyRef)` for reconciliation (ADR-0010).
2. **Strategy**: `ProviderRegistry` selects the provider for a channel from `provider_config` (`enabled`, `priority_order`) and the circuit state. Selection is data-driven, so failover or A/B is a config change.
3. **Single choke point**: all calls go through `ResilientProviderInvoker` (Resilience4j circuit breaker + time limiter on a bounded executor). Adapters must not implement their own retry; retry belongs to `RetryPolicy` (ADR-0006).
4. **Callback side**: each provider has a payload mapper registered with `EventNormalizer` (see `docs/architecture/callbacks-normalization.md`), so the rest of the system sees only `DeliveryEvent`.
5. **Worked extension**: `AcmeSmsProvider` (a 4th simulator) is added as a Spring bean implementing `SmsProvider`, a `provider_config` row (`provider_id=acme-sms`), a `messaging.providers.acme-sms.*` config block and one mapper. Zero changes to `DispatchService`, `RoutingService`, `MessageStateMachine`. The step-by-step is spec `specs/0002-new-provider-adapter.md`.

## Alternatives considered
| Option | Why not |
|---|---|
| One fat `Provider` interface with `send(channel, ...)` | Leaky: SMS and email have different options; violates interface segregation |
| Provider-specific code in orchestration (`switch(providerId)`) | Every new vendor edits the hot path; regression risk |
| Spring Cloud Stream binder-style generic plugin | Over-engineered for three channels |
| Call vendors through an API gateway/ESB | Hides outcome semantics we need (timeout vs rejected) |

## Consequences
- Positive: open/closed for providers; consistent failure classification; test doubles are trivial (`FaultInjector` drives outcomes).
- Negative: lowest-common-denominator request model; vendor-specific features go through `channelOptions` maps.
- Risk: misclassifying a vendor error (e.g. treating a vendor 500-after-accept as TRANSIENT) can cause duplicate sends; adapters must return AMBIGUOUS when the vendor may have accepted.

## Validation
- Adapter conformance tests per provider (see source `provider` test package); `AcmeSmsProvider` tests must prove it is reachable only through `ProviderRegistry`.
- ArchUnit-style rule recommended: `service` and `messaging` must not depend on concrete provider classes (see `ai-engineering/checklists/code-review.md`).
- Metrics: `messaging.provider.calls{provider,channel,outcome}`, `messaging.provider.latency{provider}`, `messaging.provider.circuit.state{provider}`.
