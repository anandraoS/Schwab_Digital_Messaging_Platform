# Integration owner notes (what changed during integration and validation)

All items below were executed and verified unless marked otherwise. Commands are relative to the repository root.

## Backend
- OpenAPI: `createRecipients`, `sort` on the bulk list endpoints, `Location` on the 202s and `lineNo=0` (job-level failure) added to
  `backend/src/main/resources/openapi/messaging-api-v1.yaml`; `docs/api/openapi.yaml` is the identical copy (`OpenApiContractIT` passes).
- Oracle reference DDL now also has `processed_message`, `replay_guard`, `bulk_job`, `bulk_job_failure` (+ unique `(job_id, line_no)`), `bulk_job_row`, `legal_hold`
  and the retention age indexes (the V50 dispatch indexes were already there).
- `RetentionJob` already purged `replay_guard` older than 24 h (`messaging.retention.replay-guard-hours`) with a test (`RetentionJobTest`); nothing to add.
- New safety net `ScheduledDispatchPoller.pollStaleQueued`: QUEUED messages older than `messaging.pipeline.queued-stale-after` (default 10 m) with no
  `processed_message` for their next dispatch and no pending outbox row are re-published; a CAS (`MessageRequestRepository.touchQueued`) keeps concurrent pollers to one publish.
  Tests: `ChannelDispatchIT.pollerRepublishesAStaleQueuedMessage...`, `...LeavesQueuedMessagesParkedInTheOutbox...`.
- `application-prod-like.yml`: `messaging.bulk.upload-dir: ${BULK_UPLOAD_DIR:${java.io.tmpdir}/messaging-bulk-uploads}`.
- `pom.xml`: `finalName` = `messaging-platform` (jar `backend/target/messaging-platform.jar`); profiles `static-analysis` (SpotBugs 4.8.6.6 + Checkstyle 3.6.0/10.21.4, rules in
  `backend/config/`), `security-scan` (OWASP dependency-check 12.1.0, `failBuildOnCVSS` 7.0, skipped automatically by profile `no-nvd-key` when `NVD_API_KEY` is not set),
  `cloud` (`java-cfenv-boot` 3.4.0, resolves and packages), `containers` unchanged. `security-scan` was **not executed** (no NVD key).
- Static analysis findings fixed (not suppressed): redundant null check (`BulkSkipListener`), catch of generic `Exception` where none is thrown (`DispatchConsumer.options`,
  `TemplateService.readVariables`), catching `NullPointerException` (`PolicyService`), unbox/rebox (`PreferenceService`), possible null (`GlobalExceptionHandler`), switches
  without `default` now fail closed (`PolicyService`, `TemplateRenderer`), one-statement-per-line (`ReconciliationService`). Two SpotBugs patterns are excluded with a written reason in
  `backend/config/spotbugs-exclude.xml` (`EI_EXPOSE_REP*` for Spring beans/records, `CT_CONSTRUCTOR_THROW` for two fail-fast singleton constructors).
- Two flaky tests hardened (race with the scheduled poller / attempt row not yet written): `ChannelDispatchIT` poller tests, `FailureRecoveryIT.scenario04`.
- New test `QueryPlanIT` (about 50 s): EXPLAIN ANALYZE of 17 hot queries on 100,000 messages; output in `docs/database/query-plans-executed.md`.
- Port: `scripts/run-local.sh` honours `SERVER_PORT` (default 18080, `application-local.yml` uses `${SERVER_PORT:18080}`) and writes `.run/backend.pid` when `RUN_PID_FILE=1`.

## Frontend (`frontend/messaging-console`)
- Timeline: `HistoryEntry` is the backend `TimelineEntry {at, kind, type, summary, detail}`; actor from `detail.actor`, status chip from `detail.to` / receipt type.
- Personas `platform-admin`, `tenant-admin`, `sender` request scope `messages:bulk`. Platform-admin writes send `X-Tenant-Id` (typed scope, else default `tenant-retail`); reads stay cross-tenant.
- Contract mismatches found by driving the real backend and fixed: form-field labels invisible (Material density -2 hides labels, now -1); template create lacked the required
  `name`; template list used non-existent `status/locales` (now `versionCounts/publishedLocales`); rejection comment field is `rejectionComment`; preferences used a string
  `fallbackOrder` and a `maskedAddress` (backend: channel array, `maskedEmail/maskedPhone`), blank quiet hours (null = default window) made the form unsavable, the save body carried
  unknown fields; message list shows `channelUsed` and `nextAttemptAt`; p95 of 0 ms (idle rolling window) is shown as a dash.
- Dev proxy: `proxy.conf.js` (replaces use of `proxy.conf.json`, which remains as the default-port file) honours `API_PORT`; `scripts/serve-ui.sh <ui-port> <api-port>`;
  `scripts/serve-dist.mjs <ui-port> <api-port>` serves the production build with an API proxy; mock-api honours `API_PORT`/`SERVER_PORT` and now returns the real shapes.
- `ng lint` clean, `ng build --configuration production` OK, `ng test` 212 of 212 pass.

## End-to-end and UI checks
- `tests/end-to-end/e2e.mjs` (zero dependencies): 254 checks, 254 pass, run output in `docs/validation/e2e-run-output.txt`.
- `tests/end-to-end/ui-check.mjs` (zero dependencies, headless Chrome through the DevTools protocol): 16 pages and 4 interactive flows (submit + idempotent replay, preferences edit/save,
  bulk generate, template create as author), 0 console errors / uncaught exceptions / failed API calls; 23 screenshots in `docs/validation/screenshots/`, JSON in `ui-check-report.json`.
  The Claude preview tools could not be used: they read `.claude/launch.json` of the session's own project, not of this repository, so `.claude/launch.json` here is not picked up.

## Measurements
`docs/validation/performance-results.md` (+ `performance-run-output.txt`). `performance/node-load/load-driver.mjs` was corrected to the real API (token body, template variables, per-channel
templates, client pool for the per-client rate limit, fault-mode and snapshot options); `wait-drain.mjs` is new. k6 not executed.

## Observations / open issues
- Handled idempotency-key races are logged by Hibernate at ERROR/WARN (`SqlExceptionHelper`, unique index violation) although the request ends 200/409 correctly; consider a log filter.
- Under sustained submission the API accepts faster than the pipeline dispatches on this machine (170-300 req/s vs about 120-200 msg/s) and answers 503 at 5,000 pending (by design).
- List/audit queries sort by `(timestamp DESC, id)`; on H2 they read the whole tenant/status partition (see query-plans doc); PostgreSQL behaviour is unverified.
- Provider p95 and dispatch-delay p95 come from a rolling (about 2 minute) Micrometer window and read 0 when idle.
- Not executed anywhere: Docker/compose/K8s/Helm/CF, k6, Testcontainers (`-Pcontainers`), real PostgreSQL/RabbitMQ/Redis/Mongo, `-Psecurity-scan`.
