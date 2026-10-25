// Scenario: DB-heavy mix (writes + indexed reads + paginated list) to expose pool saturation / N+1 / missing indexes. NOT executed.
// Watch hikaricp_connections_pending and p95 in Grafana "JVM and DB Pool" while this runs.
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, senderToken, operatorToken, submit, auth, SLO } from './lib/common.js';

export const options = {
  scenarios: {
    writers: { executor: 'constant-arrival-rate', rate: 80, timeUnit: '1s', duration: '5m', preAllocatedVUs: 40, maxVUs: 150, exec: 'write' },
    readers: { executor: 'constant-arrival-rate', rate: 120, timeUnit: '1s', duration: '5m', preAllocatedVUs: 40, maxVUs: 150, exec: 'read' },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:POST /messages}': [`p(95)<${SLO.submitP95}`],
    'http_req_duration{name:GET /messages}': [`p(95)<${SLO.readP95}`],
    'http_req_duration{name:GET /messages/{id}}': ['p(95)<250'],
  },
};

export function setup() { return { sender: senderToken(), operator: operatorToken() }; }

const ids = [];
let n = 0;
export function write(d) {
  n += 1;
  const r = submit(d.sender, n + __VU * 100000);
  check(r, { 'submit 202': (x) => x.status === 202 });
  if (r.status === 202 && ids.length < 500) ids.push(r.json('id'));
}

export function read(d) {
  const pick = Math.random();
  if (pick < 0.5) {
    const statuses = ['QUEUED', 'SENT_TO_PROVIDER', 'DELIVERED', 'FAILED'];
    const s = statuses[Math.floor(Math.random() * statuses.length)];
    const r = http.get(`${BASE_URL}/api/v1/messages?status=${s}&page=0&size=50&sort=createdAt,desc`, { headers: auth(d.operator), tags: { name: 'GET /messages' } });
    check(r, { 'list 200': (x) => x.status === 200 });
  } else if (ids.length > 0) {
    const id = ids[Math.floor(Math.random() * ids.length)];
    const r = http.get(`${BASE_URL}/api/v1/messages/${id}`, { headers: auth(d.operator), tags: { name: 'GET /messages/{id}' } });
    check(r, { 'detail 200 or 404(other tenant)': (x) => x.status === 200 || x.status === 404 });
  }
}
