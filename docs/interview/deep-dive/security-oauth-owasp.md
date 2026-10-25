# Security Deep Dive: OAuth2, OIDC, JWT, OWASP API Top 10, Webhooks, Data Protection

Scope note: reference chapter for a senior Java full-stack messaging role interview. Claims about the platform cite CONTRACT sections (`docs/design/CONTRACT.md`) and ADRs; code snippets are illustrative. Nothing here claims a penetration test, scan, or production deployment was executed. All data and secrets are synthetic or dummy placeholders.

## The Analogy Corner

- **OAuth2 = a hotel key card.** You do not give the cleaner your house key (password). The front desk (authorization server) issues a card that opens only certain doors (scopes) for a limited time (expiry).
- **JWT = a signed concert wristband.** Staff at each door can check the hologram (signature) without phoning the box office. But they must also check the date, the event name (audience), and that the hologram is genuine, not a drawing.
- **404 instead of 403 = a hotel that says "no such room" rather than "that room is occupied by someone else".** Telling you the room exists leaks information.
- **HMAC signature = a secret handshake plus a timestamp.** Both sides know the handshake. A recorded handshake from last week is rejected because the clock on it is stale.

## OAuth2 Flows

| Flow | Use | Notes |
|---|---|---|
| Authorization Code + PKCE | Browser SPAs and mobile apps with a user | Current best practice for the Angular console in a real deployment; PKCE protects the code from interception. Implicit flow is deprecated |
| Client Credentials | Service-to-service, such as an upstream brokerage app calling the messaging API (`ROLE_SENDER` service client per CONTRACT section 1) | No user; token represents the client; keep scopes narrow, for example `messages:bulk` |
| Refresh Token | Renew access without re-login | Rotate on use; bind to the client; short access tokens, longer refresh |
| Device Code | Input-constrained devices | Rarely relevant here |
| Resource Owner Password | Legacy | Do not use |

Local reality: the platform's `local`/`test` profiles expose `POST /dev/token`, which mints RS256 JWTs from an in-memory generated key for demos. It is disabled outside `local`/`test` via `messaging.security.dev-token-enabled` and must never be reachable in a real environment (CONTRACT sections 1, 9). The production profile validates tokens from an external issuer through `issuer-uri`.

## OIDC in One Page

OpenID Connect adds identity on top of OAuth2: an **ID token** (JWT about the user's authentication event), the `openid` scope, a discovery document at `/.well-known/openid-configuration`, and a JWKS endpoint for signing keys. Distinguish:

- **ID token**: for the client app, proves who logged in. Not for calling APIs.
- **Access token**: for the resource server (this API). Validate audience and scope.
- **UserInfo endpoint**: extra claims on demand.

Interview trap: sending an ID token as a bearer token to an API. A resource server should accept access tokens intended for it.

## JWT Validation Pitfalls

A resource server must validate every one of these. Spring's `JwtDecoder` handles most when configured, but you must know why.

| Pitfall | Attack | Defence |
|---|---|---|
| `alg: none` accepted | Forged unsigned token | Library rejects none; pin expected algorithm (RS256 per CONTRACT section 1) |
| Algorithm confusion (RS256 vs HS256) | Attacker signs with the public key as an HMAC secret | Pin algorithm per key; never let the header choose |
| Missing `iss` check | Token from another issuer | Validate issuer (`issuer-uri` does this) |
| Missing `aud` check | Token meant for another API reused | Add an audience validator |
| Expiry ignored or huge clock skew | Replay of old tokens | Enforce `exp`/`nbf`, small skew |
| Trusting `kid` blindly, or `jku`/`x5u` headers | Key injection or SSRF | Only fetch keys from the configured JWKS URI |
| Putting PII or secrets in the JWT | Tokens are only encoded, not encrypted | Minimal claims |
| Long-lived access tokens, no revocation | Stolen token stays valid | Short TTL, refresh rotation, deny list for emergencies |
| Treating `roles` from untrusted source | Privilege escalation | Only trust claims from a verified signature |
| Tokens in URLs or localStorage | Leakage and XSS theft | Authorization header; in-memory storage preferred. Note: the console's dev login stores the JWT in memory plus `sessionStorage` (CONTRACT section 10), acceptable for a dev persona picker but a trade-off to call out for production |

Illustrative decoder configuration with an audience check:

```java
@Bean
JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
    NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer);
    OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuer);
    OAuth2TokenValidator<Jwt> withAudience = jwt ->
        jwt.getAudience().contains("messaging-api")
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "bad audience", null));
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(withIssuer, withAudience));
    return decoder;
}
```

The `local`/`test` decoder instead uses `NimbusJwtDecoder.withPublicKey` with the in-memory dev key (CONTRACT section 1).

<!-- in-project:d03 -->
> [!NOTE] In this project
> **File:** `backend/src/main/java/com/example/messaging/security/JwtAuthConverter.java`
>
> Signature, expiry and issuer are validated by the decoder; this converter adds the application-level claim checks. Unknown roles in the `roles` array grant nothing (they are skipped, not trusted).
>
> ```java
> String tenant = jwt.getClaimAsString("tenant");
> boolean valid = tenant != null
>         && ((CurrentPrincipal.ANY_TENANT.equals(tenant) && platformAdmin) || TENANT.matcher(tenant).matches());
> if (!valid) {
>     throw new BadCredentialsException("Token has no valid tenant claim");
> }
> if (jwt.getSubject() == null || jwt.getSubject().isBlank()) {
>     throw new BadCredentialsException("Token has no subject");
> }
> ```


## Spring Security 6 Resource Server

Platform facts from CONTRACT sections 1 and 5: stateless Bearer JWT on every endpoint except `/api/v1/callbacks/**` (HMAC), health, Prometheus (network-restricted in prod), `/dev/token`, and swagger docs locally. Claims: `sub`, `roles` (array), `tenant`, `scope`. Roles: `ROLE_PLATFORM_ADMIN`, `ROLE_TENANT_ADMIN`, `ROLE_TEMPLATE_AUTHOR`, `ROLE_TEMPLATE_APPROVER`, `ROLE_OPERATOR`, `ROLE_SENDER`, `ROLE_AUDITOR`. Relevant package: `config/SecurityConfig`, `security/` (`TenantContext`, `CurrentPrincipal`, `AuthorizationService`).

Illustrative shape (the real code lives in `SecurityConfig`):

```java
@Bean
SecurityFilterChain api(HttpSecurity http) throws Exception {
    http
      .csrf(csrf -> csrf.disable())                       // stateless bearer API, no cookies
      .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .authorizeHttpRequests(a -> a
          .requestMatchers("/api/v1/callbacks/**").permitAll()   // HMAC verified separately
          .requestMatchers("/actuator/health/**").permitAll()
          .requestMatchers(HttpMethod.POST, "/api/v1/messages").hasAnyRole("SENDER", "TENANT_ADMIN", "PLATFORM_ADMIN")
          .anyRequest().authenticated())
      .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(rolesFromClaim())));
    return http.build();
}
```

Why disabling CSRF is acceptable here: CSRF exploits ambient credentials such as cookies. A bearer token sent explicitly in a header is not ambient. If you ever add cookie-based sessions, CSRF protection must come back.

Method versus resource-level authorization: URL or `@PreAuthorize` role checks answer "may this role call this endpoint?". They do not answer "may this principal touch this object?". That second question is handled by `AuthorizationService` resource-level checks.

Callback `permitAll` is deliberate and risky only if the signature filter is missing; the contract says "never trust the network" and requires signature validation in a filter or service.

## Roles and Tenant Isolation (404, not 403)

CONTRACT section 5: non-admin principals see only their `tenant` claim's data; a resource owned by another tenant returns **404**, not 403. `PLATFORM_ADMIN` has `tenant=*` and may pass `X-Tenant-Id`.

Why 404: a 403 confirms the object exists, enabling enumeration of IDs, templates, or recipients across tenants (a BOLA oracle). With UUID ids enumeration is harder but not the only risk; consistent behaviour matters.

Enforcement design points to discuss:

1. Derive the tenant only from the validated token, never from the request body. `X-Tenant-Id` is honoured only for platform admins.
2. Apply the tenant filter in the query (`WHERE tenant_id = ?`) rather than load-then-check, to avoid timing and accidental exposure.
3. Cover every access path: list endpoints, sub-resources (`/messages/{id}/history`), search by `correlationId`, delivery events, audit events, diagnostics, bulk job failures.
4. Test it: for each endpoint, call with tenant A's token against tenant B's id and assert 404. The repo has a `tests/security` area for this class of test; contents not described here.
5. Separation of duties: template approver must differ from the author (four-eyes, CONTRACT section 5).

## OWASP API Security Top 10 (2023) Mapped to the Platform

| ID | Risk | Platform mapping |
|---|---|---|
| API1 | Broken Object Level Authorization | Tenant-scoped queries, 404-not-403, `AuthorizationService` |
| API2 | Broken Authentication | JWT RS256 validation, issuer and audience checks, dev token disabled outside local/test, short TTL |
| API3 | Broken Object Property Level Authorization | DTO records limit exposed fields; `MessageDetail` never returns rendered body or full address, masked only (CONTRACT section 5); mass assignment avoided by explicit request records |
| API4 | Unrestricted Resource Consumption | Per-client rate limit 50 req/s burst 100, `RateLimitFilter`, body cap 256 KB (413), page size max 200, bulk `max-rows`, backpressure 503 + `Retry-After`, bounded thread pools |
| API5 | Broken Function Level Authorization | Role matrix per endpoint (CONTRACT section 5); admin endpoints for `PLATFORM_ADMIN`; fault-mode endpoint only in local/test |
| API6 | Unrestricted Access to Sensitive Business Flows | Idempotency keys prevent replayed submits; bulk send requires `ROLE_SENDER` plus scope `messages:bulk`; marketing consent rules enforced server-side; rate limits on submissions. Abuse of messaging (spam, SMS pumping) is the classic business-flow risk here |
| API7 | Server Side Request Forgery | Platform calls only configured provider endpoints (simulated here); no user-supplied URLs fetched; templates cannot fetch resources (no expression evaluation, allow-listed `{{var}}` engine) |
| API8 | Security Misconfiguration | Profiles with secure defaults; `prod-like` has no dev endpoints; consistent `ApiError` without stack traces; actuator exposure limited; TLS terminated per environment |
| API9 | Improper Inventory Management | Committed OpenAPI spec and a test asserting springdoc output contains every spec path (CONTRACT section 1); versioned base path `/api/v1`; `API-Version` header |
| API10 | Unsafe Consumption of APIs | Provider responses and callbacks treated as untrusted: signature verification, schema normalisation via `EventNormalizer`, timeouts and circuit breakers, dedupe, size limits |

Further injection-class controls: JPA parameter binding (no string-concatenated SQL), HTML-escaping in the email template renderer, no expression language in templates (prevents server-side template injection), and log-injection avoidance by never logging raw user input unescaped.

## HMAC Webhook Signatures and Replay Protection

CONTRACT section 1: header `X-Signature: t=<epoch>,v1=<hex>`, HMAC-SHA256 over `t + "." + rawBody`, plus `X-Provider-Key-Id` and `X-Event-Id`; 5 minute tolerance; replay cache by `X-Event-Id`; secrets dummy and loaded from config (`messaging.callback.secrets.<keyId>`). Classes: `callback/SignatureVerifier`, `ReplayGuard`, `CallbackController`; table `replay_guard`; metric `messaging.callbacks{result}` with `invalid_signature` and `replayed` results.

Illustrative verification logic:

```java
boolean verify(String header, byte[] rawBody, byte[] secret, Instant now) {
    long t = parseTimestamp(header);
    String provided = parseV1(header);
    if (Math.abs(now.getEpochSecond() - t) > 300) return false;           // tolerance
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret, "HmacSHA256"));
    mac.update((t + ".").getBytes(StandardCharsets.UTF_8));
    mac.update(rawBody);
    byte[] expected = mac.doFinal();
    return MessageDigest.isEqual(expected, HexFormat.of().parseHex(provided)); // constant time
}
```

Details interviewers probe:

| Topic | Right answer |
|---|---|
| Sign the raw bytes | Verify against the exact received body before JSON parsing; re-serialised JSON may differ |
| Constant-time compare | `MessageDigest.isEqual`, not `equals`, to avoid timing leaks |
| Timestamp inside the signed payload | Binds time to the body so an attacker cannot refresh the timestamp |
| Tolerance window vs replay cache | Window bounds cache size; cache by event id blocks replays inside the window |
| Key rotation | `X-Provider-Key-Id` selects the secret; accept old and new during rotation |
| Order of checks | Size limit, parse header, timestamp, signature, then replay cache insert, then business processing. Insert into the replay cache only after the signature is valid, otherwise an attacker can poison it |
| Defence in depth | Dedupe on `(provider_id, provider_event_id)` in `delivery_event` (CONTRACT section 3.4) also makes processing idempotent if replay protection fails |
| Responses | Return one generic `401 INVALID_SIGNATURE` for every signature problem (implemented that way); a replayed event id gets `200 {"result":"replayed"}` so the provider stops retrying; do not reveal which check failed |

Limits to be honest about: HMAC proves the sender knows the shared secret and the payload is unmodified; it is not confidentiality (use TLS) and not per-user authentication. Asymmetric signatures or mTLS are stronger where providers support them.

## Secrets Management

1. No secrets in the repo, images, or logs. `.env.example` holds placeholders only, and the dev callback secret is the dummy `local-dev-secret-not-for-production`.
2. Inject at runtime from a secret manager (Vault, cloud KMS-backed store) or platform secret objects with encryption at rest.
3. Rotate: DB credentials (dynamic secrets if available), HMAC keys with overlap by key id, signing keys via JWKS with `kid`.
4. Least privilege: separate DB roles for the app (DML) and Flyway migration (DDL).
5. Detect: pre-commit and CI secret scanning; treat any leaked secret as compromised.
6. Redis, RabbitMQ, and Mongo require authentication and TLS in non-local profiles.

## Data Minimisation, PII Masking, and Redaction

The platform design choices (CONTRACT sections 3, 4, 5, 6):

- Store template **variables**, not the rendered body, in `payload_json`; render at dispatch time with the pinned template version.
- `DispatchEnvelope` carries ids only: no content, no PII.
- API responses return masked addresses only (`r***@example.test`, `+1555***0101`).
- `common/Redaction` is the shared utility for log and diagnostic redaction. The suppression table stores an `address_hash`, not the address.
- Retention jobs (`scheduler/RetentionJob`, `messaging.retention.*`) delete data on schedule.
- Logs carry `correlationId`, `tenant`, `messageId` in MDC, never recipient addresses or variables.

Redaction pitfalls: masking must be applied at every sink (logs, exceptions, metrics tags, Mongo diagnostics, audit `detail_json`, error responses). High-cardinality or personal values must never be metric labels. Raw callback payloads archived in Mongo may contain provider-side identifiers; treat that collection as sensitive and apply retention.

Regulatory framing (high level, not legal advice): financial and messaging contexts commonly touch consent regimes (TCPA, CAN-SPAM, GDPR-style rights) and record-keeping rules. The consent and suppression rules in CONTRACT section 3.3, and the deletion flag on recipients, are the technical hooks; the platform simulates them with synthetic data.

## TLS and mTLS

| Layer | Practice |
|---|---|
| Client to edge | TLS 1.2 or higher (prefer 1.3), HSTS, modern ciphers, automated certificate renewal |
| Edge to service | Re-encrypt or use a mesh with mTLS; do not assume the cluster network is trusted |
| Service to datastores | TLS with certificate validation for PostgreSQL, Rabbit, Redis, Mongo |
| Service to providers | TLS with hostname verification; consider mTLS or signed requests where offered |
| Inbound provider webhooks | TLS plus HMAC; optionally IP allow-list or mTLS as an extra layer |

mTLS authenticates both sides with certificates; it suits service-to-service and high-trust provider links. Costs: certificate issuance, rotation, and debugging complexity. Never disable certificate validation "to make it work" (trust-all `TrustManager` is a classic finding).

## Audit

`audit_event` is append-only (`id, tenant_id, actor, actor_role, action, entity_type, entity_id, correlation_id, detail_json, occurred_at`) and is written in the same transaction as the state change for submission (CONTRACT sections 4, 6). Exposed via `GET /api/v1/audit-events` to `AUDITOR`, `TENANT_ADMIN`, `PLATFORM_ADMIN`.

Good audit practice:

- Record who, what, when, from where (correlation id), and the before/after status, not the sensitive content.
- Audit security-relevant events: authentication failures, authorization denials, signature failures, role-restricted actions such as replay, discard, provider config change, consumer pause, template approval.
- Make tampering hard: no update or delete grants for the app role, optional hash chaining or export to write-once storage.
- Keep audit retention separate (`messaging.retention.audit-days`).
- Audit reads of sensitive data when policy requires it.

## Threat Modelling Quick Pass (STRIDE)

| Threat | Example | Mitigation |
|---|---|---|
| Spoofing | Forged webhook | HMAC plus replay guard |
| Tampering | Edited template after approval | Versioned immutable published versions; four-eyes approval; audit |
| Repudiation | Operator denies replaying | Append-only audit with actor |
| Information disclosure | Cross-tenant read | Tenant-scoped queries, 404, masking |
| Denial of service | Flood submissions | Rate limit, body cap, backpressure, bounded pools |
| Elevation of privilege | Sender calls admin endpoint | Role matrix, resource-level checks |

## Interview Q and A Snippets

**Q: Why not a 403 for cross-tenant access?** It leaks existence. Return 404 and log the attempt internally for detection.

**Q: Where do you validate the webhook signature relative to JSON parsing?** Before, on raw bytes. Then check the timestamp window, then the replay cache.

**Q: Is JWT revocation possible?** Not inherently. Use short TTLs, refresh rotation, and a deny list keyed by `jti` or `sub` for emergencies.

**Q: How do you prevent a tenant admin granting themselves platform admin?** Roles and tenant come only from the issuer's signed claims; there is no API that edits roles; `X-Tenant-Id` is ignored for non-platform principals.

## Key takeaways

- Use Authorization Code with PKCE for users and Client Credentials for services; ID tokens are not API tokens.
- Validate signature, pinned algorithm, issuer, audience, expiry, and trusted key source; keep tokens minimal.
- Stateless bearer APIs can disable CSRF; role checks and object-level checks are different layers and both are required.
- Cross-tenant access returns 404 to prevent existence leaks; derive tenant only from validated claims and filter in the query.
- Map the OWASP API Top 10 to concrete controls: BOLA, rate limits, business-flow abuse, and unsafe consumption of provider callbacks are the most relevant for messaging.
- Webhooks: HMAC over timestamp plus raw body, constant-time compare, tolerance window, replay cache after verification, and idempotent processing as defence in depth.
- Minimise and mask PII at every sink; store variables not rendered content; carry no PII in queue envelopes.
- TLS everywhere, mTLS where trust demands it, never disable certificate validation.
- Audit is append-only, transactional with the change, and focused on security-relevant actions.
- Be honest: these are design controls verified at most by tests in the repo; no external penetration test or production hardening was performed.
