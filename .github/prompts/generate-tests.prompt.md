---
mode: agent
description: Generate behavior-asserting tests for a class or scenario following project test rules
---
Write tests for `${input:target:class or scenario name}`.

First read the code under test and `ai-engineering/context/testing.md`, `ai-engineering/checklists/test-generation.md`, and for failure scenarios `docs/architecture/reliability-scenarios.md` (use the exact method names).

Requirements:
- Choose the lowest sufficient level (unit, slice, `AbstractIntegrationTest`, or `*ContainerIT` with `disabledWithoutDocker = true`).
- Assert outcomes: status, attempts, audit events, metric counters, provider call counts, DB rows. No tautologies (`assertNotNull`, bare `verify`).
- Include negative cases: validation, 401/403, cross-tenant 404, duplicates, out-of-order, expiry, boundaries.
- Deterministic: fixed/advanceable `Clock`, seeded random, no `Thread.sleep`.
- For race-related behavior add a concurrent test (latch gate, at least 20 threads).
- Synthetic data only; no secrets.
- After writing, mentally mutate the production code (remove the guard) and confirm each test would fail; list the mutations considered.
- Run the tests if you can and report the exact command and result; if you cannot run them, say "not run".
