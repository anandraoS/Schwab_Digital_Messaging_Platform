#!/usr/bin/env node
// Seeds demo traffic through the public API (synthetic data only). Usage: node scripts/lib/seed-demo.mjs [baseUrl] [count]
// Requires profile local (dev token). Seed recipients/templates come from the backend's local synthetic seed (CONTRACT section 4).
const base = process.argv[2] || process.env.BASE_URL || 'http://localhost:18080';
const count = parseInt(process.argv[3] || '24', 10);
const template = process.env.TEMPLATE_CODE || 'TRADE_CONFIRMATION';
const channels = ['EMAIL', 'SMS'];  // seeded TRADE_CONFIRMATION exists for EMAIL and SMS
const categories = ['TRANSACTIONAL'];  // a template's category must equal the request category
const priorities = ['LOW', 'NORMAL', 'HIGH', 'CRITICAL'];

async function req(method, path, token, body, headers) {
  const res = await fetch(base + path, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(headers || {}) }, body: body === undefined ? undefined : JSON.stringify(body) });
  let json = null; try { json = await res.json(); } catch { /* empty */ }
  return { status: res.status, json };
}
const tok = async (roles, scope) => {
  const r = await req('POST', '/dev/token', null, { subject: 'seed-demo', roles, tenant: 'tenant-retail', scopes: scope.split(' ') });
  const t = r.json && (r.json.token || r.json.accessToken || r.json.access_token);
  if (!t) throw new Error(`dev token failed (HTTP ${r.status}); start the backend with scripts/run-local.sh`);
  return t;
};

const sender = await tok(['SENDER'], 'messages:write messages:bulk');
const tally = {}; const ids = [];
for (let i = 0; i < count; i++) {
  const body = { clientReference: `demo-${Date.now()}-${i}`, recipientId: `rcp-${String((i % 60) + 1).padStart(3, '0')}`, channel: channels[i % channels.length], category: categories[i % categories.length], priority: priorities[i % 4], template: { code: template, locale: 'en-US', version: null }, variables: { accountLast4: '1234', quantity: '10', symbol: 'ACME', price: '250.00' }, metadata: { source: 'seed-demo' } };
  const r = await req('POST', '/api/v1/messages', sender, body, { 'Idempotency-Key': `seed-${Date.now()}-${i}-${Math.random().toString(36).slice(2, 8)}` });
  tally[r.status] = (tally[r.status] || 0) + 1;
  if (r.status === 202 && r.json && r.json.id) ids.push(r.json.id);
}
console.log(`submitted ${count} messages: HTTP status counts ${JSON.stringify(tally)}`);
console.log(`first ids: ${ids.slice(0, 3).join(', ')}`);
const bulk = await req('POST', '/api/v1/bulk-jobs/generate-synthetic', sender, { rows: 200, duplicateRate: 0.02, invalidRate: 0.02 });
console.log(`synthetic bulk job: HTTP ${bulk.status} id=${bulk.json && bulk.json.id}`);
process.exit(Object.keys(tally).every((k) => k === '202' || k === '429' || k === '422') ? 0 : 1);
