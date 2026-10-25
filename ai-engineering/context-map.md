# Context Map and Repository Map

## Task to context
| Task | Load (in order) | Skip |
|---|---|---|
| Change lifecycle/status logic | `context/lifecycle.md`, ADR-0003, `docs/architecture/dispatch-retry-flow.md` | db, frontend |
| Add/modify endpoint | `context/api.md`, `context/security.md`, `standards/api-design.md`, CONTRACT section 5 | observability |
| DB change/migration | `context/db.md`, `standards/db-migrations.md`, `docs/database/schema-evolution.md` | api |
| Provider work | `context/architecture.md`, ADR-0002, `specs/0002-new-provider-adapter.md`, `docs/architecture/callbacks-normalization.md` | |
| Retry/DLQ/reconciliation | `context/lifecycle.md`, ADR-0006, ADR-0010, `docs/architecture/reconciliation-flow.md` | |
| Security review | `context/security.md`, `docs/security/threat-model.md`, `checklists/security-review.md` | |
| Write tests | `context/testing.md`, `checklists/test-generation.md`, `docs/architecture/reliability-scenarios.md` | |
| Incident/debug | `debugging-workflows.md`, `context/observability.md`, runbooks | |
| Angular work | `standards/angular.md`, CONTRACT section 10 | backend context |
| Performance | `checklists/performance-review.md`, `docs/operations/performance-investigation-guide.md` | |
| New feature | `spec-driven-workflow.md`, `specs/spec-template.md` | |

Always available (small): `AGENTS.md`, the invariants list in `context-engineering.md`.
Binding source: `docs/design/CONTRACT.md` (large: load only the section you need: 3 lifecycle/policy, 4 schema, 5 API, 6 dispatch, 7 batch, 8 metrics, 9 config).

## Repository map
```
<root>/
  AGENTS.md                      cross-tool agent rules
  backend/                       Spring Boot 3.5.16, Java 21, single module messaging-platform
    src/main/java/com/example/messaging/{common,config,security,domain,repository,service,template,provider,messaging,callback,batch,diagnostics,web,scheduler,metrics}
    src/main/resources/{db/migration,db/oracle,openapi,application*.yml}
    src/test/java/...            mirrors main packages; AbstractIntegrationTest; FailureRecoveryIT
  frontend/messaging-console/    Angular 20 standalone/Signals app
  infrastructure/                docker, compose, kubernetes, helm, rabbitmq, postgres, mongo (static validation only)
  cloud-foundry/                 manifest (not executed)
  observability/                 prometheus, grafana, otel, alerts
  tests/                         end-to-end, api-http, security, failure-recovery
  performance/                   k6 (not executed), jvm-harness
  docs/
    design/                      CONTRACT.md (initial plan), DEVIATIONS.md (what was built; wins), CONSISTENCY_LOG.md, requirements-and-assumptions.md
    adr/                         0001..0012 + README
    architecture/                flows (Mermaid), ER, deployment, semantics, reliability scenarios
    database/ security/ operations/ (runbooks/) leadership/ api/ interview/ pdf/ traceability/ validation/
  ai-engineering/                this workspace
  specs/                         spec-driven artifacts
  scripts/ tools/pdf/            helper scripts
  .github/                       copilot-instructions, instructions/, prompts/, PR template, CODEOWNERS, workflows
```

## Package responsibilities (one line each)
`common` ids/errors/redaction; `config` Spring config and properties records; `security` principal, tenant, filters; `domain` enums, entities, state machine; `repository` JPA; `service` use cases and policy; `template` safe renderer; `provider` ports, simulators, resilience; `messaging` bus, consumer, DLQ, replay; `callback` webhook auth and normalization; `batch` bulk job; `diagnostics` Mongo store; `web` controllers/DTOs; `scheduler` outbox, poller, sweeper, retention; `metrics` meters.

## Where truth lives
| Fact | Source of truth |
|---|---|
| States, transitions | `domain.MessageStateMachine` (mirrors CONTRACT 3.1, ADR-0003) |
| Schema | Flyway migrations |
| API shape | `messaging-api-v1.yaml` |
| Metric names | CONTRACT section 8 / `metrics.MessagingMetrics` |
| Validation results | `docs/validation/VALIDATION_REPORT.md` only |
