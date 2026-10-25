# Skill: Security Review of a Change

**When**: any change touching authn/z, tenancy, callbacks, templates, secrets, logging, or new endpoints. **Load**: `context/security.md`, `checklists/security-review.md`, `docs/security/threat-model.md`.

## Steps
1. Identify the trust boundary crossed by the diff and the matching threat rows (T1-T18).
2. For each new input: validation, size bounds, encoding, who controls it.
3. For each new data access: tenant predicate present? role check? 404 vs 403 policy?
4. For each log/response/bus/Mongo write: sensitive fields? masked/redacted?
5. For callback code: raw-body HMAC, constant-time compare, tolerance, replay guard, no pre-verification parsing side effects.
6. For template code: allow-list intact, no evaluation, escaping.
7. For config: no secrets; dev-only features gated by profile; actuator exposure unchanged.
8. Try to break it: write a failing test for each plausible abuse (cross-tenant id, forged signature, oversized body, missing role).
9. Report findings: severity, location, exploit sketch, fix, test. Do not fix silently in the same step as reviewing if you are the independent reviewer: report to the author.

## Output
Review note (findings table + verdict: approve/changes requested) attached to the PR.
