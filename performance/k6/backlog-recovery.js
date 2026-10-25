// Scenario: pause consumers, build a backlog, resume, measure drain time. Needs PLATFORM_ADMIN (dev token). NOT executed.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';
import { BASE_URL, adminToken, senderToken, submit, auth, queues } from './lib/common.js';

const BACKLOG = parseInt(__ENV.BACKLOG || '5000', 10);
const drainSeconds = new Trend('mp_backlog_drain_seconds');
const peakDepth = new Trend('mp_backlog_peak_depth');

export const options = {
  scenarios: { recovery: { executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: '30m' } },
  thresholds: { mp_backlog_drain_seconds: ['p(95)<600'], checks: ['rate>0.99'] },
};

function total(q) {
  if (!q) return -1;
  return (q.depthByPriority ? Object.values(q.depthByPriority).reduce((a, b) => a + b, 0) : q.depth) || 0;
}

export default function () {
  const admin = adminToken(); const sender = senderToken();
  check(http.post(`${BASE_URL}/api/v1/ops/consumers/pause`, null, { headers: auth(admin) }), { 'paused': (r) => r.status < 300 });
  let accepted = 0;
  for (let i = 0; i < BACKLOG; i++) { if (submit(sender, i).status === 202) accepted++; }
  sleep(3);                                          // allow the outbox publisher to enqueue
  peakDepth.add(Math.max(0, total(queues(admin))));
  const t0 = Date.now();
  check(http.post(`${BASE_URL}/api/v1/ops/consumers/resume`, null, { headers: auth(admin) }), { 'resumed': (r) => r.status < 300 });
  let d = total(queues(admin));
  for (let i = 0; i < 1200 && d > 0; i++) { sleep(1); d = total(queues(admin)); }
  drainSeconds.add((Date.now() - t0) / 1000);
  check(d, { 'queue drained to 0': (x) => x === 0 });
  console.log(`accepted=${accepted} drain_seconds=${(Date.now() - t0) / 1000}`);
}
