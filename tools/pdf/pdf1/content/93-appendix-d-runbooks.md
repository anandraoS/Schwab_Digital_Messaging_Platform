# Appendix D. Operational Runbooks

Every alert in `observability/prometheus/alerts.yml` links to a runbook under `docs/operations/runbooks/`. The runbooks for dead-letter growth, retry storms, bulk jobs, queue backlog and high latency appear in Chapters 13, 14 and 20; the others follow here. Runbooks are design-level procedures written against the metrics and endpoints of the platform. They have **not** been exercised in a real incident or a game day (risk R12 in the register).

{{embed:docs/operations/runbooks/README.md|title=Runbook index}}

{{embed:docs/operations/runbooks/provider-outage.md|title=Runbook: Provider outage}}

{{embed:docs/operations/runbooks/provider-rate-limit.md|title=Runbook: Provider rate limit}}

{{embed:docs/operations/runbooks/consumer-crash-ambiguous-outcomes.md|title=Runbook: Consumer crash and ambiguous outcomes}}

{{embed:docs/operations/runbooks/callback-signature-failures.md|title=Runbook: Callback signature failures}}

{{embed:docs/operations/runbooks/database-outage.md|title=Runbook: Database outage}}

{{embed:docs/operations/runbooks/redis-outage.md|title=Runbook: Redis outage}}

{{embed:docs/operations/runbooks/high-api-latency.md|title=Runbook: High API latency}}

{{embed:docs/operations/data-retention-and-replay-policy.md|title=Data retention and replay policy}}
