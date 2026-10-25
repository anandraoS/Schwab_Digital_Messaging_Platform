---
mode: agent
description: Performance review of a diff or component against the project checklist
---
Review `${input:target:diff or class names}` for performance risks.

Load `ai-engineering/checklists/performance-review.md`, `docs/database/transactions-and-connection-pooling.md`, `docs/database/query-plans-and-indexes.md`.

Check and report with file:line:
- statements per request/message (N+1, lazy loading in mappers, missing pagination)
- index support for each new query and the write-amplification cost on `message_request`
- transactions held across remote calls; pool sizing impact (`hikaricp_*`)
- unbounded pools, queues, caches, collections; streaming vs loading whole files
- provider call path: timeouts, bounded executor, retry amplification, jitter
- logging and allocation in hot paths

For each finding give: impact estimate with reasoning (label as estimate), fix, and the measurement that would confirm it (jcmd/JFR/EXPLAIN/statement-count test from `docs/operations/performance-investigation-guide.md`). Do not state measured numbers unless you ran a measurement and quote the command.
