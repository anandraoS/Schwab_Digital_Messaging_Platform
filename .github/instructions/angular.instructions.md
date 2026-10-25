---
applyTo: "frontend/**/*.{ts,html,scss}"
---
# Angular rules

Full standard: `ai-engineering/standards/angular.md`. Routes: CONTRACT section 10.

- Angular 20 standalone components only (no NgModules); lazy routes with `loadComponent`; `ChangeDetectionStrategy.OnPush`.
- Signals for state (`signal`, `computed`, `toSignal`); Reactive Forms (typed) for forms; Angular Material 20.
- HTTP through `HttpClient` interceptors: auth header, correlation id, error normalization (`ApiError.code` to actionable text), loading indicator.
- Route guards are UX only; never base security on client-side role checks; use `/api/v1/me` for display.
- No `innerHTML`, `bypassSecurityTrust*` with API data. Template previews as text or sandboxed.
- Do not store tokens in `localStorage`; no secrets in the bundle; display masked addresses as returned.
- Handle 401, 403, 404, 409 (optimistic lock conflict UX), 429 (`Retry-After`), 503.
- Auto-generate the `Idempotency-Key` per submit attempt and reuse it when retrying the same attempt.
- Server-side pagination with `PageResponse`; cancel polling on destroy (`takeUntilDestroyed`).
- Tests: Karma + Jasmine for services, interceptors, guards, key components; no real timers.
- Dev server port 14200, proxy to `http://localhost:18080`.
