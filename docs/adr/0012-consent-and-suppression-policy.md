# ADR-0012: Consent, suppression and quiet-hours policy

Status: Accepted (2026-10-18)

## Context
Sending marketing without consent, or to an address that hard-bounced or complained, creates legal exposure and damages sender reputation. Yet security alerts and transaction confirmations must reach the customer. The rules must be ordered, testable and impossible to bypass through fallback channels, bulk jobs or replay. (This is an engineering model for a synthetic system, not legal advice; real programs need compliance review, see `docs/security/prod-vs-reference.md`.)

## Decision
`service.PolicyService` evaluates, in this order (CONTRACT 3.3; this ADR is the policy reference, there is no separate `policy.md`; implemented in `PolicyService`, reasons in `PolicyReason`):
1. Recipient exists in tenant and is not `deleted`.
2. **Suppression** (`suppression` table: hard bounce, complaint, regulatory) blocks **all categories** on that channel for that tenant: `REJECTED(SUPPRESSED)`. The scope is **per tenant**: the schema key is `UNIQUE(tenant_id, channel, address_hash)` and `SuppressionRepository` lookups include the tenant, so there is no cross-tenant (global) list in this implementation.
3. **Category rules**: MARKETING needs explicit channel opt-in and `marketingOptIn=true` else `REJECTED(OPTED_OUT)`. TRANSACTIONAL, SECURITY_ALERT, SERVICE ignore marketing opt-out but never step 2. Per-channel `enabled` applies only to SERVICE.
4. **Quiet hours** in the recipient's time zone (default 21:00-08:00): MARKETING and LOW/NORMAL SERVICE are deferred (still VALIDATED, `scheduled_at` moved to window end). SECURITY_ALERT always sends. TRANSACTIONAL sends unless channel is SMS/PUSH and priority is LOW.
5. **Fallback** channels are evaluated through steps 1-4 independently; fallback never bypasses consent.
6. Idempotency (ADR-0004).

Evaluation points: at submission **and again at dispatch time** (consumer), because consent can change between accept and send. A rejection at submission (opt-out, suppression, deleted recipient, no address, expiry before a deferred send) is persisted as a `REJECTED` message (ACCEPTED to REJECTED, audited) and answered **202** with `status=REJECTED` and `statusReason`, not a 4xx; this keeps idempotent replay and the audit trail consistent (`docs/design/DEVIATIONS.md`, stage 1). `POLICY_SUPPRESSED` stays in the error-code enum but is not returned by the implemented API. A rejection decided at dispatch time moves the claimed message (PROCESSING) to `REJECTED` with `status_reason`.
Hard bounce and complaint callbacks write `suppression` rows automatically. Suppression stores an `address_hash`, not the address, and is keyed `UNIQUE(tenant_id, channel, address_hash)`.
Unsubscribe callbacks (`UNSUBSCRIBED`) update preferences with an audit event. Preference writes use optimistic locking (`version`, 409 `CONFLICT_OPTIMISTIC_LOCK`).

## Alternatives considered
| Option | Why not |
|---|---|
| Check consent only at submission | Stale consent at send time |
| A global (cross-tenant) suppression list | Shared reputation risk is real, but a global list leaks one tenant's recipient data signals to another and needs a platform-level owner. This implementation is tenant scoped; a platform-wide list would be a future ADR |
| Treat fallback as a new request | Would let callers bypass opt-out by listing channels |
| Block all categories on opt-out | Customers would miss security alerts |

## Consequences
- Positive: auditable ordered rules; one place to change policy; table-driven tests.
- Negative: dispatch-time evaluation adds reads (cached per-recipient preference snapshot with short TTL is an optimization, not a correctness dependency).
- Risk: category misuse (a marketing message flagged TRANSACTIONAL). Mitigation: category is bound to the template (`template.category`) and validated against the request, template approval reviews category, audit sampling.

## Validation
- `PolicyServiceTest` table-driven over (category x channel x consent x suppression x quiet hours x priority); fallback-consent test; replay re-evaluates policy.
- Metrics: transitions to REJECTED by reason (`messaging.transitions{to="REJECTED"}`); see source for reason tags.
