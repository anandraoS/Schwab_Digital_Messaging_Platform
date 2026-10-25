# Messaging Operations Console (React)

React operations console for the Schwab digital messaging platform. It is the **same console, same backend and same API contract as the
Angular one** in `../../frontend/messaging-console` (port 14200); this app runs on port **15200**. The Angular app is the reference and is not
modified by this one.

Stack (exact versions pinned in `package.json`, lockfile committed): React 19.3.1, TypeScript 5.9 (strict), Vite 7.3, React Router 7.18
(lazy routes via `React.lazy`), TanStack Query 5.104 (server state, polling), Vitest 3.2 + Testing Library + MSW 2.15, ESLint 9 + typescript-eslint.
No UI framework, no web fonts, no CDN assets: the build works offline. No `dangerouslySetInnerHTML` (enforced by an ESLint rule), and message
bodies are never requested, sent or displayed.

## Commands

All commands run in `frontend-react/messaging-console-react`.

```bash
npm install                 # first time (npm ci for a clean, lockfile-exact install)
npm run dev                 # dev server http://127.0.0.1:15200 (API proxy -> http://127.0.0.1:${API_PORT:-18080})
API_PORT=18480 npm run dev  # backend on another port
npm run lint                # eslint + typescript-eslint
npm run typecheck           # tsc --noEmit (strict)
npm run build               # typecheck + production build into dist/
npm run preview             # serves dist/ on 15200 with the same API proxy
npm test -- --run           # vitest, one run (use `npm test` for watch mode)
```

From the repository root, with a backend running:

```bash
SERVER_PORT=18480 RUN_PID_FILE=1 bash scripts/run-local.sh &        # backend (jar: backend/target/messaging-platform.jar)
bash scripts/seed-demo.sh http://127.0.0.1:18480 30                  # optional demo data
bash scripts/serve-react.sh 15200 18480                              # builds if needed, serves dist/ (MODE=dev for the dev server)
node tests/end-to-end/react-ui-check.mjs http://127.0.0.1:15200 docs/validation/react-screenshots   # headless-Chrome check
bash scripts/stop-local.sh
```

`/api`, `/dev` and `/actuator` are proxied to the backend by `vite.config.ts` (dev server and `vite preview`), so the browser only talks to
its own origin and no CORS configuration is involved. Sign in with one of the seven dev personas (`POST /dev/token`, local/test profiles only).

## Same backend, same contract as the Angular console

| Concern | Angular console | React console (this app) |
| --- | --- | --- |
| Backend / API | `/api/v1/**`, `/dev/token`, `/actuator/**` | identical, same paths and payloads |
| Dev port / proxy | 14200, `proxy.conf.js` reads `API_PORT` | 15200, `vite.config.ts` reads `API_PORT` (default 18080) |
| Models | `core/models/api.models.ts` | `src/core/models.ts` (copied, same shapes) |
| Real-backend fixes | `TimelineEntry` (actor in `detail.actor`, status in `detail.to`), `maskedEmail`/`maskedPhone`, `fallbackOrder` as channel array, template create needs `name`, `rejectionComment`, `versionCounts`/`publishedLocales`, null quiet hours = default window | same, see `Timeline.tsx`, `PreferencesPage.tsx`, `TemplateEditor.tsx`, `TemplateListPage.tsx` |
| Auth | 7 personas, token field `access_token`/`accessToken`, JWT claims for roles, session in `sessionStorage`, scopes (`messages:bulk`) | same list and request bodies, plus the bare `token` field; `src/core/auth/session.ts` |
| Headers | `Authorization: Bearer`, `X-Correlation-Id`, `Idempotency-Key`; `X-Tenant-Id` for platform admins (typed scope, else `tenant-retail` on writes, none on reads) | same, in `src/core/http/client.ts` |
| Errors | `ERROR_MESSAGES` table + status fallbacks, `Retry-After`, correlation id, field details, optimistic-lock flag | same table, `src/core/http/errorMapper.ts` (`NormalizedError`) |
| 401 | clear session, redirect to `/login?reason=expired` | clear session, route guard redirects to `/login?reason=expired` |
| Loading indicator | interceptor counter, polling requests are quiet | counter in `src/core/http/loading.ts`, polling requests are quiet |
| Role matrix | `core/auth/access.ts` | `src/core/auth/access.ts` (UX only, the backend enforces) |
| Validators | `core/forms/validators.ts` | `src/core/forms/validators.ts` (plain functions, same regexes and limits) |
| Routes | `/login /dashboard /messages /messages/new /messages/:id /templates /templates/:id /preferences /bulk-jobs /bulk-jobs/:id /providers /queues /failed /dead-letters /delivery-events /audit /admin` | identical routes (`src/app/routes.tsx`) plus `/forbidden` and a not-found page |
| Polling | queues every 5 s, bulk job detail every 2 s until terminal | same intervals (`refetchInterval`) |
| Destructive actions | confirm dialog (cancel, requeue, replay, discard, stop, approve, retire, pause) | same set, `ConfirmDialog.tsx` |

Guards and hidden navigation entries are a **UX convenience only**; the backend enforces roles, tenant isolation and the four-eyes rule on every request.

## Layout

```
src/
  main.tsx, index.css
  app/          routes.tsx (lazy routes), guards.tsx, Shell.tsx, queryClient.ts
  core/         models.ts, api/*.ts (one module per backend area), http/ (client, errorMapper, loading), auth/ (session, access), forms/validators.ts
  components/   StatusChip, DataTable, Pager, EmptyState, ErrorBanner, MaskedValue, Timeline, ConfirmDialog, Charts, Fields, hooks
  features/     login, dashboard, messages (list/submit/detail), templates (list/detail/editor), preferences, bulk-jobs, providers,
                queues, failed, dead-letters, delivery-events, audit, admin, misc
  test/         MSW server, render helpers
```

## Tests

`npm test -- --run`: Vitest (jsdom) + Testing Library + MSW. Covered: API client (headers, tenant scope, 401, loading, multipart, idempotency
replay), error mapper, session/auth, role matrix and guards, validators, shared components (status chip, data table, pager, empty/error states,
timeline, confirm dialog, charts) and the pages (login, dashboard, message list/submit/detail, template list/detail workflow with four-eyes,
preferences incl. optimistic-lock conflict, queues polling, bulk jobs, dead letters, failed, providers/admin, delivery events, audit).
See `docs/validation/react-frontend.md` for the recorded results.
