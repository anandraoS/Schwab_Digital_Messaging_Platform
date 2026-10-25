# Pull Request Checklist

The PR template (`.github/pull_request_template.md`) embeds this list. Reviewer and author both tick.

## Author
- [ ] Linked spec/issue; scope matches; PR small enough to review (< ~400 changed lines or split).
- [ ] ADR added/updated if a cross-cutting decision changed; no silent ADR override.
- [ ] Tests: unit + integration for new behavior; regression test for each bug fix; no `@Disabled` without ticket.
- [ ] State machine: any status change goes through `MessageStateMachine` and is covered by the transition matrix test.
- [ ] Idempotency considered for every new write path (API, consumer, callback, provider).
- [ ] Tenant isolation: every new repository read/write takes tenant; cross-tenant test added.
- [ ] Authorization: role + resource-level check; endpoint added to the role matrix test.
- [ ] No transaction held across provider/remote calls; no unbounded queues/threads/collections.
- [ ] Timeouts on every remote call; resources closed (try-with-resources).
- [ ] Logging: no secrets, bodies, full addresses; MDC fields set; levels sane.
- [ ] Metrics: names per CONTRACT section 8 or documented additions.
- [ ] DB: migration is expand-only, portable (H2+PG), indexed for new queries, N+1 checked.
- [ ] API: backward compatible; OpenAPI updated; error codes from the list.
- [ ] Docs/runbooks updated; config keys documented with defaults.
- [ ] Not-executed items called out honestly in the PR description.
## Reviewer
- [ ] Understand the intent; read tests first.
- [ ] Walk through failure paths: crash after each step, duplicate delivery, concurrent actors.
- [ ] Verify invariants list (`ai-engineering/context-engineering.md`).
- [ ] Security-sensitive? Request independent security review (CODEOWNERS).
- [ ] Performance: queries per request, pool use, allocation in hot paths.
- [ ] Naming/readability; no dead code; no speculative abstraction.
- [ ] Approve only when all above are satisfied; leave actionable comments classified blocking vs nit.

See also `ai-engineering/checklists/code-review.md` and worked cases in `code-review-scenarios.md`.
