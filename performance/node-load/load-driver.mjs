#!/usr/bin/env node
// Zero-dependency concurrent load driver (Node 18+ fetch). Runs against a locally started backend (default http://localhost:18080).
//
//   node performance/node-load/load-driver.mjs --scenario submit --concurrency 16 --duration 30
//   node performance/node-load/load-driver.mjs --scenario mixed --requests 2000 --concurrency 32 --out performance/node-load/results/mixed.json
//
// Scenarios: submit (POST /messages) | read (GET list/detail/ops) | mixed (70% submit / 30% read) | idempotent (same key repeatedly: expects replay) | health
// Flags: --base-url --scenario --concurrency --duration(s) --requests --rate(req/s cap, 0=unlimited) --channel EMAIL|SMS|PUSH|mixed
//        --tenant --template --recipients --warmup(s) --out <file.json> --token <jwt> (skip /dev/token) --help
// Token: POST /dev/token {subject, roles, tenant, scopes}; response field accessToken (verified against the real backend).
// The API rate limit is per client (JWT subject, default 50 req/s burst 100), so the driver spreads load over --clients
// distinct sender subjects; one subject would only measure the rate limiter (HTTP 429).
// Fault injection (profile local only): --fault TRANSIENT [--fault-provider simulated-email] [--fault-probability 1] sets the
// simulator fault mode before the run and resets it to NONE afterwards; a snapshot of /ops/summary, /ops/queues and /providers
// is taken after the run (and again after --settle seconds) and written to the --out file.
// This tool measures; it never fabricates numbers. Record results in performance/README.md only from real runs.
import { randomUUID } from 'node:crypto';
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

const args = parseArgs(process.argv.slice(2));
if (args.help) { printHelp(); process.exit(0); }

const cfg = {
  baseUrl: args['base-url'] || process.env.BASE_URL || 'http://localhost:18080',
  scenario: args.scenario || 'submit',
  concurrency: int(args.concurrency, 8),
  duration: num(args.duration, args.requests ? 0 : 20),
  requests: int(args.requests, 0),
  rate: num(args.rate, 0),
  channel: args.channel || 'mixed',
  tenant: args.tenant || 'tenant-retail',
  template: args.template || 'TRADE_CONFIRMATION',
  recipients: int(args.recipients, 40),
  warmup: num(args.warmup, 2),
  out: args.out || null,
  token: args.token || process.env.TOKEN || null,
  clients: int(args.clients, 32),
  fault: args.fault || null,
  faultProvider: args['fault-provider'] || 'simulated-email',
  faultProbability: num(args['fault-probability'], 1),
  settle: num(args.settle, 0),
};
const SCENARIOS = new Set(['submit', 'read', 'mixed', 'idempotent', 'health']);
if (!SCENARIOS.has(cfg.scenario)) { console.error(`unknown scenario "${cfg.scenario}"`); process.exit(2); }

const CHANNELS = ['EMAIL', 'SMS', 'PUSH'];
// LOW is left out on purpose: LOW SMS/PUSH is deferred by quiet hours for recipients whose local time is 21:00-08:00 (correct policy),
// which would leave VALIDATED rows behind and distort drain measurements.
const PRIOS = ['NORMAL', 'NORMAL', 'NORMAL', 'HIGH', 'CRITICAL'];

async function getToken(roles, scopes, tenant, subject) {
  if (cfg.token) return cfg.token;
  const res = await fetch(`${cfg.baseUrl}/dev/token`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ subject: subject || `load-${roles[0].toLowerCase()}`, roles, tenant: tenant || cfg.tenant, scopes }),
  });
  if (!res.ok) throw new Error(`/dev/token -> HTTP ${res.status}. Is the backend running with profile local on ${cfg.baseUrl}?`);
  const j = await res.json();
  const t = j.token || j.accessToken || j.access_token;
  if (!t) throw new Error(`/dev/token response has no token field (keys: ${Object.keys(j).join(',')})`);
  return t;
}

function body(i) {
  const n = String((i % cfg.recipients) + 1).padStart(3, '0');
  const channel = cfg.channel === 'mixed' ? CHANNELS[i % 3] : cfg.channel;
  // seeded templates: TRADE_CONFIRMATION exists for EMAIL and SMS; PUSH uses PRICE_ALERT (a template per channel)
  const push = channel === 'PUSH';
  return JSON.stringify({
    clientReference: `node-${i}`, recipientId: `rcp-${n}`,
    channel,
    category: push ? 'SERVICE' : 'TRANSACTIONAL', priority: PRIOS[i % PRIOS.length],
    template: { code: push ? 'PRICE_ALERT' : cfg.template, locale: 'en', version: null },
    variables: push ? { symbol: 'ACME', change: '+1.2%', price: '250.00' } : { accountLast4: '1234', quantity: '10', symbol: 'ACME', price: '250.00' },
    metadata: { source: 'node-load' },
  });
}

const tokens = {};
const stats = { byOp: {}, status: {}, errors: {}, ids: [] };
function rec(op, ms, status, err) {
  const o = (stats.byOp[op] ||= { lat: [], n: 0 });
  o.n++; if (!err) o.lat.push(ms);
  const key = err ? `ERR:${err}` : String(status);
  stats.status[key] = (stats.status[key] || 0) + 1;
  if (err) stats.errors[err] = (stats.errors[err] || 0) + 1;
}

async function call(op, method, path, headers, payload) {
  const t0 = performance.now();
  try {
    const res = await fetch(cfg.baseUrl + path, { method, headers, body: payload });
    const text = await res.text();
    rec(op, performance.now() - t0, res.status, null);
    return { status: res.status, text, headers: res.headers };
  } catch (e) {
    rec(op, performance.now() - t0, 0, (e.cause && e.cause.code) || e.name || 'fetch_error');
    return null;
  }
}
const H = (tok, extra) => ({ Authorization: `Bearer ${tok}`, 'Content-Type': 'application/json', 'API-Version': '1', ...(extra || {}) });

let seq = 0;
const fixedKey = randomUUID();
async function oneRequest() {
  const i = seq++;
  const sc = cfg.scenario === 'mixed' ? (Math.random() < 0.7 ? 'submit' : 'read') : cfg.scenario;
  if (sc === 'health') return call('GET /actuator/health/readiness', 'GET', '/actuator/health/readiness', {});
  if (sc === 'submit') {
    const r = await call('POST /messages', 'POST', '/api/v1/messages', H(tokens.senders[i % tokens.senders.length], { 'Idempotency-Key': randomUUID() }), body(i));
    if (r && r.status === 202 && stats.ids.length < 1000) { try { stats.ids.push(JSON.parse(r.text).id); } catch { /* ignore */ } }
    return;
  }
  if (sc === 'idempotent') {
    return call('POST /messages (same key)', 'POST', '/api/v1/messages', H(tokens.senders[0], { 'Idempotency-Key': fixedKey }), body(0));
  }
  // read
  const pick = Math.random();
  const op = tokens.operators[i % tokens.operators.length];
  if (pick < 0.4) return call('GET /messages', 'GET', '/api/v1/messages?page=0&size=50', H(op));
  if (pick < 0.7 && stats.ids.length) return call('GET /messages/{id}', 'GET', `/api/v1/messages/${stats.ids[i % stats.ids.length]}`, H(op));
  if (pick < 0.85) return call('GET /ops/summary', 'GET', '/api/v1/ops/summary', H(op));
  return call('GET /ops/queues', 'GET', '/api/v1/ops/queues', H(op));
}

function pct(sorted, p) { if (!sorted.length) return null; const idx = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1); return sorted[Math.max(0, idx)]; }
const f = (x) => (x === null ? 'n/a' : x.toFixed(1));

async function run() {
  console.log(`load-driver: scenario=${cfg.scenario} concurrency=${cfg.concurrency} ${cfg.requests ? `requests=${cfg.requests}` : `duration=${cfg.duration}s`} rate=${cfg.rate || 'unlimited'} target=${cfg.baseUrl}`);
  if (cfg.scenario !== 'health') {
    tokens.senders = [];
    for (let c = 0; c < cfg.clients; c++) tokens.senders.push(await getToken(['SENDER'], [], cfg.tenant, `load-sender-${c}`));
    tokens.operators = [];
    for (let c = 0; c < Math.max(4, Math.ceil(cfg.clients / 4)); c++) tokens.operators.push(await getToken(['OPERATOR'], [], cfg.tenant, `load-operator-${c}`));
    tokens.operator = tokens.operators[0];
    tokens.admin = await getToken(['PLATFORM_ADMIN'], [], '*', 'load-admin');
    if (cfg.fault) {
      const r = await call('PUT fault-mode', 'PUT', `/api/v1/providers/${cfg.faultProvider}/fault-mode`, H(tokens.admin), JSON.stringify({ mode: cfg.fault, probability: cfg.faultProbability }));
      console.log(`fault mode ${cfg.fault} (p=${cfg.faultProbability}) on ${cfg.faultProvider}: HTTP ${r && r.status}`);
      stats.byOp = {}; stats.status = {};
    }
  }

  if (cfg.warmup > 0 && cfg.scenario !== 'health') {
    const end = Date.now() + cfg.warmup * 1000;
    await Promise.all(Array.from({ length: Math.min(4, cfg.concurrency) }, async () => { while (Date.now() < end) await oneRequest(); }));
    stats.byOp = {}; stats.status = {}; stats.errors = {};   // discard warm-up samples (ids retained for reads)
    console.log(`warm-up ${cfg.warmup}s done`);
  }

  const started = performance.now();
  const stopAt = cfg.duration > 0 ? Date.now() + cfg.duration * 1000 : Infinity;
  let issued = 0;
  const gap = cfg.rate > 0 ? (1000 * cfg.concurrency) / cfg.rate : 0;   // per-worker pacing
  const workers = Array.from({ length: cfg.concurrency }, async () => {
    let next = Date.now();
    for (;;) {
      if (Date.now() >= stopAt) return;
      if (cfg.requests && issued >= cfg.requests) return;
      issued++;
      if (gap) { const wait = next - Date.now(); if (wait > 0) await new Promise((r) => setTimeout(r, wait)); next = Math.max(next, Date.now()) + gap; }
      await oneRequest();
    }
  });
  await Promise.all(workers);
  const elapsed = (performance.now() - started) / 1000;

  const snapshot = async () => {
    const get = async (path) => { try { const r = await fetch(cfg.baseUrl + path, { headers: H(tokens.admin) }); return await r.json(); } catch { return null; } };
    const [summaryJson, queues, providers] = await Promise.all([get('/api/v1/ops/summary'), get('/api/v1/ops/queues'), get('/api/v1/providers')]);
    return { at: new Date().toISOString(), byStatus: summaryJson && summaryJson.byStatus, queues, providers: providers && providers.map((p) => ({ providerId: p.providerId, circuitState: p.circuitState, successRate: p.successRate, faultMode: p.faultMode })) };
  };
  let afterRun = null;
  let afterSettle = null;
  if (cfg.scenario !== 'health') {
    afterRun = await snapshot();
    if (cfg.fault) {
      await call('PUT fault-mode', 'PUT', `/api/v1/providers/${cfg.faultProvider}/fault-mode`, H(tokens.admin), JSON.stringify({ mode: 'NONE', probability: 1 }));
      console.log('fault mode reset to NONE');
    }
    if (cfg.settle > 0) {
      console.log(`settling ${cfg.settle}s ...`);
      await new Promise((r) => setTimeout(r, cfg.settle * 1000));
      afterSettle = await snapshot();
    }
  }
  const summary = { config: { ...cfg, token: cfg.token ? '<provided>' : null }, elapsedSeconds: +elapsed.toFixed(2), operations: {}, status: stats.status, errors: stats.errors, afterRun, afterSettle };
  let total = 0, all = [];
  console.log('\noperation                          count    rps     p50ms   p95ms   p99ms   maxms');
  for (const [op, o] of Object.entries(stats.byOp)) {
    const s = [...o.lat].sort((a, b) => a - b); total += o.n; all = all.concat(s);
    const row = { count: o.n, rps: +(o.n / elapsed).toFixed(1), p50: pct(s, 50), p95: pct(s, 95), p99: pct(s, 99), max: s.length ? s[s.length - 1] : null };
    summary.operations[op] = row;
    console.log(`${op.padEnd(32)} ${String(row.count).padStart(7)} ${String(row.rps).padStart(7)} ${f(row.p50).padStart(8)} ${f(row.p95).padStart(7)} ${f(row.p99).padStart(7)} ${f(row.max).padStart(7)}`);
  }
  all.sort((a, b) => a - b);
  summary.overall = { count: total, rps: +(total / elapsed).toFixed(1), p50: pct(all, 50), p95: pct(all, 95), p99: pct(all, 99) };
  console.log(`\noverall: ${total} requests in ${elapsed.toFixed(1)}s = ${summary.overall.rps} rps; p50=${f(summary.overall.p50)}ms p95=${f(summary.overall.p95)}ms p99=${f(summary.overall.p99)}ms`);
  console.log(`status counts: ${JSON.stringify(stats.status)}`);
  if (cfg.out) { mkdirSync(dirname(cfg.out), { recursive: true }); writeFileSync(cfg.out, JSON.stringify(summary, null, 2) + '\n'); console.log(`wrote ${cfg.out}`); }
  if (afterRun) console.log(`after run : ${JSON.stringify(afterRun.byStatus)} queues=${JSON.stringify(afterRun.queues && { inFlight: afterRun.queues.inFlight, retryPending: afterRun.queues.retryPending, dlq: afterRun.queues.dlqDepth })}`);
  if (afterSettle) console.log(`after settle: ${JSON.stringify(afterSettle.byStatus)} queues=${JSON.stringify(afterSettle.queues && { inFlight: afterSettle.queues.inFlight, retryPending: afterSettle.queues.retryPending, dlq: afterSettle.queues.dlqDepth })}`);
  const bad = Object.entries(stats.status).filter(([k]) => k.startsWith('ERR:') || k.startsWith('5')).reduce((a, [, v]) => a + v, 0);
  process.exit(bad > 0 ? 1 : 0);
}

function parseArgs(argv) {
  const o = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (!a.startsWith('--')) continue;
    const k = a.slice(2);
    if (k === 'help') { o.help = true; continue; }
    const v = argv[i + 1];
    if (v === undefined || v.startsWith('--')) { o[k] = true; } else { o[k] = v; i++; }
  }
  return o;
}
function int(v, d) { const n = parseInt(v, 10); return Number.isFinite(n) ? n : d; }
function num(v, d) { const n = parseFloat(v); return Number.isFinite(n) ? n : d; }
function printHelp() {
  console.log(`Usage: node load-driver.mjs [--scenario submit|read|mixed|idempotent|health] [--concurrency N] [--duration S | --requests N]
       [--rate RPS] [--channel EMAIL|SMS|PUSH|mixed] [--base-url URL] [--tenant ID] [--template CODE] [--warmup S] [--out file.json] [--token JWT]
       [--clients N] [--fault MODE --fault-provider ID --fault-probability P --settle S]`);
}

run().catch((e) => { console.error(`load-driver failed: ${e.message}`); process.exit(2); });
