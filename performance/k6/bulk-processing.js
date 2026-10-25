// Scenario: bulk job via synthetic generator endpoint; measure end-to-end job duration and throughput. NOT executed.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';
import { BASE_URL, senderToken, auth } from './lib/common.js';

const ROWS = parseInt(__ENV.ROWS || '20000', 10);
const jobDuration = new Trend('mp_bulk_job_seconds');
const jobThroughput = new Trend('mp_bulk_rows_per_second');
const TERMINAL = ['COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'STOPPED', 'ABANDONED'];

export const options = {
  scenarios: { one_job: { executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: '30m' } },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    mp_bulk_rows_per_second: ['p(50)>100'],   // illustrative: >= 100 rows/s
    checks: ['rate>0.99'],
  },
};

export default function () {
  const token = senderToken();
  const t0 = Date.now();
  const res = http.post(`${BASE_URL}/api/v1/bulk-jobs/generate-synthetic`, JSON.stringify({ rows: ROWS, duplicateRate: 0.02, invalidRate: 0.02 }),
    { headers: auth(token), tags: { name: 'POST generate-synthetic' } });
  if (!check(res, { 'job accepted 202': (r) => r.status === 202 })) return;
  const id = res.json('id');
  let status = 'CREATED';
  for (let i = 0; i < 900 && !TERMINAL.includes(status); i++) {
    sleep(2);
    const g = http.get(`${BASE_URL}/api/v1/bulk-jobs/${id}`, { headers: auth(token), tags: { name: 'GET bulk-job' } });
    if (g.status === 200) status = g.json('status');
  }
  const secs = (Date.now() - t0) / 1000;
  jobDuration.add(secs); jobThroughput.add(ROWS / secs);
  check(status, { 'job reached a terminal state': (s) => TERMINAL.includes(s), 'job not FAILED': (s) => s !== 'FAILED' });
}
