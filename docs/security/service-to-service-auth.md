# Service-to-Service Authentication

## Inbound: calling services (SENDER clients)
| Aspect | Design |
|---|---|
| Mechanism | OAuth 2.0 **client credentials** grant against the enterprise IdP; the platform is an OAuth2 resource server validating RS256 JWTs (Spring Security 6) |
| Claims | `sub` (client id), `roles` (array, e.g. `ROLE_SENDER`), `tenant` (one tenant id), `scope` (e.g. `messages:send`, `messages:bulk`), `iss`, `aud`, `exp` |
| Token lifetime | Short (illustrative: 5-15 min); clients cache and refresh before expiry |
| Validation | issuer-uri discovery (prod), signature, `exp`/`nbf`, audience (configure `aud` check; see source `SecurityConfig`/`JwtConfig`), clock skew 60 s |
| Authorization | tenant + roles + scopes (ADR-0011) |
| Local | `POST /dev/token` mints RS256 tokens from an in-memory key for personas; disabled outside `local`/`test` (T10) |
| Rate limiting | per `sub` token bucket |

Example client call (placeholders, no real secrets):
```
POST {idp}/oauth2/token  grant_type=client_credentials  scope=messages:send   (client authenticates with private_key_jwt or secret from its own secret store)
POST /api/v1/messages   Authorization: Bearer <access_token>   Idempotency-Key: <uuid>
```
Prefer `private_key_jwt` or mTLS-bound tokens (RFC 8705) over shared client secrets.

## Optional: mTLS
| Option | When | Notes |
|---|---|---|
| TLS at ingress only | default reference | simplest; clients identified by JWT |
| mTLS at ingress/mesh (client certs) | regulated environments, internal service mesh | cert identity (SPIFFE/URI SAN) mapped to client; combine with JWT (defense in depth); certificate rotation automation required; certificate-bound access tokens (`cnf` claim) prevent token theft replay |
Not implemented/executed in this repo; an Ingress annotation/mesh policy example may exist under `infrastructure/` (static only).

## Outbound: platform to providers
| Aspect | Design |
|---|---|
| Credentials | Per-provider API key/OAuth client from the secret store; never in `provider_config` (only `hmac_key_ref`) |
| Transport | TLS 1.2+ with default trust store; certificate pinning not modeled |
| Timeouts | `messaging.providers.<id>.timeout-ms`; connect and read timeouts distinct (see source) |
| Network | Egress allow-list / proxy |
| Idempotency | `idempotencyRef` sent in header/body where vendor supports |
Simulators are in-process and need no credentials.

## Inbound: provider callbacks
Authenticated by HMAC (`X-Signature: t=<epoch>,v1=<hex>` over `t + "." + rawBody`), `X-Provider-Key-Id` selects the secret, tolerance 5 min, replay cache on `X-Event-Id`. Optional additions in prod: provider source IP allow-list at edge, mTLS if the vendor supports. Callback endpoints are `permitAll` in the filter chain by design and protected by the verifier: tests must prove that an unsigned request never reaches `DeliveryEventProcessor`.

## Internal components
Within the single JVM: no network auth. Between the platform and data stores: per-store credentials, TLS (`prod-like`), least-privilege DB user (DML only for the app; DDL by a separate migration user used by Flyway in CI/CD), Rabbit user limited to the messaging vhost, Redis ACL user limited to key prefix `msg:*`.

## Threats addressed
T1, T3, T4, T10 in `threat-model.md`.
