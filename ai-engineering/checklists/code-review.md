# Code Review Checklist

Use with `docs/leadership/pr-checklist.md`. Tick or write N/A with reason.

## Correctness and state
- [ ] Status changes go through `MessageStateMachine` and CAS; no direct status setters.
- [ ] New transition? ADR/CONTRACT/table/tests/UI badge updated.
- [ ] Provider outcome mapping unchanged or covered; timeouts never mapped to failure or blind retry for EMAIL/SMS.
- [ ] Callback handling respects rank rule and dedupe.
## Idempotency and concurrency
- [ ] Each write path survives duplicate invocation; unique constraint or CAS backs it.
- [ ] Race analysed: two consumers, sweeper vs callback, operator vs consumer. Test exists.
- [ ] No check-then-act without atomic guard.
## Failure handling
- [ ] Crash after each step leaves a recoverable state; attempt row before provider call.
- [ ] Retries bounded with jitter; no infinite loops; poison path.
- [ ] Timeouts on remote calls; circuit breaker used; no transaction across remote call.
## Resources
- [ ] Bounded pools/queues/collections; resources closed; no unbounded streaming into memory; scheduler tick exception-safe.
## Security and privacy
- [ ] Tenant scoping on every repository access; foreign => 404.
- [ ] Role check and resource-level check; cross-tenant test.
- [ ] No secrets/bodies/addresses in logs, API, bus, Mongo; masked output.
- [ ] Input validated and bounded; no expression evaluation on user input.
## Data/DB
- [ ] Migration expand-only/portable; indexes justified; N+1 checked (statement count test).
## API
- [ ] Backward compatible; OpenAPI updated; ApiError codes from the list.
## Observability
- [ ] Metrics named per CONTRACT; alert/runbook for new failure modes; MDC set.
## Tests
- [ ] Tests assert behavior and invariants; regression test for the bug; concurrency test for race fixes; no sleeps; edge cases (null, empty, max, expired).
## Docs
- [ ] ADR, spec, runbook, config docs updated; not-executed items stated honestly.
## AI-assisted changes
- [ ] Reviewer checked against the invariants list in `context-engineering.md`; generated tests are not tautological; no hallucinated APIs (compiles and runs); sensitive areas independently reviewed.
