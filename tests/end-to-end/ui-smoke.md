# UI smoke test (optional, Playwright)

The Angular console does not depend on Playwright. Unit tests (Karma/Jasmine) are the verified suite:
`npm test` inside `frontend/messaging-console`. This document describes an optional browser smoke run.
It was **not executed** when the console was built (Playwright is not installed in this repository).

## Prerequisites
- Node 24 and a Playwright install of your choice, for example in a throw-away folder outside the repo:
  `npm init -y` then `npm i -D @playwright/test` then `npx playwright install chromium`.
- Either the real backend (profile `local`, port 18080) or `npm run start:mock` (UI stub, port 18080).
- The console: `npm start` in `frontend/messaging-console` (port 14200).

## Suggested scenarios
1. **Login**: open `http://localhost:14200/login`, click the persona button `[data-persona="operator"]`; expect redirect to `/dashboard` and the toolbar to show `operator-retail`.
2. **Role-aware nav**: as `auditor` the nav shows "Audit history" and not "Admin" / "New message"; as `platform-admin` it shows "Admin".
3. **Message list**: `/messages` shows a table (or the empty state), filter by status, page through results.
4. **Submit**: as `sender`, `/messages/new`, fill recipient id + template code + locale, submit; expect "Accepted (HTTP 202)" with a link to the message. Submit again with the same key; expect "Idempotent replay (HTTP 200)".
5. **Detail + timeline**: open the message; the lifecycle timeline renders; no message body anywhere.
6. **Dead letters** (operator): Replay opens a confirm dialog; cancel leaves the row untouched.
7. **Bulk job**: as `sender`, `/bulk-jobs`, generate a synthetic job; the detail page progress bar advances without manual refresh.

## Example (`ui-smoke.spec.ts`, kept outside the repo or under a local-only folder)

```ts
import { test, expect } from '@playwright/test';

test('operator can sign in and see queues', async ({ page }) => {
  await page.goto('http://localhost:14200/login');
  await page.locator('[data-persona="operator"]').click();
  await expect(page).toHaveURL(/dashboard/);
  await page.getByRole('link', { name: 'Queues' }).click();
  await expect(page.getByText('Dead-letter depth')).toBeVisible();
});
```

Run with `npx playwright test`. Record results in `docs/validation/VALIDATION_REPORT.md` only if actually executed.
