# 8. Provider Adapter Architecture

## 8.1 The idea

Email, SMS and push vendors all do roughly the same thing and fail in roughly the same ways, but their APIs, payloads, status words and quirks differ. The platform draws a boundary: **orchestration** (retries, policy, state, audit) knows only a small interface and a normalised outcome; **adapters** translate to and from each vendor. Replacing a vendor, or adding one, must not require touching the orchestration code. That is not a hope: `NewProviderWithoutOrchestrationChangeTest` proves it with a vendor that exists only inside the test.

## 8.2 The port

```java
public interface ChannelProvider {
    String providerId();                 // matches provider_config.provider_id and the callback path segment
    Channel channel();
    ProviderResult send(ProviderRequest request);   // never throws for provider-side problems
    Optional<ProviderStatus> lookup(String idempotencyRef);  // reconciliation; empty = callbacks only
}
```

Implementations return a `ProviderResult` carrying one of six normalised outcomes:

```java
public enum ProviderOutcome {
    ACCEPTED, TRANSIENT_FAILURE, PERMANENT_FAILURE, RATE_LIMITED, TIMEOUT, AMBIGUOUS;
    public boolean isRetryable() { return this == TRANSIENT_FAILURE || this == RATE_LIMITED; }
    public boolean isUncertain() { return this == TIMEOUT || this == AMBIGUOUS; }
}
```

`isRetryable` and `isUncertain` are the two questions the rest of the system asks. Uncertain outcomes are **never** blindly retried for EMAIL and SMS.

Channel-specific sub-interfaces (`EmailProvider`, `SmsProvider`, `PushNotificationProvider`) let a registry look up providers by channel.

{{diagram:providers}}

| Class | Role |
|---|---|
| `ProviderRegistry` | Resolves the enabled providers for a channel in `priority_order` (strategy-based routing); backed by the `provider_config` table |
| `ResilientProviderInvoker` | Reserves a circuit-breaker permit, runs the call on the bounded provider executor with a time limiter, records the outcome and latency |
| `ProviderRequest` | Recipient address, rendered content, `idempotencyRef` (`messageId:attemptGroup`), channel options, timeout |
| `ProviderAdminService` | Enable or disable a provider, set rate limit and timeout, set a simulator fault mode (local and test only) |
| `FaultInjector` | Deterministic and probabilistic fault modes for simulators |

## 8.3 Resilience around every call

{{diagram:circuit}}

| Mechanism | Default | Where |
|---|---|---|
| Time limiter | Per-provider `timeout-ms` (`provider_config.timeout_ms`) | `ResilientProviderInvoker`, bounded executor (16 threads, queue 64) |
| Circuit breaker | Opens at 50 percent failures over a 20-call window (minimum 10 calls), 5 s open, 3 half-open probes | Resilience4j, configured from `messaging.pipeline.resilience.*` |
| Token bucket rate limit | `provider_config.rate_limit_per_sec`, burst = rate | `CoordinationService` (Redis Lua script, in-memory fallback) |
| Refusal handling | Open breaker or empty bucket requeues the delivery 0.1 to 5 s, consuming no attempt | `DispatchConsumer` |

The breaker leaves `OPEN` by itself (`automaticTransitionFromOpenToHalfOpenEnabled`); otherwise a state pre-check would keep it open forever. `invoke(provider, request)` without a reservation returns `TRANSIENT` with code `CIRCUIT_OPEN` without touching the provider.

## 8.4 The simulators

No real provider is ever called. Four simulators stand in for vendors, all extending `SimulatedProviderBase`:

| Provider id | Class | Channel | Callback key id |
|---|---|---|---|
| `simulated-email` | `SimulatedEmailProvider` | EMAIL | `simulated-email-key` |
| `simulated-sms` | `SimulatedSmsProvider` | SMS | `simulated-sms-key` |
| `simulated-push` | `SimulatedPushProvider` | PUSH | `simulated-push-key` |
| `acme-sms` | `AcmeSmsProvider` | SMS | `acme-sms-key` |

`SimulatedProviderBase` behaves like a **compliant vendor**: it deduplicates on `idempotencyRef` before anything else, remembers what it accepted so that `lookup` works for reconciliation, and can post signed callbacks to the platform. Its fault modes (`FaultInjector.Mode`): `NONE`, `TRANSIENT`, `PERMANENT`, `RATE_LIMIT`, `TIMEOUT`, `AMBIGUOUS`, `ALTERNATING`, each with a probability. The `AMBIGUOUS` mode genuinely accepts the message and then "loses" the response, which is exactly the situation reconciliation exists for.

```java
case AMBIGUOUS:
    accept(ref); // the vendor did accept it, but the response is "lost"
    return ProviderResult.failure(ProviderOutcome.AMBIGUOUS, "SIM_LOST_RESPONSE",
            "accepted but response lost", elapsedMs(start));
```

A recipient address of the form `...+hardbounce@...` simulates a hard bounce (`HARD_BOUNCE`, which adds a suppression). `PERMANENT` mode answers `SIM_REJECTED` and does not suppress. Fault injection endpoints exist only in profiles `local` and `test`.

## 8.5 Worked example: adding a provider

`AcmeSmsProvider` is the worked "new provider" example (spec `specs/0002-new-provider-adapter.md`, skill `ai-engineering/skills/add-provider-adapter.md`). Adding a vendor is five small steps:

1. Implement `SmsProvider` (or the right sub-interface) as a Spring bean returning normalised outcomes.
2. Add a `ProviderPayloadMapper` bean that turns the vendor's webhook payload into a normalised event (Chapter 12), including its status vocabulary.
3. Insert a `provider_config` row (enabled, `priority_order`, rate limit, timeout, `hmac_key_ref`).
4. Add `messaging.callback.secrets.<keyId>` (a dummy value locally; a secret store in production).
5. Add mapper unit tests covering duplicate, out-of-order and unknown-status events (`EventNormalizerTest`).

Nothing in `service`, `messaging`, `scheduler`, `callback` or `domain` changes, and `NewProviderWithoutOrchestrationChangeTest` guards that those packages never name a concrete provider.

> [!NOTE] Spec drift recorded in the deviations
> Spec 0002 still mentions the Acme event id as `eid` and the key id as `acme-key-1`. As implemented, Acme's payload uses `id` for the event id and the key id is `acme-sms-key` (seed `hmac_key_ref`). The implemented values are the ones in `docs/architecture/callbacks-normalization.md`.

## 8.6 Tests that prove it

| Test | Proves |
|---|---|
| `ProviderRegistryTest` | Routing order, disabled providers skipped |
| `ResilientProviderInvokerTest` | Timeouts, breaker transitions, reservation and release |
| `SimulatedProviderTest`, `FaultInjectorTest` | Provider-side idempotency, fault modes |
| `ProviderOutcomeIT` | Each outcome produces the right transition |
| `NewProviderWithoutOrchestrationChangeTest` | A new vendor needs no orchestration change |
| `ProviderApiIT` | Provider health and config endpoints |

## Reference material

{{embed:docs/adr/0002-provider-abstraction.md|title=ADR-0002: Provider abstraction}}

{{embed:specs/0002-new-provider-adapter.md|title=Spec 0002: New provider adapter|note=Written before implementation; where it differs (event id field, key id), the implemented values are in Chapter 12.}}
