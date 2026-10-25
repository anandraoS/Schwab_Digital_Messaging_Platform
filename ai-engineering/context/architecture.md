# Context: Architecture
Load when: designing/changing components, adding a provider, touching bus/outbox, deployment questions. Detail: `docs/architecture/architecture-overview.md`.

- Modular monolith, Java 21, Spring Boot 3.5.16, packages under `com.example.messaging` (see context-map).
- Flow: `POST /messages` -> one tx (message VALIDATED + outbox + audit + idempotency) -> `OutboxPublisher` (200 ms, CAS claim) -> `MessageBus` -> `DispatchConsumer` -> `DispatchService` -> `ResilientProviderInvoker` -> provider adapter. Callbacks -> `callback` package -> `DeliveryEventProcessor`.
- Ports with two impls: `MessageBus` (Rabbit | InMemory), coordination (Redis | memory via `ResilientCoordination`), diagnostics (Mongo).
- Provider ports: `EmailProvider`, `SmsProvider`, `PushNotificationProvider` (+ `lookup(idempotencyRef)`); `ProviderRegistry` selects from `provider_config`; outcomes are the closed enum `ProviderOutcome`.
- Envelope `DispatchEnvelope` has ids only (no content/PII).
- DB is source of truth; Redis fast path only; Mongo best-effort diagnostics.
- Bounded everything: consumer concurrency (8), prefetch (16), queue capacity 5000 in memory, body 256 KB, bulk concurrency 4.
- Profiles: `local`, `test`, `prod-like` (+`rabbit`). Local port 18080.
- ADRs: 0001 bus, 0002 providers, 0009 outbox, 0008 deployment.
- Do not: call providers inside a DB transaction; put business logic in controllers; import concrete provider classes in `service`/`messaging`.
