# ADR-0007: Template versioning and pinning

Status: Accepted (2026-10-18)

## Context
Templates carry legal and regulatory wording (trade confirmations, security alerts). A template edit must never change what an already-accepted message says, must be reviewed (four-eyes), and must be traceable to the exact text a customer received. Rendering happens at dispatch time, possibly minutes after acceptance, so late edits are a real hazard.

## Decision
- `template` (identity: tenant, code, channel, category) has many immutable-once-published `template_version` rows, unique per `(template_id, version_no, locale)`. Status `TemplateStatus{DRAFT, PENDING_APPROVAL, PUBLISHED, RETIRED}`.
- **Pin at accept time**: `SubmitMessageRequest.template.version = null` resolves to the latest PUBLISHED version for the locale **at acceptance** and writes `template_code`, `template_version_no`, `template_locale` onto `message_request`. An explicit version is validated to exist and be PUBLISHED (or RETIRED-but-still-allowed only if config permits; default: PUBLISHED only).
- **Render at dispatch with the pinned version**, even if that version has since been RETIRED. Retiring prevents *new* messages from pinning it; it does not break accepted ones. The render-check at submission validates variables (`VariableValidator`) but does not store the rendered body (data minimization; store variables, not body).
- **Edits**: only DRAFT versions are editable (optimistic lock via `If-Match`/`version`). Published content is immutable; a change is a new version.
- **Approval flow**: author submits (`DRAFT to PENDING_APPROVAL`); approver (different principal, four-eyes) publishes; on approve, the previously PUBLISHED version of the same locale becomes RETIRED; reject returns to DRAFT with comment. All steps audited.
- **Locale fallback**: requested locale, then language-only, then tenant default; the *resolved* locale is what gets pinned.

### What happens to accepted messages if a template changes?
| Event after acceptance | Effect on accepted (not yet sent) messages |
|---|---|
| New version published | None: they render with the pinned older version |
| Pinned version retired | None: still renders; audit notes pinned-retired send |
| Pinned version content found harmful | Operator cancels (`/cancel`, status VALIDATED/QUEUED/RETRY_PENDING) or bulk-cancels by filter; a retire alone does not stop them. Emergency lever: provider/consumer pause (`/ops/consumers/pause`) |
| Variables of the pinned version no longer satisfiable | Cannot happen: variable validation occurred at accept time against the same pinned version |

## Alternatives considered
| Option | Why not |
|---|---|
| Always render latest at dispatch | Silent content change after acceptance; unauditable |
| Snapshot rendered body at accept | PII/regulated content stored at rest; larger rows; violates minimization |
| Copy template body into each message | Same storage issue, no dedupe |
| Mutable templates with history table | Hard to prove what was sent |

## Consequences
- Positive: reproducible sends; safe rollout; audit by `(template_code, version_no, locale)`.
- Negative: stale wording may still go out for already-accepted messages (accepted trade-off; mitigated by cancel tooling).
- Risk: `TemplateRenderer` allow-list evolution must not break old versions: renderer changes are versioned and regression-tested against fixtures (`TemplateRendererTest`).

## Validation
- Tests: pin-at-accept, retire-after-accept still renders, approver != author, retire-then-new-submissions-use-new (see source `service`/`template` tests).
- Security: template injection controls in `docs/security/threat-model.md` (allow-listed `{{var}}`, no expressions, HTML escaping).
