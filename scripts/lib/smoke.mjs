#!/usr/bin/env node
// Smoke test against a running backend. Usage: node scripts/lib/smoke.mjs [baseUrl]   (default http://localhost:18080)
// Requires profile local (dev token). Exit 0 only if every check passes. Token response/request shape is assumed (see CONTRACT 1).
const base = process.argv[2] || process.env.BASE_URL || 'http://localhost:18080';
const template = process.env.TEMPLATE_CODE || 'TRADE_CONFIRMATION';
let failed = 0;
const ok = (name, cond, extra = '') => { console.log(`${cond ? 'PASS' : 'FAIL'} ${name}${extra ? ` (${extra})` : ''}`); if (!cond) failed++; };

async function req(method, path, { token, headers, body } = {}) {
  const res = await fetch(base + path, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(headers || {}) }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15000) });
  const text = await res.text(); let json = null; try { json = JSON.parse(text); } catch { /* not json */ }
  return { status: res.status, json, headers: res.headers };
}
async function tokenFor(roles, scope, tenant = 'tenant-retail') {
  const r = await req('POST', '/dev/token', { body: { subject: `smoke-${roles[0].toLowerCase()}`, roles, tenant, scopes: scope ? [scope] : [] } });
  const t = r.json && (r.json.token || r.json.accessToken || r.json.access_token);
  return { status: r.status, token: t };
}

try {
  const live = await req('GET', '/actuator/health/liveness'); ok('liveness 200', live.status === 200, `HTTP ${live.status}`);
  const ready = await req('GET', '/actuator/health/readiness'); ok('readiness 200', ready.status === 200, `HTTP ${ready.status}`);
  const anon = await req('GET', '/api/v1/me'); ok('unauthenticated /api/v1/me is 401', anon.status === 401, `HTTP ${anon.status}`);
  const s = await tokenFor(['SENDER'], 'messages:write'); ok('dev token (sender)', s.status === 200 && !!s.token, `HTTP ${s.status}`);
  const o = await tokenFor(['OPERATOR'], 'messages:read'); ok('dev token (operator)', o.status === 200 && !!o.token);
  if (s.token) {
    const me = await req('GET', '/api/v1/me', { token: s.token }); ok('/api/v1/me with token', me.status === 200, `HTTP ${me.status}`);
    const key = `smoke-${Date.now()}`;
    const payload = { clientReference: key, recipientId: 'rcp-001', channel: 'EMAIL', category: 'TRANSACTIONAL', priority: 'NORMAL', template: { code: template, locale: 'en-US', version: null }, variables: { accountLast4: '1234', quantity: '10', symbol: 'ACME', price: '250.00' } };
    const sub = await req('POST', '/api/v1/messages', { token: s.token, headers: { 'Idempotency-Key': key }, body: payload });
    ok('submit message -> 202', sub.status === 202, `HTTP ${sub.status}`);
    const id = sub.json && sub.json.id;
    const replay = await req('POST', '/api/v1/messages', { token: s.token, headers: { 'Idempotency-Key': key }, body: payload });
    ok('idempotent replay -> 200 + Idempotent-Replay', replay.status === 200 && replay.headers.get('idempotent-replay') === 'true', `HTTP ${replay.status}`);
    const noKey = await req('POST', '/api/v1/messages', { token: s.token, body: payload }); ok('missing Idempotency-Key rejected (4xx)', noKey.status >= 400 && noKey.status < 500, `HTTP ${noKey.status}`);
    if (id && o.token) {
      let status = null;
      for (let i = 0; i < 30; i++) { const st = await req('GET', `/api/v1/messages/${id}/status`, { token: o.token }); status = st.json && st.json.status; if (status && !['VALIDATED', 'QUEUED', 'PROCESSING'].includes(status)) break; await new Promise((r) => setTimeout(r, 1000)); }
      ok('message progressed past QUEUED/PROCESSING within 30s', status && !['VALIDATED', 'QUEUED', 'PROCESSING'].includes(status), `status=${status}`);
      const sum = await req('GET', '/api/v1/ops/summary', { token: o.token }); ok('/ops/summary 200', sum.status === 200, `HTTP ${sum.status}`);
    }
  }
  const prom = await req('GET', '/actuator/prometheus'); ok('/actuator/prometheus exposes messaging_ metrics', prom.status === 200, `HTTP ${prom.status}`);
} catch (e) { console.log(`FAIL smoke aborted: ${e.message} (is the backend running on ${base}?)`); failed++; }
console.log(failed ? `SMOKE FAILED: ${failed} check(s)` : 'SMOKE OK');
process.exit(failed ? 1 : 0);
