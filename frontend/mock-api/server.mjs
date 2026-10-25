// Opt-in UI development stub. NOT an application backend and NOT part of the product.
// Zero dependencies (plain Node ESM). Serves synthetic, in-memory data on port 18080 so the Angular
// console can be developed before / without the real Spring Boot API. All data is fake; state resets on restart.
// Run:  node frontend/mock-api/server.mjs   (or `npm run start:mock` inside frontend/messaging-console)
import http from 'node:http';
import { randomUUID } from 'node:crypto';

const PORT = Number(process.env.MOCK_PORT ?? process.env.API_PORT ?? process.env.SERVER_PORT ?? 18080);
const now = () => new Date().toISOString();
const ago = (min) => new Date(Date.now() - min * 60000).toISOString();

// ---- tiny seeded RNG so data is stable across restarts
let seed = 42;
const rnd = () => { seed = (seed * 1664525 + 1013904223) % 4294967296; return seed / 4294967296; };
const pick = (a) => a[Math.floor(rnd() * a.length)];

const CHANNELS = ['EMAIL', 'SMS', 'PUSH'];
const STATUSES = ['DELIVERED', 'DELIVERED', 'DELIVERED', 'SENT_TO_PROVIDER', 'QUEUED', 'RETRY_PENDING', 'FAILED', 'DEAD_LETTERED', 'DELIVERY_UNKNOWN', 'REJECTED', 'CANCELLED'];
const CATEGORIES = ['TRANSACTIONAL', 'SECURITY_ALERT', 'SERVICE', 'MARKETING'];
const PRIORITIES = ['CRITICAL', 'HIGH', 'NORMAL', 'LOW'];
const TERMINAL = ['DELIVERED', 'REJECTED', 'CANCELLED'];

const messages = Array.from({ length: 137 }, (_, i) => {
  const status = pick(STATUSES);
  const channel = pick(CHANNELS);
  return {
    id: `msg-${String(i + 1).padStart(4, '0')}-${randomUUID().slice(0, 8)}`, tenantId: 'tenant-retail', clientReference: `order-${8000 + i}`,
    recipient: { id: `rcp-${String((i % 60) + 1).padStart(3, '0')}`, externalRef: `CUST-${String((i % 60) + 1).padStart(3, '0')}`,
      maskedAddress: channel === 'EMAIL' ? 'r***@example.test' : '+1555***0101' },
    channel, channelUsed: channel, category: pick(CATEGORIES), priority: pick(PRIORITIES), status,
    statusReason: status === 'FAILED' ? 'PROVIDER_PERMANENT_FAILURE' : status === 'REJECTED' ? 'OPTED_OUT' : null,
    terminal: TERMINAL.includes(status), template: { code: pick(['TRADE_CONFIRMATION', 'OTP_CODE', 'STATEMENT_READY']), versionNo: 2, locale: 'en-US' },
    correlationId: randomUUID(), idempotencyKey: randomUUID(), scheduledAt: null, expiresAt: null,
    attemptCount: status === 'QUEUED' ? 0 : 1 + Math.floor(rnd() * 3), maxAttempts: 5, nextAttemptAt: status === 'RETRY_PENDING' ? ago(-3) : null,
    createdAt: ago(i * 7 + 1), updatedAt: ago(i * 7),
    attempts: [{ attemptNo: 1, channel, providerId: `sim-${channel.toLowerCase()}`, outcome: status === 'FAILED' ? 'PERMANENT_FAILURE' : 'ACCEPTED',
      providerMessageId: 'pm-' + randomUUID().slice(0, 8), errorCode: status === 'FAILED' ? 'MAILBOX_UNKNOWN' : null, latencyMs: 40 + Math.floor(rnd() * 200), startedAt: ago(i * 7) }],
    deliveryEvents: [{ providerId: `sim-${channel.toLowerCase()}`, providerEventId: 'ev-' + i, type: 'PROVIDER_ACCEPTED', occurredAt: ago(i * 7), applied: true }],
  };
});
const summaryOf = (m) => ({ id: m.id, clientReference: m.clientReference, channel: m.channel, channelUsed: m.channelUsed, category: m.category,
  priority: m.priority, status: m.status, statusReason: m.statusReason, templateCode: m.template.code, recipientId: m.recipient.id,
  correlationId: m.correlationId, createdAt: m.createdAt, updatedAt: m.updatedAt });

const mkVersion = (no, locale, status, author) => ({ versionNo: no, locale, status, subject: locale === 'en-US' ? 'Your trade {{symbol}} is confirmed' : 'Su operacion {{symbol}} fue confirmada',
  body: 'Hello {{firstName}}, your order for {{symbol}} was executed.', variables: ['firstName', 'symbol'], createdBy: author, approvedBy: status === 'PUBLISHED' ? 'approver-retail' : null,
  publishedAt: status === 'PUBLISHED' ? ago(5000) : null, version: 0 });
const templates = [
  { id: 'tpl-1', code: 'TRADE_CONFIRMATION', channel: 'EMAIL', category: 'TRANSACTIONAL', versions: [mkVersion(1, 'en-US', 'RETIRED', 'author-retail'), mkVersion(2, 'en-US', 'PUBLISHED', 'author-retail'), mkVersion(2, 'es-US', 'PENDING_APPROVAL', 'author-retail'), mkVersion(3, 'en-US', 'DRAFT', 'author-retail')] },
  { id: 'tpl-2', code: 'OTP_CODE', channel: 'SMS', category: 'SECURITY_ALERT', versions: [mkVersion(1, 'en-US', 'PUBLISHED', 'author-retail')] },
  { id: 'tpl-3', code: 'STATEMENT_READY', channel: 'PUSH', category: 'SERVICE', versions: [mkVersion(1, 'en-US', 'PUBLISHED', 'author-retail')] },
];
const tplSummary = (t) => ({ id: t.id, tenantId: 'tenant-retail', code: t.code, name: t.name ?? t.code, channel: t.channel, category: t.category,
  latestVersionNo: Math.max(...t.versions.map((v) => v.versionNo)),
  publishedLocales: [...new Set(t.versions.filter((v) => v.status === 'PUBLISHED').map((v) => v.locale))],
  versionCounts: t.versions.reduce((acc, v) => ({ ...acc, [v.status]: (acc[v.status] ?? 0) + 1 }), {}), updatedAt: ago(30) });

const recipients = Array.from({ length: 60 }, (_, i) => ({ id: `rcp-${String(i + 1).padStart(3, '0')}`, externalRef: `CUST-${String(i + 1).padStart(3, '0')}`,
  displayName: `Recipient ${String(i + 1).padStart(3, '0')}`, maskedEmail: `r***${i + 1}@example.test`, maskedPhone: `+1555***${String(i + 1).padStart(4, '0')}`, locale: i % 5 === 0 ? 'es' : 'en', timeZone: 'America/New_York', deleted: false }));
const prefs = new Map();
const prefsOf = (id) => { if (!prefs.has(id)) prefs.set(id, { recipientId: id, marketingOptIn: false, emailEnabled: true, smsEnabled: true, pushEnabled: true, quietStart: null, quietEnd: null, timeZone: 'America/New_York', fallbackOrder: ['EMAIL', 'SMS', 'PUSH'], suppressedChannels: [], version: 0 }); return prefs.get(id); };

const providers = ['sim-email', 'sim-sms', 'sim-push', 'acme-sms'].map((p, i) => ({ providerId: p, channel: p.includes('email') ? 'EMAIL' : p.includes('sms') ? 'SMS' : 'PUSH',
  enabled: true, circuitState: i === 3 ? 'HALF_OPEN' : 'CLOSED', successRate: 0.99 - i * 0.03, p95LatencyMs: 120 + i * 60, rateLimitPerSec: 50, timeoutMs: 2000, lastError: i === 3 ? 'TIMEOUT after 2000ms' : null, faultMode: 'NONE', faultProbability: 0 }));

const jobs = [];
const mkJob = (name, total, extra = {}) => { const j = { id: 'job-' + randomUUID().slice(0, 8), name, status: 'RUNNING', total, processed: 0, succeeded: 0, failed: 0, duplicate: 0,
  fileName: null, ratePerSecond: 20, startedAt: now(), finishedAt: null, createdBy: 'mock', _t0: Date.now(), ...extra }; jobs.unshift(j); return j; };
mkJob('Demo statement run', 600, { _t0: Date.now() - 20000 });
const advance = (j) => {
  if (j.status !== 'RUNNING') return;
  const done = Math.min(j.total, Math.floor(((Date.now() - j._t0) / 1000) * 40));
  j.processed = done; j.failed = Math.floor(done * 0.03); j.duplicate = Math.floor(done * 0.01); j.succeeded = done - j.failed - j.duplicate;
  if (done >= j.total) { j.status = j.failed ? 'COMPLETED_WITH_ERRORS' : 'COMPLETED'; j.finishedAt = now(); }
};
const jobView = (j) => { advance(j); const { _t0, ...v } = j; return v; };

let consumersPaused = false;
const deadLetters = messages.filter((m) => m.status === 'DEAD_LETTERED').map((m) => ({ messageId: m.id, channel: m.channel, reason: pick(['RETRIES_EXHAUSTED', 'POISON_MESSAGE', 'PERMANENT_FAILURE']), deadLetteredAt: m.updatedAt, attemptCount: m.attemptCount, discarded: false }));
const idem = new Map();

const page = (list, url) => { const p = Number(url.searchParams.get('page') ?? 0), s = Math.min(200, Number(url.searchParams.get('size') ?? 20));
  return { content: list.slice(p * s, p * s + s), page: p, size: s, totalElements: list.length, totalPages: Math.max(1, Math.ceil(list.length / s)), sort: url.searchParams.get('sort') ?? '' }; };
const cid = (req) => req.headers['x-correlation-id'] ?? randomUUID();
const err = (res, req, status, code, message, details = []) => send(res, status, { timestamp: now(), status, error: http.STATUS_CODES[status], code, message, path: req.url, correlationId: cid(req), details }, req);
function send(res, status, body, req, headers = {}) {
  res.writeHead(status, { 'Content-Type': 'application/json', 'X-Correlation-Id': req ? cid(req) : '', 'API-Version': '1', ...headers });
  res.end(body === undefined ? '' : JSON.stringify(body));
}
const readBody = (req) => new Promise((resolve) => { const c = []; req.on('data', (d) => c.push(d)); req.on('end', () => { const raw = Buffer.concat(c).toString(); try { resolve(JSON.parse(raw)); } catch { resolve(raw); } }); });
const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const path = url.pathname, m = req.method;
  let r;
  try {
    if (path === '/actuator/health') return send(res, 200, { status: 'UP', mock: true }, req);
    if (path === '/dev/token' && m === 'POST') {
      const b = await readBody(req);
      if (!b?.subject || !Array.isArray(b?.roles)) return err(res, req, 400, 'MALFORMED_REQUEST', 'subject and roles required');
      // NOT a real signed token: header.payload.mock-signature. UI only decodes it for display.
      const token = `${b64({ alg: 'none', typ: 'JWT' })}.${b64({ sub: b.subject, roles: b.roles, tenant: b.tenant, exp: Math.floor(Date.now() / 1000) + 3600 })}.mock`;
      return send(res, 200, { access_token: token, expires_in: 3600 }, req);
    }
    if (path.startsWith('/api/v1') && !req.headers.authorization?.startsWith('Bearer ')) return err(res, req, 401, 'UNAUTHENTICATED', 'Missing bearer token');
    const p = path.replace('/api/v1', '');
    if (p === '/me') return send(res, 200, { subject: 'mock', roles: [], tenant: 'tenant-retail' }, req);

    if (p === '/messages' && m === 'GET') {
      let list = messages;
      for (const k of ['status', 'channel', 'category', 'priority']) { const v = url.searchParams.get(k); if (v) list = list.filter((x) => x[k] === v); }
      const rid = url.searchParams.get('recipientId'); if (rid) list = list.filter((x) => x.recipient.id === rid);
      const q = url.searchParams.get('q'); if (q) list = list.filter((x) => x.clientReference?.includes(q));
      const c = url.searchParams.get('correlationId'); if (c) list = list.filter((x) => x.correlationId === c);
      return send(res, 200, page(list.map(summaryOf), url), req);
    }
    if (p === '/messages' && m === 'POST') {
      const key = req.headers['idempotency-key']; const b = await readBody(req);
      if (!key) return err(res, req, 400, 'VALIDATION_FAILED', 'Idempotency-Key header is required');
      if (!b?.template?.code) return err(res, req, 400, 'VALIDATION_FAILED', 'Validation failed', [{ field: 'template.code', message: 'must not be blank' }]);
      if (b.template.code === 'MISSING') return err(res, req, 404, 'TEMPLATE_NOT_FOUND', 'Template not found');
      const hash = JSON.stringify(b);
      const prev = idem.get(key);
      if (prev) return prev.hash === hash ? send(res, 200, prev.body, req, { 'Idempotent-Replay': 'true' }) : err(res, req, 422, 'IDEMPOTENCY_KEY_REUSED', 'Key reused');
      const id = 'msg-new-' + randomUUID().slice(0, 8);
      const body = { id, status: 'VALIDATED', channel: b.channel, correlationId: cid(req), scheduledAt: b.schedule?.sendAt ?? null, links: { self: `/api/v1/messages/${id}` } };
      idem.set(key, { hash, body });
      messages.unshift({ ...messages[0], id, status: 'VALIDATED', channel: b.channel, channelUsed: b.channel, correlationId: body.correlationId, createdAt: now(), updatedAt: now(), terminal: false, attemptCount: 0, attempts: [], deliveryEvents: [], clientReference: b.clientReference ?? null });
      return send(res, 202, body, req, { Location: `/api/v1/messages/${id}` });
    }
    if ((r = p.match(/^\/messages\/([^/]+)(?:\/(status|history|cancel|requeue))?$/))) {
      const msg = messages.find((x) => x.id === r[1]);
      if (!msg) return err(res, req, 404, 'NOT_FOUND', 'Message not found');
      if (!r[2] && m === 'GET') return send(res, 200, msg, req);
      if (r[2] === 'status') return send(res, 200, { id: msg.id, status: msg.status, statusReason: msg.statusReason, channelUsed: msg.channelUsed, attemptCount: msg.attemptCount, updatedAt: msg.updatedAt, terminal: msg.terminal }, req);
      if (r[2] === 'history') return send(res, 200, [
        { at: msg.createdAt, kind: 'AUDIT', type: 'MESSAGE_ACCEPTED', summary: 'MESSAGE_ACCEPTED', detail: { actor: 'sender-retail', to: 'VALIDATED' } },
        { at: msg.createdAt, kind: 'AUDIT', type: 'MESSAGE_TRANSITION', summary: 'MESSAGE_TRANSITION', detail: { actor: 'outbox-publisher', from: 'VALIDATED', to: 'QUEUED' } },
        ...msg.attempts.map((a) => ({ at: a.startedAt, kind: 'ATTEMPT', type: 'ATTEMPT_FINISHED', summary: `Attempt ${a.attemptNo} via ${a.providerId}`, detail: { attemptNo: a.attemptNo, channel: a.channel, providerId: a.providerId, outcome: a.outcome, errorCode: a.errorCode ?? null, latencyMs: a.latencyMs } })),
        ...(msg.status === 'DELIVERED' ? [{ at: msg.updatedAt, kind: 'DELIVERY_EVENT', type: 'DELIVERED', summary: 'Provider receipt DELIVERED', detail: { providerId: 'simulated-email', applied: true, ignoredReason: null } }] : []),
      ], req);
      if (r[2] === 'cancel') { if (!['VALIDATED', 'QUEUED', 'RETRY_PENDING'].includes(msg.status)) return err(res, req, 409, 'ILLEGAL_STATE_TRANSITION', `Cannot cancel from ${msg.status}`); msg.status = 'CANCELLED'; msg.terminal = true; return send(res, 200, summaryOf(msg), req); }
      if (r[2] === 'requeue') { if (!['FAILED', 'DEAD_LETTERED'].includes(msg.status)) return err(res, req, 409, 'ILLEGAL_STATE_TRANSITION', `Cannot requeue from ${msg.status}`); msg.status = 'QUEUED'; return send(res, 200, summaryOf(msg), req); }
    }

    if (p === '/templates' && m === 'GET') {
      let list = templates; for (const k of ['channel']) { const v = url.searchParams.get(k); if (v) list = list.filter((x) => x[k] === v); }
      const st = url.searchParams.get('status'); const code = url.searchParams.get('code');
      let out = list.map(tplSummary); if (st) out = out.filter((x) => (x.versionCounts[st] ?? 0) > 0); if (code) out = out.filter((x) => x.code.includes(code.toUpperCase()));
      return send(res, 200, page(out, url), req);
    }
    if (p === '/templates' && m === 'POST') {
      const b = await readBody(req);
      if (templates.some((t) => t.code === b.code && t.channel === b.channel)) return err(res, req, 409, 'ILLEGAL_STATE_TRANSITION', 'Template already exists');
      if (!b.name) return err(res, req, 400, 'VALIDATION_FAILED', 'Validation failed', [{ field: 'name', message: 'must not be blank' }]);
      const t = { id: 'tpl-' + (templates.length + 1), code: b.code, name: b.name, channel: b.channel, category: b.category, versions: [{ versionNo: 1, locale: b.locale, status: 'DRAFT', subject: b.subject, body: b.body, variables: b.variables ?? [], createdBy: 'author-retail', version: 0 }] };
      templates.push(t); return send(res, 201, t, req);
    }
    if (p === '/templates/preview' && m === 'POST') {
      const b = await readBody(req); const missing = (b.variables ?? []).filter((v) => !(b.sampleData ?? {})[v]);
      if (missing.length) return send(res, 200, { valid: false, errors: missing.map((v) => ({ field: v, message: 'missing sample value' })) }, req);
      const fill = (s) => String(s ?? '').replace(/\{\{\s*(\w+)\s*\}\}/g, (_, k) => b.sampleData?.[k] ?? '');
      return send(res, 200, { valid: true, subject: b.subject ? fill(b.subject) : null, body: fill(b.body) }, req);
    }
    if ((r = p.match(/^\/templates\/([^/]+)(?:\/(history|versions)(?:\/(\d+)\/(submit|approve|reject|retire))?)?$/))) {
      const t = templates.find((x) => x.id === r[1]); if (!t) return err(res, req, 404, 'NOT_FOUND', 'Template not found');
      if (!r[2] && m === 'GET') return send(res, 200, { ...tplSummary(t), versions: t.versions }, req);
      if (r[2] === 'history') return send(res, 200, [{ id: 'a1', actor: 'author-retail', action: 'TEMPLATE_CREATED', entityType: 'TEMPLATE', entityId: t.id, occurredAt: ago(6000) }], req);
      if (r[2] === 'versions' && !r[3] && m === 'POST') { const b = await readBody(req); t.versions.push({ versionNo: Math.max(...t.versions.map((v) => v.versionNo)) + 1, locale: b.locale, status: 'DRAFT', subject: b.subject, body: b.body, variables: b.variables, createdBy: 'author-retail', version: 0 }); return send(res, 201, {}, req); }
      if (r[2] === 'versions' && r[3] && !r[4] && m === 'PUT') { const loc = url.searchParams.get('locale'); const v = t.versions.find((x) => x.versionNo === Number(r[3]) && x.locale === loc); if (!v || v.status !== 'DRAFT') return err(res, req, 409, 'ILLEGAL_STATE_TRANSITION', 'Only DRAFT can be edited'); Object.assign(v, await readBody(req)); v.version++; return send(res, 200, v, req); }
      if (r[4]) { const loc = url.searchParams.get('locale'); const v = t.versions.find((x) => x.versionNo === Number(r[3]) && x.locale === loc); if (!v) return err(res, req, 404, 'NOT_FOUND', 'Version not found');
        const b = await readBody(req); const from = { submit: 'DRAFT', approve: 'PENDING_APPROVAL', reject: 'PENDING_APPROVAL', retire: 'PUBLISHED' }[r[4]];
        if (v.status !== from) return err(res, req, 409, 'ILLEGAL_STATE_TRANSITION', `Cannot ${r[4]} from ${v.status}`);
        if (r[4] === 'approve') { t.versions.filter((x) => x.locale === loc && x.status === 'PUBLISHED').forEach((x) => { x.status = 'RETIRED'; }); v.status = 'PUBLISHED'; v.publishedAt = now(); v.approvedBy = 'approver-retail'; }
        else v.status = { submit: 'PENDING_APPROVAL', reject: 'DRAFT', retire: 'RETIRED' }[r[4]];
        if (r[4] === 'reject') v.rejectionComment = b.comment ?? null;
        return send(res, 200, v, req); }
    }

    if (p === '/recipients' && m === 'GET') { const q = (url.searchParams.get('q') ?? '').toLowerCase(); return send(res, 200, page(recipients.filter((x) => !q || `${x.id} ${x.externalRef} ${x.displayName}`.toLowerCase().includes(q)), url), req); }
    if ((r = p.match(/^\/recipients\/([^/]+)\/preferences$/))) {
      if (!recipients.some((x) => x.id === r[1])) return err(res, req, 404, 'RECIPIENT_NOT_FOUND', 'Recipient not found');
      const cur = prefsOf(r[1]);
      if (m === 'GET') return send(res, 200, cur, req);
      if (m === 'PUT') { const b = await readBody(req);
        if (b.version !== cur.version) return err(res, req, 409, 'CONFLICT_OPTIMISTIC_LOCK', 'Preferences were modified by another user');
        Object.assign(cur, b, { recipientId: r[1], version: cur.version + 1 });
        if (b.__simulateConcurrentEdit) cur.version++; return send(res, 200, cur, req); }
    }

    if (p === '/bulk-jobs' && m === 'GET') return send(res, 200, page(jobs.map(jobView), url), req);
    if (p === '/bulk-jobs' && m === 'POST') { await readBody(req); return send(res, 202, jobView(mkJob('Uploaded CSV job', 300, { fileName: 'upload.csv' })), req); }
    if (p === '/bulk-jobs/generate-synthetic' && m === 'POST') { const b = await readBody(req); return send(res, 202, jobView(mkJob('Synthetic job', b.rows ?? 500)), req); }
    if ((r = p.match(/^\/bulk-jobs\/([^/]+)(?:\/(failures|stop|restart))?$/))) {
      const j = jobs.find((x) => x.id === r[1]); if (!j) return err(res, req, 404, 'NOT_FOUND', 'Job not found');
      if (!r[2]) return send(res, 200, jobView(j), req);
      if (r[2] === 'failures') { advance(j); return send(res, 200, page(Array.from({ length: j.failed }, (_, i) => ({ lineNo: i * 31 + 2, reason: pick(['INVALID_EMAIL', 'UNKNOWN_RECIPIENT', 'MISSING_COLUMN var_amount']), rawLineHash: randomUUID().slice(0, 12) })), url), req); }
      if (r[2] === 'stop') { j.status = 'STOPPED'; return send(res, 200, jobView(j), req); }
      if (r[2] === 'restart') { j.status = 'RUNNING'; j._t0 = Date.now() - (j.processed / 40) * 1000; return send(res, 200, jobView(j), req); }
    }

    if (p === '/providers' && m === 'GET') return send(res, 200, providers, req);
    if ((r = p.match(/^\/providers\/([^/]+)\/(config|fault-mode)$/)) && m === 'PUT') {
      const pr = providers.find((x) => x.providerId === r[1]); if (!pr) return err(res, req, 404, 'NOT_FOUND', 'Provider not found'); const b = await readBody(req);
      if (r[2] === 'config') Object.assign(pr, { enabled: b.enabled, rateLimitPerSec: b.rateLimitPerSec, timeoutMs: b.timeoutMs }); else Object.assign(pr, { faultMode: b.mode, faultProbability: b.probability });
      return send(res, 200, pr, req);
    }
    if (p === '/ops/queues') return send(res, 200, { depthByPriority: { CRITICAL: Math.floor(rnd() * 5), HIGH: Math.floor(rnd() * 30), NORMAL: Math.floor(rnd() * 200), LOW: Math.floor(rnd() * 80) },
      inFlight: Math.floor(rnd() * 16), consumers: 8, consumersPaused, dlqDepth: deadLetters.filter((d) => !d.discarded).length, retryPending: messages.filter((x) => x.status === 'RETRY_PENDING').length, oldestMessageAgeSeconds: Math.floor(rnd() * 900) }, req);
    if (p === '/ops/summary') { const byStatus = {}, byChannel = {}; messages.forEach((x) => { byStatus[x.status] = (byStatus[x.status] ?? 0) + 1; byChannel[x.channel] = (byChannel[x.channel] ?? 0) + 1; });
      const ok = byStatus.DELIVERED ?? 0; const denom = messages.filter((x) => x.terminal || ['FAILED', 'DEAD_LETTERED'].includes(x.status)).length || 1;
      return send(res, 200, { windowHours: 24, total: messages.length, byStatus, byChannel, successRate: ok / denom, p95DispatchDelayMs: 840 }, req); }
    if (p === '/ops/consumers/pause' && m === 'POST') { consumersPaused = true; return send(res, 200, { paused: true }, req); }
    if (p === '/ops/consumers/resume' && m === 'POST') { consumersPaused = false; return send(res, 200, { paused: false }, req); }

    if (p === '/dead-letters' && m === 'GET') return send(res, 200, page(deadLetters, url), req);
    if ((r = p.match(/^\/dead-letters\/([^/]+)\/(replay|discard)$/)) && m === 'POST') {
      const d = deadLetters.find((x) => x.messageId === r[1]); if (!d) return err(res, req, 404, 'NOT_FOUND', 'Dead letter not found');
      if (r[2] === 'discard') d.discarded = true; else { deadLetters.splice(deadLetters.indexOf(d), 1); const msg = messages.find((x) => x.id === r[1]); if (msg) msg.status = 'QUEUED'; }
      return send(res, 200, {}, req);
    }
    if (p === '/failed-messages') return send(res, 200, page(messages.filter((x) => ['FAILED', 'DELIVERY_UNKNOWN', 'RETRY_PENDING'].includes(x.status)).map((x) => ({ id: x.id, status: x.status, channel: x.channel, statusReason: x.statusReason, attemptCount: x.attemptCount, nextAttemptAt: x.nextAttemptAt, updatedAt: x.updatedAt })), url), req);
    if (p === '/delivery-events') { let ev = messages.flatMap((x) => x.deliveryEvents.map((e) => ({ ...e, id: randomUUID(), messageId: x.id, receivedAt: e.occurredAt })));
      const t = url.searchParams.get('type'); if (t) ev = ev.filter((e) => e.type === t); const mid = url.searchParams.get('messageId'); if (mid) ev = ev.filter((e) => e.messageId === mid); return send(res, 200, page(ev, url), req); }
    if (p === '/audit-events') return send(res, 200, page(messages.slice(0, 80).map((x, i) => ({ id: 'au-' + i, actor: 'sender-retail', actorRole: 'SENDER', action: 'MESSAGE_ACCEPTED', entityType: 'MESSAGE', entityId: x.id, correlationId: x.correlationId, detail: { channel: x.channel }, occurredAt: x.createdAt })), url), req);

    return err(res, req, 404, 'NOT_FOUND', `No mock route for ${m} ${path}`);
  } catch (e) {
    return err(res, req, 500, 'INTERNAL_ERROR', String(e?.message ?? e));
  }
});

server.listen(PORT, () => console.log(`[mock-api] UI dev stub listening on http://localhost:${PORT} (synthetic data, NOT the real backend)`));
