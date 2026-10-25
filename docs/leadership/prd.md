# Product Requirements Document (example)

Product: Omnichannel Messaging and Notification Orchestration Platform (reference implementation, synthetic data). Document status: example PRD written to show structure; metrics are illustrative.

## 1. Problem
Business applications (account alerts, trade confirmations, onboarding, marketing) each integrate directly with email/SMS/push vendors. Result: inconsistent consent handling, duplicate sends on retries, no unified delivery status, vendor lock-in, and slow incident response ("did the customer get it?").

## 2. Goals
| # | Goal | Measure (illustrative) |
|---|---|---|
| G1 | One API to send EMAIL/SMS/PUSH with a consistent lifecycle | 100% of pilot apps integrated via `POST /api/v1/messages` |
| G2 | Never lose an accepted message; never silently duplicate | 0 accepted-lost; duplicate rate < 0.01% |
| G3 | Enforce consent, suppression and quiet hours centrally | 0 marketing sends to opted-out recipients (audited) |
| G4 | Honest delivery status incl. "unknown" | status answer within 1 call (`/messages/{id}/status`) |
| G5 | Operable at scale: backlog, DLQ, replay, reconcile | MTTR for provider outage < 30 min with runbook |
| G6 | Provider-agnostic: add a vendor in days | `AcmeSmsProvider` example without orchestration change |
| G7 | Governed templates | 100% of production templates four-eyes approved and versioned |

## 3. Non-goals
Real vendor integration, campaign authoring/segmentation, A/B testing, in-app inbox, claims of regulatory compliance.

## 4. Personas
| Persona | Needs | Role |
|---|---|---|
| Application developer (service client) | simple, idempotent API, clear errors | `ROLE_SENDER` |
| Template author / approver | safe preview, versioning, approval | `ROLE_TEMPLATE_AUTHOR` / `ROLE_TEMPLATE_APPROVER` |
| Operations engineer | queue/DLQ visibility, replay, provider controls | `ROLE_OPERATOR` |
| Tenant admin | preferences, suppressions, audit | `ROLE_TENANT_ADMIN` |
| Auditor | read-only audit, delivery events | `ROLE_AUDITOR` |
| Platform admin | provider config, consumer pause | `ROLE_PLATFORM_ADMIN` |

## 5. User stories (selected)
1. As a developer I submit a message with an idempotency key so that retries never double-send.
2. As a developer I query status and see whether it was accepted by the provider vs delivered.
3. As a marketer's service I send a bulk CSV job with a rate cap that restarts safely.
4. As an approver I publish a template version only if I am not its author.
5. As an operator I see DLQ items with reason and replay them after fixing the cause.
6. As an auditor I see who changed preferences/templates and when.
7. As a compliance owner I want marketing sends suppressed for opted-out recipients even on fallback channels.

## 6. Functional requirements
FR1 Submit/query/cancel messages (CONTRACT section 5). FR2 Template lifecycle with pinning (ADR-0007). FR3 Policy enforcement (ADR-0012). FR4 Provider routing, retry, fallback (ADR-0002/0006). FR5 Delivery event ingestion and normalization. FR6 Reconciliation of unknown outcomes (ADR-0010). FR7 Bulk jobs. FR8 Ops console (Angular). FR9 Audit trail. FR10 Metrics/tracing/logs.

## 7. Non-functional requirements
Availability and latency SLOs (illustrative, `docs/operations/slis-slos.md`); security (tenant isolation, HMAC callbacks, no content at rest); scalability (horizontal consumers); portability (CF and Kubernetes); testability (H2 profile, simulators).

## 8. Success metrics
Delivery-status coverage, time-to-detect provider issues, DLQ age, duplicate reports, approval cycle time, onboarding time per app.

## 9. Risks and open questions
See `risk-register.md`. Open: per-tenant quotas, cost attribution, multi-region.

## 10. Release plan
Sprint plan in `sprint-task-breakdown.md`; phased rollout: pilot tenant, canary, GA (design-level).
