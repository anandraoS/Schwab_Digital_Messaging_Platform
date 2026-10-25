# Angular Standards

Angular 20, standalone components, Signals, Angular Material 20. Applies to `frontend/messaging-console/**`. Routes and pages: CONTRACT section 10.

## Architecture
- Standalone components only (no NgModules); lazy-loaded routes via `loadComponent`/`loadChildren`.
- Feature folders: `features/<name>/{pages,components,services,models}`; shared UI in `shared/`; core singletons (auth, interceptors, API client) in `core/`.
- State: Signals (`signal`, `computed`, `effect` sparingly) and `toSignal` for HTTP; services hold state per feature; no global store unless justified.
- Reactive Forms for all forms (typed `FormGroup`); per-channel validators; idempotency key auto-generated per submit attempt and reused across retry of the same attempt.
- `ChangeDetectionStrategy.OnPush` on components.

## HTTP and errors
- `HttpClient` with interceptors: auth header, correlation id, error normalization (maps `ApiError.code` to actionable text), loading indicator.
- Never parse or trust role from JWT for security decisions; use `/api/v1/me` for UX only; guards are UX only (server enforces).
- Handle 401 (re-login), 403 (message), 404, 409 (conflict UX: reload and show diff for preferences), 429 (respect `Retry-After`), 503.

## Security
- No `innerHTML`/`bypassSecurityTrust*` with API data; render template previews as text or sanitized preview in a sandboxed iframe.
- Store the dev token in memory + sessionStorage only (local dev), never localStorage; no secrets in the bundle.
- Display masked addresses as returned; never reconstruct.

## UX and accessibility
- Material components, keyboard operable, labels, aria for status chips; do not rely on color alone for status; table pagination server-side (`PageResponse`).
- Polling (bulk job progress) with backoff and cancellation on destroy (`takeUntilDestroyed`).

## Testing
Karma + Jasmine: services (HttpTestingController), interceptors, guards, key components (harnesses). No sleeping; fakeAsync where needed.

## Lint and build
Angular CLI defaults, strict TypeScript (`strict: true`), no `any` without comment; production build must pass; dev proxy `proxy.conf.json` to `http://localhost:18080`, dev server port 14200.
