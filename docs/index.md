# Documentation Map

Start with the root [`README.md`](../README.md). This project is an independent reference implementation based on a job description; it is not any employer's system and all data is synthetic. Executed results live only in `validation/VALIDATION_REPORT.md`.

## Which document wins?
`design/DEVIATIONS.md` records what was actually built and wins over `design/CONTRACT.md` (the initial plan). `design/CONSISTENCY_LOG.md` lists documentation fixes made to align the rest.

## Design and decisions
| Document | Purpose |
|---|---|
| [design/requirements-and-assumptions.md](design/requirements-and-assumptions.md) | requirements, scope, out-of-scope, assumptions |
| [design/CONTRACT.md](design/CONTRACT.md) | initial binding plan (partly superseded) |
| [design/DEVIATIONS.md](design/DEVIATIONS.md) | what was built and why it differs |
| [design/CONSISTENCY_LOG.md](design/CONSISTENCY_LOG.md) | documentation consistency fixes |
| [adr/README.md](adr/README.md) | ADR index (0001-0013; 0013 is Proposed, not implemented) |
| [../specs](../specs/spec-template.md) | specs: 0001 priority aging (not implemented), 0002 new provider adapter (implemented as simulator) |

## Architecture
[architecture/architecture-overview.md](architecture/architecture-overview.md), [submission-flow](architecture/submission-flow.md), [dispatch-retry-flow](architecture/dispatch-retry-flow.md), [callback-flow](architecture/callback-flow.md), [callbacks-normalization](architecture/callbacks-normalization.md), [reconciliation-flow](architecture/reconciliation-flow.md), [bulk-job-flow](architecture/bulk-job-flow.md), [template-approval-flow](architecture/template-approval-flow.md), [delivery-semantics](architecture/delivery-semantics.md), [reliability-scenarios](architecture/reliability-scenarios.md), [deployment-views](architecture/deployment-views.md), [er-diagram](architecture/er-diagram.md).

## Data
[database/data-model.md](database/data-model.md), [mongodb-usage](database/mongodb-usage.md), [oracle-mapping](database/oracle-mapping.md), [query-plans-and-indexes](database/query-plans-and-indexes.md), [retention-and-archival](database/retention-and-archival.md), [schema-evolution](database/schema-evolution.md), [transactions-and-connection-pooling](database/transactions-and-connection-pooling.md).

## Security
[security/threat-model.md](security/threat-model.md), [owasp-api-top10-2023-analysis](security/owasp-api-top10-2023-analysis.md), [service-to-service-auth](security/service-to-service-auth.md), [secrets-management](security/secrets-management.md), [data-minimization-and-retention](security/data-minimization-and-retention.md), [prod-vs-reference](security/prod-vs-reference.md).

## Operations
[operations/runbooks/README.md](operations/runbooks/README.md) (every alert in `observability/prometheus/alerts.yml` maps to a runbook), [observability](operations/observability.md), [slis-slos](operations/slis-slos.md), [ci-cd](operations/ci-cd.md), [deployment/README](operations/deployment/README.md), [kubernetes-guide](operations/kubernetes-guide.md), [cloud-foundry-guide](operations/cloud-foundry-guide.md), [performance-tests](operations/performance-tests.md), [performance-investigation-guide](operations/performance-investigation-guide.md), [bulk-test-plan](operations/bulk-test-plan.md), [data-retention-and-replay-policy](operations/data-retention-and-replay-policy.md), [release-readiness-checklist](operations/release-readiness-checklist.md), [rollback-plan](operations/rollback-plan.md), [post-incident-review-template](operations/post-incident-review-template.md).

## Product, engineering management and leadership artifacts
Conceptual documents, not records of a real team: [leadership/prd.md](leadership/prd.md), [acceptance-criteria](leadership/acceptance-criteria.md), [feature-specification](leadership/feature-specification.md), [hld](leadership/hld.md), [lld](leadership/lld.md), [api-contract-guide](leadership/api-contract-guide.md), [test-strategy](leadership/test-strategy.md), [sprint-task-breakdown](leadership/sprint-task-breakdown.md), [risk-register](leadership/risk-register.md), [pr-checklist](leadership/pr-checklist.md), [code-review-scenarios](leadership/code-review-scenarios.md), [mentoring-examples](leadership/mentoring-examples.md), [troubleshooting-scenarios](leadership/troubleshooting-scenarios.md).

## API
[api/openapi.yaml](api/openapi.yaml): identical copy of `backend/src/main/resources/openapi/messaging-api-v1.yaml`, enforced by `OpenApiContractIT`.

## Traceability and validation
[traceability/job-description-traceability-matrix.md](traceability/job-description-traceability-matrix.md) (+ `.csv`), [validation/VALIDATION_REPORT.md](validation/VALIDATION_REPORT.md) (executed results; produced separately).

## Interview preparation
[interview/00-how-to-use.md](interview/00-how-to-use.md) and the files beside it: study material, not evidence of production experience. PDFs of the documentation are built into `pdf/` by `tools/pdf`.

## AI-assisted engineering
[../ai-engineering/README.md](../ai-engineering/README.md), [../AGENTS.md](../AGENTS.md), `../.github/copilot-instructions.md`.
