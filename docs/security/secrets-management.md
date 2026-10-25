# Secrets Management

## Inventory
| Secret | Used by | Local value | Prod source |
|---|---|---|---|
| DB credentials | `spring.datasource.*` | H2 in-memory (none) | Secret store injected as env vars |
| RabbitMQ credentials | `RabbitMessageBus` | n/a (memory bus) | Secret store |
| Redis password | coordination adapter | n/a | Secret store |
| Mongo URI/credentials | `MongoConfig` | in-memory | Secret store |
| Callback HMAC secrets per `keyId` | `callback.SignatureVerifier` | `local-dev-secret-not-for-production` (dummy) | Secret store, referenced by `provider_config.hmac_key_ref` |
| Provider API credentials | provider adapters | none (simulators) | Secret store |
| JWT signing key | IdP (prod); dev: in-memory generated RS256 key per run | ephemeral | IdP-managed; app only has `issuer-uri`/JWKS |
| OTLP headers/tokens | tracing exporter | disabled | Secret store |

## Rules
1. No secret in git, images, `application*.yml`, test fixtures, logs, docs, or CI files. Only the labelled dummy value above is allowed, and only for `local`/`test`.
2. Secrets are injected through environment variables or mounted files (`*_FILE` convention), mapped in `PropertiesConfig`; the app fails fast at startup in `prod-like` if a required secret is missing or equals the dummy value (see source for the startup guard).
3. `hmac_key_ref` stores a *reference name*, not the secret.
4. Secrets are never echoed by `/actuator/env` (exposure disabled; Spring sanitizes keys such as `*secret*`, `*password*`, `*token*`).
5. `.env.example` (or `env.example`) holds only placeholders like `CHANGE_ME`.
6. Logs: `Redaction` masks values for keys matching `(?i)(secret|password|token|authorization|api[-_]?key|signature)`.

## Deployment mapping
| Platform | Mechanism | Notes (not executed here) |
|---|---|---|
| Kubernetes | `Secret` objects (encrypted at rest with KMS provider) or External Secrets Operator / CSI driver from a vault | RBAC limit `get secret`; never in Helm values committed to git |
| Cloud Foundry | user-provided service instances / CredHub | bound as env vars in manifest |
| CI | CI credentials store; masked variables | no secrets in pipeline files |

## Rotation
| Secret | Procedure |
|---|---|
| Callback HMAC | Two active `keyId`s: provider starts signing with new `keyId` (`X-Provider-Key-Id`), platform accepts both for overlap window, retire old after traffic confirms. Metric `messaging.callbacks{result="invalid_signature"}` must stay flat |
| DB password | Create second DB user, roll deployment, revoke old; Hikari `max-lifetime` ensures reconnect |
| Provider API keys | Provider-specific dual-key overlap |
| JWT keys | IdP JWKS rotation; resource server caches JWKS and refreshes on unknown `kid` |
Compromise response: revoke, rotate, search logs/audit for use, post-incident review (`docs/operations/post-incident-review-template.md`).

## Detection
- Secret scanning (gitleaks or equivalent) as CI/pre-commit stage; configured statically, **not executed** here.
- Review checklist item: "no new secret-like literals" (`ai-engineering/checklists/security-review.md`).
- AI-assisted coding rule: never paste real secrets into prompts; assistants must use placeholders (see `AGENTS.md`).
