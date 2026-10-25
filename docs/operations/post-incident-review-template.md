# Post-Incident Review (blameless) Template

Copy to `docs/operations/pir/YYYY-MM-DD-short-title.md` (create the folder when needed). Blameless: describe system and process conditions, not individuals.

## 1. Summary
- Title, date, severity (SEV1-4), duration, author, reviewers.
- One paragraph: what happened, who/what was affected, how it ended.

## 2. Impact
| Dimension | Value |
|---|---|
| Messages affected (by status, channel, priority, tenant) | |
| Delayed by (p50/p95/max) | |
| Lost / duplicated / unknown-state messages | |
| SLO error budget consumed | |
| Customer or business impact (qualitative) | |

## 3. Timeline (UTC)
| Time | Event | Source (alert, log, metric, human) |
|---|---|---|
| | first symptom | |
| | detection | |
| | response started | |
| | mitigation applied | |
| | resolved | |
Time to detect / acknowledge / mitigate / resolve.

## 4. Detection and response assessment
Did alerts fire? Right threshold? Was a runbook used? What was missing from the runbook?

## 5. Root cause and contributing factors
Use "5 whys" and list: trigger, latent conditions, missing safeguards. Reference ADRs/invariants violated or confirmed (e.g., ADR-0010 held / not held).

## 6. Data consistency review (platform-specific)
- Any messages in `DELIVERY_UNKNOWN` created? Resolved how?
- Any duplicates sent (compare attempts with same `idempotencyRef`)? 
- Any accepted-but-never-queued messages (outbox NEW age)?
- DLQ contents and replay decisions (audit event ids).

## 7. What went well / what went poorly / where we got lucky

## 8. Action items
| # | Action | Type (prevent/detect/mitigate) | Owner | Due | Tracking link |
|---|---|---|---|---|---|

## 9. Lessons for AI-assisted development (if relevant)
Did generated code or a skipped checklist contribute? Which guardrail (checklist, test, ADR) should change?

## 10. Appendices
Graphs (names of dashboards/panels), log queries, config diffs, correlation ids.
