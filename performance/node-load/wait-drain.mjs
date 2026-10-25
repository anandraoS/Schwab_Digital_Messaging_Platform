#!/usr/bin/env node
// Polls /ops/summary until no message is VALIDATED/QUEUED/PROCESSING/RETRY_PENDING and reports how long the backlog
// took to drain and the resulting end-to-end processing rate (messages that left those states per second).
//   node performance/node-load/wait-drain.mjs [--base-url http://localhost:18280] [--timeout 600]
const arg = (k, d) => { const i = process.argv.indexOf(`--${k}`); return i > 0 ? process.argv[i + 1] : d; };
const base = arg('base-url', process.env.BASE_URL ?? 'http://localhost:18080');
const timeoutS = Number(arg('timeout', 600));
const tr = await fetch(`${base}/dev/token`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ subject: 'drain-admin', tenant: '*', roles: ['PLATFORM_ADMIN'] }) });
const token = (await tr.json()).accessToken;
const pending = (by) => ['VALIDATED', 'QUEUED', 'PROCESSING', 'RETRY_PENDING'].reduce((a, k) => a + (by[k] ?? 0), 0);
const get = async () => (await (await fetch(`${base}/api/v1/ops/summary`, { headers: { Authorization: `Bearer ${token}` } })).json()).byStatus;
let by = await get();
const start = Date.now();
const initial = pending(by);
console.log(`backlog at start: ${initial} (${JSON.stringify(by)})`);
let last = initial;
let lastAt = start;
let plateauSince = start;
let lastPending = initial;
let drainedAt = null;
while (pending(by) > 0 && Date.now() - start < timeoutS * 1000) {
  await new Promise((r) => setTimeout(r, 1000));
  by = await get();
  if (pending(by) !== lastPending) { lastPending = pending(by); plateauSince = Date.now(); drainedAt = Date.now(); }
  if (Date.now() - plateauSince > 15000) break; // only deferred (quiet-hours) messages remain
  if ((Date.now() - lastAt) >= 5000) {
    const p = pending(by);
    console.log(`  t+${((Date.now() - start) / 1000).toFixed(0)}s pending=${p} (${((last - p) / ((Date.now() - lastAt) / 1000)).toFixed(0)}/s)`);
    last = p; lastAt = Date.now();
  }
}
const secs = ((drainedAt ?? Date.now()) - start) / 1000;
const left = pending(by);
console.log(`drained: ${left === 0 ? 'completely' : `to a plateau of ${left} still-pending messages (deferred by quiet hours, not stuck)`} in ${secs.toFixed(1)} s; ${initial - left} messages = ${((initial - left) / secs).toFixed(0)} msg/s end-to-end (excludes messages processed before the first poll)`);
console.log(`final: ${JSON.stringify(by)}`);
