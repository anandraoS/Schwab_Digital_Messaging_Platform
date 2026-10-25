# React console: validation record

App: `frontend-react/messaging-console-react` (React 19.3.1, TypeScript 5.9 strict, Vite 7.3.7, React Router 7.18.4, TanStack Query 5.104.4).
Same backend and same API contract as the Angular console. All numbers below come from runs on 2026-10-19 on the development machine
(Windows 11, Node 24.13.1, npm 11.8.0, Chrome 155, GraalVM JDK 21.0.7 for the backend).

## Commands and results

In `frontend-react/messaging-console-react`:

| Command | Result |
| --- | --- |
| `npm install` (versions then pinned exactly in `package.json`, `package-lock.json` kept; `npm install` afterwards: up to date) | 320 packages |
| `npm run lint` (eslint 9 + typescript-eslint, `dangerouslySetInnerHTML` banned by rule) | 0 errors, 0 warnings |
| `npm run typecheck` (`tsc --noEmit`, strict, includes the tests) | 0 errors |
| `npm run build` (typecheck + `vite build`) | OK in about 4 s, 135 modules |
| `npm test -- --run` | **10 files, 202 tests, 202 passed**, 0 failed, about 25-37 s, no React/act warnings on stderr |

Build output (`dist/assets`): 36 files, 404 KB raw / 137.5 KB gzip in total (JS 395 KB raw). Entry chunk `index-*.js` 309.2 KB raw / 98.2 KB gzip
(React, router, TanStack Query, shell, auth, API client); CSS 9.1 KB / 2.8 KB gzip; every page is its own lazy chunk (largest:
`MessageSubmitPage` 10.2 KB, `TemplateDetailPage` 6.7 KB, `MessageDetailPage` 6.6 KB, `PreferencesPage` 6.2 KB).

Tests per file: `components.test.tsx` 25 (status chip, masked value, empty/error states, data table, pager, timeline, confirm dialog, charts, formatters),
`app.test.tsx` 19 (login page, route guards, shell/nav per role, tenant scope, sign out, 401 handling, loading bar, dashboard),
`session.test.ts` 16 (personas, token response variants, role normalization, JWT decode, restore/expiry, nav matrix),
`validators.test.ts` 19, `client.test.ts` 22 (correlation id, bearer, `X-Tenant-Id` rules, 401, network error, loading counter, quiet polling,
idempotency replay, multipart upload, query/path encoding), `errorMapper.test.ts` 12 (all 21 contract codes, details, Retry-After, conflict),
`messages.test.tsx` 23 (list filters/pagination, submit builder + validation + form + idempotent replay, detail + cancel/requeue),
`templates.test.tsx` 21 (role/four-eyes gating, submit/approve/reject/retire, edit with optimistic version, create with `name`, preview),
`preferences.test.tsx` 13 (null quiet hours, channel-array fallback order, version handling, conflict: reload+re-apply and reload+discard, read-only role),
`ops.test.tsx` 32 (queues polling start/stop/unmount, bulk list/detail polling/stop/restart/upload checks, dead letters replay/discard confirm,
failed, providers + fault injection, admin, delivery events, audit, no message body rendered).
The API calls in the tests go through the real fetch client against MSW handlers (no mocking of the client).

## Live verification against the real backend

```bash
cd <root>
SERVER_PORT=18480 RUN_PID_FILE=1 bash scripts/run-local.sh       # packaged jar, profile local (H2); started in the background, healthy after about 40 s
bash scripts/seed-demo.sh http://127.0.0.1:18480 30                # 30 messages (HTTP 202 x 30) + 1 synthetic bulk job
bash scripts/serve-react.sh 15200 18480                            # production build served by `vite preview`, proxy -> 18480
node tests/end-to-end/react-ui-check.mjs http://127.0.0.1:15200 docs/validation/react-screenshots
bash scripts/stop-local.sh                                         # "Stopped."
```

(Port 18080 was not used; 18480 was free. The backend and the preview server were stopped afterwards.)

`tests/end-to-end/react-ui-check.mjs` (zero dependencies, headless Chrome over the DevTools protocol, modelled on `ui-check.mjs`) result:
**45 steps, 0 with problems, 0 console errors / uncaught exceptions, 0 failed API calls (any 4xx/5xx on `/api`, `/dev`, `/actuator`), 0 error banners, 33 screenshots**.
Full output: `docs/validation/react-screenshots/react-ui-check-output.txt`, machine-readable: `react-ui-check-report.json` (same folder).

What the 45 steps cover (all against the real backend):
- login page offers the 7 personas; sign in as platform admin through the UI (`POST /dev/token`);
- 16 pages visited as platform admin: dashboard, messages, new message, message detail (lifecycle timeline rendered), templates, template detail (locale tabs),
  queues, providers, bulk jobs, dead letters, failed, audit, delivery events, preferences, admin;
- queues page polling: 3 `/ops/queues` requests in about 11.5 s (initial + 2 polls at 5 s);
- submit a message (`rcp-031`, `TRADE_CONFIRMATION`, 4 variables): `Accepted (HTTP 202)`, then the same idempotency key again: `Idempotent replay (HTTP 200)`;
- preferences of `CUST-040`: search, masked address shown, edit fallback order `SMS,EMAIL`, save (`Saved.`), second save with the returned version (no false conflict);
- bulk job: generate 60 synthetic rows, navigate to the job, progress polling until `COMPLETED`;
- platform-admin fault injection (TRANSIENT with probability 0, then NONE; harmless) accepted by the backend (needs `X-Tenant-Id` on the write, sent by the client);
- template lifecycle: as template author create a template (with the required `name`) and submit it for approval; as template approver approve it (confirm dialog) and see it `PUBLISHED`
  (four-eyes between two different users, enforced by the backend);
- personas sender, operator, auditor, tenant admin: their pages load without errors; auditor opening `/queues` is shown the "Not permitted" page (UX guard);
- mobile viewport (390 px) message list and the opened navigation menu.

Screenshots (`docs/validation/react-screenshots/`): `01-login` ... `33-mobile-menu` (33 PNG).

## Bugs found and fixed while building and verifying

- Login persona buttons carried `role="listitem"`, which replaces the button role for assistive technology (copied from the Angular page); now a `ul` of `li` + `button`.
- Variable key/value inputs of the submit form all had the same accessible name ("Key"/"Value"); they now carry numbered `aria-label`s.
- Tenant-scope input placeholder was clipped in the header; the input was widened.
- Test infrastructure: jsdom's `FormData` is not understood by Node's `fetch`, so the multipart upload test inspects the body handed to `fetch`; lazy route chunks needed a longer first-use timeout under load (`testTimeout` 20 s, `asyncUtilTimeout` 5 s). No application bug was found by the live run: it passed on the first complete execution (the very first attempt aborted only because the freshly started backend had no messages yet; the demo data was seeded and the run repeated).

## Not run / limits

- No Lighthouse/axe audit, no Safari or Firefox, no screen-reader session; accessibility is limited to labels, roles, focus handling in the confirm dialog, skip link, and keyboard-reachable controls checked in tests.
- `npm ci` from the lockfile was not run (only `npm install`, which reported "up to date" after pinning); `npm audit` not run.
- The live check used the `local` profile (H2, in-memory bus); not exercised against PostgreSQL/Oracle, RabbitMQ or other infrastructure profiles.
- Upload of a real CSV file through the browser (file chooser) was not driven in Chrome; the multipart request body is only covered by a unit test (against a stubbed `fetch`), and the UI file-validation messages by component tests; the backend's acceptance of the React upload request was therefore not verified live. Dead-letter replay/discard, stop/restart of a running bulk job and the optimistic-lock conflict panel were tested with MSW only (the live backend had no dead letters, and the check did not race two editors).
- The page-level polling intervals in tests are shortened through props (`refreshMs`, `pollMs`); the live run used the real 5 s interval for queues, but bulk-job polling (2 s) was observed only through the job reaching `COMPLETED`.
- Dev server (`npm run dev`) was not started in this run; the verified server is `vite preview` over the production build (same proxy configuration).
- A leftover log file `.work-react-backend.log` (backend console output of the live run) sits in `<root>` because the sandbox refused to delete it; it can be removed.
