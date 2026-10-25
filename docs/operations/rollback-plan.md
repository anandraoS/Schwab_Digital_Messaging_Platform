# Rollback Plan

Principle: every release must be rollback-safe by construction (expand-only schema, backward-compatible messages and API) so rollback is "deploy previous version", not "restore data". Procedures below are design-level; not executed here.

## Decision criteria (during canary or after full rollout)
Roll back when any holds for > 5 min (illustrative): API 5xx burn alert firing; dispatch delay p95 > 2x baseline; DLQ growth with new reason; any suspected duplicate-send or wrong-recipient/content behavior (roll back immediately); provider success rate regression correlated with release; data-consistency invariant violated (see `ai-engineering/context-engineering.md` invariants).

## Compatibility requirements (checked in release readiness)
| Surface | Rule |
|---|---|
| Database | Previous app version must run on the new schema: only additive migrations; no drop/rename in same release |
| Broker | `DispatchEnvelope` changes are additive; consumers ignore unknown fields; old consumers must handle new envelopes (rolling mixed versions) |
| API | Additive only within `/api/v1`; removal needs version/deprecation |
| Config | New keys have safe defaults; removing a key waits one release |
| Templates/policies | Data-driven; no code coupling |
| Redis/Mongo | Key/collection shapes versioned; additive |

## Procedures
### Kubernetes (not executed)
1. `kubectl rollout undo deployment/messaging-api` (or Helm `helm rollback messaging <revision>`).
2. Watch `kubectl rollout status`; verify readiness; check queue depth and DLQ.
### Cloud Foundry (not executed)
1. Blue-green: map route back to the previous app (`cf map-route` / `cf unmap-route`), or `cf push` previous droplet (`cf rollback <app>` if the CLI version supports it).
### Feature flag / config rollback
Prefer disabling the feature via config/flag (e.g., priority aging off, provider disabled) which is faster than redeploy.

## Data handling after rollback
| Situation | Action |
|---|---|
| Messages accepted by new version | remain valid; old version processes them (compatibility rule) |
| Messages in `DELIVERY_UNKNOWN` created during bad window | reconcile as usual; do not bulk-fail |
| Wrongly sent content | cannot be recalled: incident process, customer comms owned by business; stop further sends: pause consumers, cancel queued messages by filter |
| DLQ growth during bad window | analyze; replay after fix per replay policy |
| Schema expanded columns unused | leave until next release; contract phase later |

## Forward-fix vs rollback
Forward-fix only when the fix is trivial, tested, and risk of staying on the bad version is low. Otherwise roll back first.

## Verification after rollback
Smoke test, SLIs normalized, backlog draining, no new error classes, audit entry for rollback decision (who/when/why).

## Post-rollback
PIR (`post-incident-review-template.md`), add regression test, update checklists/invariants if a class of bug was missed.
