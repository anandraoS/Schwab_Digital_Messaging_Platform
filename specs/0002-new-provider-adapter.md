# Spec 0002: Add a New Provider Adapter (AcmeSmsProvider)

- Status: **Implemented as a simulator** (reference implementation; no real vendor exists or is contacted). Rewritten on 2026-10-18 to match the code; the first draft planned an `AcmeClient`, an `AcmeCallbackMapper` and the key id `acme-key-1`, none of which exist.
- Owner (role): Backend
- Date: 2026-10-18
- Related: ADR-0002, ADR-0004, ADR-0010; `docs/architecture/callbacks-normalization.md`; `docs/architecture/callback-flow.md`; `docs/design/DEVIATIONS.md` (stage 2, item 7; bullet on `ProviderAdminService`); `ai-engineering/skills/add-provider-adapter.md`

## 1. Context and problem
A second SMS vendor ("Acme", simulated) is needed for failover and rate-limit capacity. Goal: onboard it **without changing orchestration** (`DispatchConsumer`, `RetryPolicy`, `MessageStateMachine`, `ProviderRegistry` selection logic). This spec proves the extension point works; no real vendor is contacted.

## 2. Goals and non-goals
Goals: new adapter, payload mapper, config, tests; selectable via `provider_config`; failover works. Non-goals: real vendor integration, changing the SMS port, changing the lifecycle.

## 3. Requirements and what was built
| Req | Requirement | Implementation (verified in source) |
|---|---|---|
| R1 | Adapter implementing `SmsProvider`, id `acme-sms`, Spring bean | `provider/simulator/AcmeSmsProvider` (`@Component`, extends `SimulatedProviderBase implements SmsProvider`, `ID = "acme-sms"`, message id prefix `acme-`). It lives in `provider.simulator`, not `provider` |
| R2 | Every result maps to `ProviderOutcome`; ambiguous after-send errors map to AMBIGUOUS; no retry inside the adapter | Shared by all simulators in `SimulatedProviderBase` + `FaultInjector`; `ProviderOutcome` lives in package `provider` |
| R3 | Sends `idempotencyRef` (`messageId:attemptGroup`) as the client reference | `SimulatedProviderBase.send` de-duplicates on the idempotency reference like a compliant vendor |
| R4 | `lookup(idempotencyRef)` | `SimulatedProviderBase.lookup`, used by `ReconciliationService` through `ResilientProviderInvoker.lookup` |
| R5 | Callback mapper for Acme's own payload | `callback/AcmeSmsPayloadMapper`, a `ProviderPayloadMapper` bean found by `EventNormalizer`. Payload `{"ref","state","ts","id","code"}`: `ref` is Acme's own message id, `state` is `queued`/`sent`/`ok`/`fail`, `ts` is epoch seconds, `id` is the event id (the first draft said `eid`) |
| R5b | Signed callbacks | endpoint `/api/v1/callbacks/acme-sms`; key id **`acme-sms-key`** (seed `provider_config.hmac_key_ref`); dummy secret `messaging.callback.secrets.acme-sms-key` in `application-local.yml` and `application-test.yml`, `${CALLBACK_SECRET_ACME_SMS}` in `application-prod-like.yml` |
| R6 | Configuration and `provider_config` row | seed row `prv-004` (`acme-sms`, `SMS`, **enabled = FALSE**, `priority_order` 2, 50 req/s, timeout 3000 ms, key ref `acme-sms-key`) in `db/seed/V900__synthetic_seed.sql` |
| R7 | Fault injection through the common base | `FaultInjector` / `ProviderFaultController` (`PUT /api/v1/providers/{providerId}/fault-mode`, local/test only) |
| R8 | No orchestration change | enforced by `NewProviderWithoutOrchestrationChangeTest#orchestrationPackagesNeverNameAConcreteProvider` (packages `service`, `messaging`, `scheduler`, `callback`, `domain` never name a concrete provider) |

## 4. Acceptance criteria and evidence
| AC | Criterion | Test (class#method) |
|---|---|---|
| AC-1 | With `simulated-sms` disabled and `acme-sms` enabled through the config API, an SMS dispatches through Acme; attempt row has `provider_id=acme-sms` | `NewProviderWithoutOrchestrationChangeTest#acmeSmsIsDisabledByDefaultAndSwitchedOnPurelyThroughConfiguration` |
| AC-2 | `priority_order` and breaker state drive failover between the two SMS vendors | `NewProviderWithoutOrchestrationChangeTest#priorityOrderAndCircuitStateDriveFailoverBetweenTheTwoSmsVendors`, `ProviderRegistryTest#anOpenCircuitFailsOverToTheNextProviderButIfAllAreOpenTheFirstIsStillReturned` |
| AC-3 | Timeout leads to `DELIVERY_UNKNOWN` without a blind resend; `lookup` drives reconciliation | provider-independent: `FailureRecoveryIT#scenario04_providerTimeout_movesToDeliveryUnknown_reconcilesWithoutDuplicateSend`, `ReconciliationIT` (not Acme-specific) |
| AC-4 | Acme callbacks normalise (`ok` to DELIVERED, `fail` to FAILED, `queued` to PROVIDER_ACCEPTED); duplicates and bad signatures handled | `EventNormalizerTest#acmeUsesItsOwnShapeWithEpochSecondsAndNoIdempotencyEcho`, `CallbackApiIT`, and the signed Acme callback step in `NewProviderWithoutOrchestrationChangeTest` |
| AC-5 | Orchestration classes untouched | the source-level rule in R8 (a diffstat review was the original plan; the test replaces it) |
| AC-6 | `GET /api/v1/providers` lists `acme-sms` with circuit state and rate limit | `ProviderApiIT` |
| AC-7 | A brand new vendor defined only in test code is routed, called and normalised | `NewProviderWithoutOrchestrationChangeTest#aBrandNewVendorIsRoutedCalledAndNormalisedWithoutChangingAnyMainClass` |

Executed results are recorded only in `docs/validation/VALIDATION_REPORT.md`; this spec does not claim a pass count.

## 5. Design (as built)
1. Adapter: `AcmeSmsProvider` is a thin subclass of the simulator base (acceptable for a simulator; a real vendor adapter should be an independent class that maps accepted to ACCEPTED, validation error to PERMANENT_FAILURE, throttle to RATE_LIMITED, connect refused before send to TRANSIENT_FAILURE, read timeout to TIMEOUT, unknown-after-send to AMBIGUOUS). There is no separate `AcmeClient`.
2. Mapper: `AcmeSmsPayloadMapper` implements `ProviderPayloadMapper` (parse and render), registered simply by being a bean; `EventNormalizer` rejects two mappers for one provider id (checked in `EventNormalizerTest`).
3. Data: the `acme-sms` row is in the synthetic seed (disabled); enabling is `PUT /api/v1/providers/acme-sms/config` by PLATFORM_ADMIN (audited), no restart.
4. Secrets: dummy local/test secret only; production reads `CALLBACK_SECRET_ACME_SMS` from the environment/secret store.
5. Metrics need no change (the `provider` tag distinguishes it). `ProviderAdminService` lives in package `provider` because it knows the simulator classes.
Alternatives: dynamic plugin loading (rejected: over-engineered).

## 6. Failure modes
Classification-driven like every provider (ADR-0006, ADR-0010). Specific risk: a real vendor that ignores the idempotency reference would produce duplicates after a "not received" lookup; treat unknown lookup answers as unknown.

## 7. Security and privacy
Own HMAC key id bound to the provider (a key of another provider is rejected with the uniform 401); secrets only via config; free-text vendor reasons never reach audit or logs; raw payloads archived redacted; mapper is pure.

## 8. Performance and capacity
Per-provider token bucket from `provider_config.rate_limit_per_sec` (burst = rate); timeout from `provider_config.timeout_ms`.

## 9. Observability
`messaging.provider.calls{provider="acme-sms",...}`, `messaging.provider.latency`, `messaging.provider.circuit.state`; dashboards use the provider label.

## 10. Rollout and rollback
Disabled by default; enable through the provider config API after staging validation. Rollback: disable the provider; no schema contraction needed.

## 11. Open questions
Real vendor semantics for lookup and idempotency are unknown for any real vendor and must be verified against vendor documentation, not assumed.
