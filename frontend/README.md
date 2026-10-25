# Messaging Operations Console (Angular 20)

Operations UI for the omnichannel messaging platform (CONTRACT section 10). All data is synthetic; no real provider is called.

```
frontend/
  messaging-console/   Angular 20 app (standalone components, Signals, Reactive Forms, Angular Material 20, lazy routes)
  mock-api/server.mjs  OPT-IN UI development stub (plain Node ESM, zero dependencies, port 18080)
```

## Commands (run inside `frontend/messaging-console`)

| Command | What it does |
|---|---|
| `npm ci` (or `npm install`) | Install pinned dependencies (exact versions, lockfile committed) |
| `npm start` | Dev server on **http://localhost:14200**, proxies `/api`, `/dev`, `/actuator` to `http://localhost:18080` (`proxy.conf.json`) |
| `npm run start:mock` | Starts the mock API stub on 18080 (use **instead of** the Spring Boot backend, never together) |
| `npm run lint` | `ng lint` (angular-eslint, incl. template accessibility rules) |
| `npm run build` | `ng build --configuration production` -> `dist/messaging-console` |
| `npm test` | `ng test --watch=false --browsers=ChromeHeadlessNoSandbox` (Karma + Jasmine) |
| `npm run test:watch` | Interactive Karma |

Karma uses the custom `ChromeHeadlessNoSandbox` launcher (`karma.conf.js`). If `CHROME_BIN` is unset it falls back to the standard Windows/Linux Chrome install paths.

### Typical local session

1. Backend: start Spring Boot with profile `local` on port 18080 (provides `POST /dev/token`), **or** `npm run start:mock`.
2. `npm start`, open http://localhost:14200, pick a persona on the login page.

## Mock API stub (`mock-api/server.mjs`)

A **UI development tool only**. It is **not** an application backend, is not packaged, and has no persistence or security. It serves seeded fake data for the main endpoints (messages incl. detail/history/cancel/requeue, idempotent submit, templates + version workflow + preview, recipients/preferences with optimistic-lock 409, bulk jobs with time-based progress, providers + fault mode, queues, summary, dead letters, failed messages, delivery events, audit). `/dev/token` returns an **unsigned** pseudo-JWT (`alg: none`). State resets on restart. The stub does not enforce roles or tenants; it only returns 401 without a bearer token. Port override: `MOCK_PORT`.

## Structure

```
src/app/
  core/auth      AuthService (dev token, in-memory + sessionStorage session), guards, role matrix + nav (access.ts)
  core/http      interceptors (correlation id, bearer, loading, error normalization), error-mapper (code -> actionable text), LoadingService
  core/api       typed services per area (messages, templates, recipients, bulk-jobs, ops, events)
  core/models    api.models.ts (ApiError, PageResponse<T>, SubmitMessageRequest, MessageDetail, ...)
  core/forms     reusable validators (e-mail, E.164, SMS sender id, push TTL, future date, unique keys, ...)
  core/util      Loadable<T> (signal-based data/loading/error holder, cancels stale requests)
  shared         data-table, pager, status-chip, empty-state, error-banner, confirm-dialog, timeline, masked-value, charts (plain SVG)
  layout         shell (role-aware sidenav, collapses below 960px, skip link, global progress bar)
  features/*     login, dashboard, messages (list/new/detail), templates (list/detail/editor), preferences, bulk-jobs (list/detail),
                 providers, queues, failed, dead-letters, delivery-events, audit, admin   (all lazy-loaded)
```

Interceptor order: correlation id -> auth bearer (+ `X-Tenant-Id` for platform admin when a scope is set) -> global loading indicator -> error normalization (maps `ApiError.code` to guidance; 401 signs out and redirects to login). Background polling (queues, bulk-job progress) is marked `SKIP_LOADING` so it does not flash the progress bar.

## Security notes (read this)

- **Route guards and hidden buttons are UX only.** Roles decoded from the JWT are used to hide controls and routes that would 403. The backend is the sole authority for authorization and tenant isolation; every request is checked there. The code comments in `guards.ts` and `access.ts` say the same.
- The dev JWT is decoded **without verification** for display purposes only.
- Tokens are held in memory and `sessionStorage` (dev only). A production deployment should replace the dev login with the OIDC flow of the chosen IdP.
- Message bodies are never requested or rendered (the API does not provide them). Template source/preview text, audit details and all API strings are rendered with text interpolation; there is no `innerHTML`/`bypassSecurityTrust*` anywhere. Recipient addresses are shown masked; `masked-value` re-masks defensively if an unmasked value ever appears.
- Open redirects are prevented on login (`returnUrl` must be an app-relative path).

## Assumptions about payloads the contract does not detail

CONTRACT section 5 fixes `ApiError`, `PageResponse`, `SubmitMessageRequest`, `MessageAcceptedResponse` and `MessageDetail`. For other responses (ops summary/queues, providers, bulk jobs, template detail/versions, audit/delivery events, history entries, dead letters, failed messages, recipient preferences, preview) the console uses the shapes in `src/app/core/models/api.models.ts` (documented in `docs/design/DEVIATIONS.md`). They must be reconciled with the committed OpenAPI spec; each is isolated in one model + one service. The dev token request sends roles **without** the `ROLE_` prefix (confirmed against the backend `DevTokenController`) and, for the sender persona, scope `messages:bulk`; the client accepts snake_case or camelCase token response names.

## Verification status

Last run (Windows 11, Node 24, Chrome 155 headless): `npm ci` ok; `npm run lint` clean; `npm run build` ok (initial bundle 341 kB raw / 95 kB transferred, all pages lazy chunks); `npm test` = **204 of 204 specs passed**, no failures, no "no expectations" warnings.

### Verified (executed on this machine)
- `npm install`, `npx ng lint`, `npx ng build --configuration production`, `npx ng test --watch=false --browsers=ChromeHeadlessNoSandbox` all pass (numbers in the delivery report / below).
- `ng serve` on 14200 starts; the dev proxy forwards `/dev/token` and `/api` to the mock stub on 18080; headless Chrome renders the login page with all seven personas.
- The mock stub was exercised with Node `fetch` for the main GET endpoints.

### NOT verified
- Against the **real Spring Boot backend** (it was being written concurrently). Payload shapes outside the contract section are assumptions.
- Playwright / end-to-end browser flows (see `tests/end-to-end/ui-smoke.md`; optional, not run).
- Real accessibility audit (axe/Lighthouse) and screen-reader testing; only basics were applied (labels, roles, aria, focus ring, skip link, text alternatives for charts and status colours).
- Cross-browser testing (Chrome headless only), visual regression, performance budgets beyond the production build succeeding.
- Serving the production build from Spring Boot static resources (optional script not provided).
