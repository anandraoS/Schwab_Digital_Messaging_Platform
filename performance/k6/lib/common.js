// Shared helpers for all k6 scripts. NOT executed (k6 unavailable on the authoring machine).
// Assumptions to verify on first real run (CONTRACT does not fix the exact shape):
//   - POST /dev/token accepts JSON {subject, roles[], tenant, scopes[]} and returns the JWT in one of: token | accessToken | access_token.
//   - Seed data has recipients rcp-001.. and a PUBLISHED template (TEMPLATE_CODE) for each channel.
import http from 'k6/http';
import { check, fail } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:18080';
export const TENANT = __ENV.TENANT || 'tenant-retail';
export const TEMPLATE_CODE = __ENV.TEMPLATE_CODE || 'TRADE_CONFIRMATION';
export const RECIPIENTS = parseInt(__ENV.RECIPIENTS || '60', 10);

// Illustrative SLO-derived thresholds (tune after measuring; see docs/operations/performance-tests.md)
export const SLO = {
  submitP95: 300, submitP99: 800, errorRate: 0.01, readP95: 500,
};

export function getToken(roles, scope, tenant) {
  const res = http.post(`${BASE_URL}/dev/token`, JSON.stringify({
    subject: `k6-${(roles[0] || 'user').toLowerCase()}`, roles, tenant: tenant || TENANT, scopes: (scope || 'messages:write').split(' '),
  }), { headers: { 'Content-Type': 'application/json' }, tags: { name: 'dev-token' } });
  if (res.status !== 200) fail(`dev token failed: HTTP ${res.status} (is the backend running with profile local?)`);
  const body = res.json();
  const token = body.token || body.accessToken || body.access_token;
  if (!token) fail(`dev token response has no token field; keys: ${Object.keys(body).join(',')}`);
  return token;
}

export function senderToken() { return getToken(['SENDER'], 'messages:write messages:bulk'); }
export function operatorToken() { return getToken(['OPERATOR'], 'messages:read'); }
export function adminToken() { return getToken(['PLATFORM_ADMIN'], 'messages:write messages:bulk', '*'); }

export function auth(token, extra) {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', 'API-Version': '1', ...(extra || {}) };
}

const CHANNELS = ['EMAIL', 'SMS'];  // seeded TRADE_CONFIRMATION template exists for EMAIL and SMS
const PRIORITIES = ['LOW', 'NORMAL', 'NORMAL', 'NORMAL', 'HIGH', 'CRITICAL'];

export function submitBody(i, opts) {
  const o = opts || {};
  const n = ((i % RECIPIENTS) + 1).toString().padStart(3, '0');
  return JSON.stringify({
    clientReference: `k6-${__VU}-${i}`,
    recipientId: `rcp-${n}`,
    channel: o.channel || CHANNELS[i % CHANNELS.length],
    category: o.category || 'TRANSACTIONAL',
    priority: o.priority || PRIORITIES[i % PRIORITIES.length],
    template: { code: TEMPLATE_CODE, locale: 'en-US', version: null },
    variables: { accountLast4: '1234', quantity: '10', symbol: 'ACME', price: '250.00' },
    metadata: { source: 'k6' },
  });
}

export function submit(token, i, opts) {
  return http.post(`${BASE_URL}/api/v1/messages`, submitBody(i, opts), {
    headers: auth(token, { 'Idempotency-Key': uuidv4(), 'X-Correlation-Id': `k6-${uuidv4()}` }),
    tags: { name: 'POST /messages' },
  });
}

export function checkAccepted(res) {
  return check(res, {
    'submit 202': (r) => r.status === 202,
    'has id': (r) => { try { return !!r.json('id'); } catch (e) { return false; } },
  });
}

export function queues(token) {
  const r = http.get(`${BASE_URL}/api/v1/ops/queues`, { headers: auth(token), tags: { name: 'GET /ops/queues' } });
  return r.status === 200 ? r.json() : null;
}

export function setFaultMode(token, providerId, mode, probability) {
  return http.put(`${BASE_URL}/api/v1/providers/${providerId}/fault-mode`, JSON.stringify({ mode, probability }),
    { headers: auth(token), tags: { name: 'PUT fault-mode' } });
}

export const baseThresholds = {
  http_req_failed: [`rate<${SLO.errorRate}`],
  'http_req_duration{name:POST /messages}': [`p(95)<${SLO.submitP95}`, `p(99)<${SLO.submitP99}`],
  checks: ['rate>0.99'],
};
