# Data Minimization and Retention

All data in this repository is synthetic. The design nevertheless applies production-grade minimization so the pattern is demonstrable. Not legal advice; real programs need privacy/compliance review.

## Data classification
| Class | Examples | Handling |
|---|---|---|
| Restricted | Rendered message body, provider credentials, HMAC secrets, push tokens | Body is never persisted by the platform; secrets in secret store; push tokens not returned by API |
| Confidential | Email, phone, recipient name | Stored only in `recipient`; API returns masked (`r***@example.test`, `+1555***0101`); logs mask; bus has ids only; `suppression` stores hash only |
| Internal | message ids, statuses, metrics, audit | Normal controls, tenant-scoped |
| Public | OpenAPI spec, docs | n/a |

## Minimization decisions
| Decision | Where | Rationale |
|---|---|---|
| Store template variables, not rendered body (`payload_json`) | `message_request`, ADR-0007 | Avoid storing regulated wording with personal data; re-render from pinned version for support if needed |
| Variables validated against template declaration; unknown keys rejected | `VariableValidator` | Prevent arbitrary data hoarding |
| Envelope carries ids only | `DispatchEnvelope` | Broker is a wider access surface |
| Mask addresses in all API responses | `web` DTO mappers | Operators rarely need full address |
| Hash addresses for suppression | `suppression.address_hash` | Block without retaining address |
| Redact provider diagnostics | `common.Redaction` before Mongo write | Avoid copying PII to second store |
| Correlation id instead of business ids in logs where possible | MDC | Traceability without content |
| Mongo TTL | `expireAt` | Short life for raw payloads |
| Audit stores `detail_json` with ids and field names, not values, for preference/template changes | `AuditService` | Audit without duplicating sensitive values (see source for exact fields) |

## Retention
See `docs/database/retention-and-archival.md` for numbers. Principles: shortest retention that supports support/dispute/audit needs; audit outlives data it describes; non-terminal rows are never purged silently; purge actions are counted and audited.

## Data subject requests (design)
| Request | Approach (not automated in the reference) |
|---|---|
| Access | Search by `external_ref` and recipient id; masked views via API; full export is a controlled DB task |
| Erasure | Mark `deleted` (stops sending), null contact columns, delete preferences, anonymize audit actors where required, keep hash suppression |
| Consent change | `PUT /recipients/{id}/preferences` with optimistic lock, audited |

## Test data rules
No real names, emails, phone numbers: `Recipient 001`, `recipient001@example.test`, `+1555010xxxx`. CI and docs must not contain real data. `SyntheticDatasetGenerator` produces the bulk files.

## Logging rules
Never log: bodies, full addresses, tokens, signatures, secrets, raw callback payloads at INFO. Allowed: ids, statuses, counts, masked addresses, error codes. Enforced by review checklists and a log capture test (see `docs/security/threat-model.md` T6).
