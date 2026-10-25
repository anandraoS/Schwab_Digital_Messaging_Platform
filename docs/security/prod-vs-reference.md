# Production vs Reference: Gap Analysis

This repository is an independent reference implementation with synthetic data and simulated providers. It is not production-ready and makes no compliance claim. This list is deliberately blunt.

| Area | Reference state | What production needs |
|---|---|---|
| Compliance review | None. Consent model is an engineering abstraction | Legal/privacy review per jurisdiction and channel (e.g., telephony and email marketing rules, data residency, retention schedules); documented lawful basis; records of processing |
| Access governance | Roles defined in code; JWT from dev issuer locally | Enterprise IdP, joiner/mover/leaver, periodic access reviews, break-glass process, privileged access management, separation of duties for ops roles |
| Real provider integration | Simulators only | Vendor contracts, sandbox testing, real error taxonomy mapping, webhook IP allow-lists, volume tiers, deliverability monitoring (SPF/DKIM/DMARC, sender reputation, short code registration) |
| Disaster recovery | Not exercised; no multi-region | RPO/RTO targets, tested backup/restore of PostgreSQL, Rabbit definitions + quorum queues, cross-AZ/region design, DR drills, runbook validation |
| Penetration testing | None executed | Independent pen-test, DAST, authenticated API fuzzing, threat model review with security team |
| Static/dep scanning | Config only, not executed | SAST, SCA, container scanning, secret scanning enforced as gates with triage SLAs |
| Secrets/KMS | Env-based; dev dummy secret | Vault/KMS-backed, rotation automation, HSM for signing where required |
| Encryption | TLS assumed at ingress; no at-rest config in repo | At-rest encryption (DB/volume), TLS everywhere incl. data stores, field-level encryption for contact data where mandated |
| Tenant isolation | Shared schema, app-enforced | PostgreSQL RLS as defense in depth; per-tenant quotas; possibly schema/DB per regulated tenant |
| Capacity | Not load-tested on real infra; k6 scripts not executed | Load and soak tests on prod-like infra, capacity model, autoscaling validation, chaos tests |
| Observability | Metrics/dashboards defined; not rendered; alerts illustrative | Real SLOs agreed with stakeholders, paging rotation, dashboards in use, trace sampling policy, log retention and access control |
| Rate limiting | App-level per client | Edge/WAF/API gateway limits, bot protection, per-tenant spend quotas |
| Audit | Append-only by convention | WORM storage/DB privileges, SIEM forwarding, tamper evidence |
| Data lifecycle | Retention job, no erasure workflow | Subject-request workflows, legal hold, verified purge |
| Supply chain | Pinned BOM | SBOM, signed images, provenance (SLSA), base-image patch cadence |
| Release process | Static pipeline files; no CI run claimed | Protected branches, required reviews, environment promotion, canary/blue-green, automated rollback |
| JWT handling | Validation yes; revocation no | Token introspection or short lifetimes + revocation events; mTLS-bound tokens |
| Operational maturity | Runbooks written, never exercised in an incident | Game days, on-call training, PIR culture, error-budget governance |
| Accessibility/UX | Basic Angular Material | Accessibility audit (WCAG), localization review |
| Cost controls | None | Per-channel cost tracking, anomaly alerts on send volume |

## What the reference does demonstrate
Design for failure (ADR-0003/4/6/9/10), tenant isolation tests, HMAC callbacks with replay protection, bounded resources, auditability, template governance, documented honesty about unexecuted assets.

## How to use this document
Treat each row as an input to a real project's risk register (`docs/leadership/risk-register.md` template) rather than as a checklist already satisfied.
