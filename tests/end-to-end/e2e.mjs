#!/usr/bin/env node
// Real end-to-end test over HTTP against a running backend (profile `local`). Zero dependencies: Node 18+ (global
// fetch, FormData, Blob) and node:crypto only. Everything is synthetic; the provider simulators never call anything.
//
//   SERVER_PORT=18280 bash scripts/run-local.sh \
//     --messaging.dispatch.backoff-base-ms=300 --messaging.dispatch.backoff-cap-ms=2000 \
//     --messaging.dispatch.reconcile-after=3s --messaging.pipeline.scheduler.reconcile-interval-ms=2000 \
//     --messaging.bulk.chunk-size=100
//   BASE_URL=http://localhost:18280 BACKEND_LOG=<path of backend console log> node tests/end-to-end/e2e.mjs
//
// The faster timing knobs only shorten waits (retry backoff, reconciliation delay, bulk chunk size so a job can be
// stopped between chunks); the tests tolerate the defaults too, they just take longer.
// Exit code 0 only when every check passed. The run prints one line per check and a PASS/FAIL summary.
import { createHmac, randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';

const BASE = process.env.BASE_URL ?? 'http://localhost:18080';
const BACKEND_LOG = process.env.BACKEND_LOG ?? '';
const SECRET = process.env.CALLBACK_SECRET ?? 'local-dev-secret-not-for-production'; // dummy secret of application-local.yml
const RUN = Date.now().toString(36);

// ------------------------------------------------------------------------------------------------ harness
let passed = 0;
let failed = 0;
const failures = [];
const skipped = [];
let section = '';

function heading(title) {
  section = title;
  console.log(`\n== ${title}`);
}
function check(name, ok, detail = '') {
  if (ok) {
    passed += 1;
    console.log(`  PASS  ${name}`);
  } else {
    failed += 1;
    failures.push(`${section}: ${name} ${detail}`);
    console.log(`  FAIL  ${name} ${detail}`);
  }
  return ok;
}
function skip(name, why) {
  skipped.push(`${section}: ${name} (${why})`);
  console.log(`  SKIP  ${name} (${why})`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const short = (v) => (typeof v === 'string' ? v : JSON.stringify(v))?.slice(0, 300);

async function api(method, path, { token, body, headers = {}, raw = false } = {}) {
  const isForm = typeof FormData !== 'undefined' && body instanceof FormData;
  const res = await fetch(BASE + path, {
    method,
    headers: {
      ...(body !== undefined && !isForm ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body === undefined ? undefined : isForm || typeof body === 'string' ? body : JSON.stringify(body),
  });
  const text = await res.text();
  let json;
  try {
    json = text && !raw ? JSON.parse(text) : undefined;
  } catch {
    json = undefined;
  }
  return { status: res.status, headers: res.headers, json, text };
}

async function devToken(subject, tenant, roles, scopes = []) {
  const r = await api('POST', '/dev/token', { body: { subject, tenant, roles, scopes } });
  if (r.status !== 200) throw new Error(`dev token failed: ${r.status} ${r.text}`);
  return r.json.accessToken;
}

async function waitFor(fn, { timeoutMs = 60_000, intervalMs = 250, what = 'condition' } = {}) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    last = await fn();
    if (last) return last;
    await sleep(intervalMs);
  }
  throw new Error(`timed out after ${timeoutMs} ms waiting for ${what}`);
}

const T = {}; // tokens, filled in main
const RETAIL = 'tenant-retail';
const WEALTH = 'tenant-wealth';

const statusOf = async (token, id) => (await api('GET', `/api/v1/messages/${id}/status`, { token })).json?.status;
async function waitStatus(token, id, wanted, timeoutMs = 60_000) {
  const targets = Array.isArray(wanted) ? wanted : [wanted];
  let last;
  try {
    await waitFor(async () => {
      last = await statusOf(token, id);
      return targets.includes(last);
    }, { timeoutMs, what: `message ${id} to reach ${targets.join('|')}` });
    return last;
  } catch {
    return `${last} (waited for ${targets.join('|')})`;
  }
}
const detailOf = async (token, id) => (await api('GET', `/api/v1/messages/${id}`, { token })).json;

const VARS = { accountLast4: '1234', quantity: '10', symbol: 'ACME', price: '250.00' };
function emailBody(recipientId, extra = {}) {
  return {
    clientReference: `e2e-${RUN}-${randomUUID().slice(0, 8)}`,
    recipientId,
    channel: 'EMAIL',
    category: 'TRANSACTIONAL',
    priority: 'NORMAL',
    template: { code: 'TRADE_CONFIRMATION', locale: 'en' },
    variables: VARS,
    ...extra,
  };
}
async function submit(token, body, key = `e2e-${RUN}-${randomUUID()}`, headers = {}) {
  const r = await api('POST', '/api/v1/messages', { token, body, headers: { 'Idempotency-Key': key, ...headers } });
  r.key = key;
  return r;
}
const setFault = (mode, probability = 1, provider = 'simulated-email') =>
  api('PUT', `/api/v1/providers/${provider}/fault-mode`, { token: T.admin, body: { mode, probability } });
const emit = (messageId, type, extra = {}) =>
  api('POST', '/dev/simulator/callbacks/emit', { token: T.admin, body: { messageId, type, delayMs: 0, ...extra } });

/** Signed provider webhook exactly as a provider would send it (simulated-email payload format). */
function callback(payload, { eventId, secret = SECRET, t = Math.floor(Date.now() / 1000), provider = 'simulated-email', keyId } = {}) {
  const body = typeof payload === 'string' ? payload : JSON.stringify(payload);
  const v1 = createHmac('sha256', secret).update(`${t}.${body}`).digest('hex');
  return api('POST', `/api/v1/callbacks/${provider}`, {
    body,
    headers: { 'X-Signature': `t=${t},v1=${v1}`, 'X-Provider-Key-Id': keyId ?? `${provider}-key`, 'X-Event-Id': eventId },
  });
}
const receipt = (messageId, event, eventId, extra = {}) => ({
  event, event_id: eventId, timestamp: new Date().toISOString(), meta: { idempotencyRef: `${messageId}:1` }, ...extra,
});

// ------------------------------------------------------------------------------------------------ scenarios
async function personas() {
  heading('1. Personas, authentication and authorization');
  T.admin = await devToken(`e2e-admin-${RUN}`, '*', ['PLATFORM_ADMIN'], ['messages:bulk']);
  T.sender = await devToken(`e2e-sender-${RUN}`, RETAIL, ['SENDER'], ['messages:bulk']);
  T.senderNoBulk = await devToken(`e2e-sender-nb-${RUN}`, RETAIL, ['SENDER']);
  T.tenantAdmin = await devToken(`e2e-tadmin-${RUN}`, RETAIL, ['TENANT_ADMIN'], ['messages:bulk']);
  T.operator = await devToken(`e2e-operator-${RUN}`, RETAIL, ['OPERATOR']);
  T.author = await devToken(`e2e-author-${RUN}`, RETAIL, ['TEMPLATE_AUTHOR']);
  T.approver = await devToken(`e2e-approver-${RUN}`, RETAIL, ['TEMPLATE_APPROVER']);
  T.auditor = await devToken(`e2e-auditor-${RUN}`, RETAIL, ['AUDITOR']);
  T.wealth = await devToken(`e2e-wsender-${RUN}`, WEALTH, ['SENDER']);
  check('dev tokens minted for all personas', Object.values(T).every((t) => typeof t === 'string' && t.split('.').length === 3));

  const health = await api('GET', '/actuator/health');
  check('health endpoint is UP', health.status === 200 && health.json?.status === 'UP', short(health.text));
  const liveness = await api('GET', '/actuator/health/liveness');
  check('liveness probe UP', liveness.status === 200 && liveness.json?.status === 'UP');

  const me = await api('GET', '/api/v1/me', { token: T.operator });
  check('GET /me returns roles and tenant', me.status === 200 && me.json.roles.includes('OPERATOR') && me.json.tenant === RETAIL, short(me.text));

  const none = await api('GET', '/api/v1/messages');
  check('no token -> 401 UNAUTHENTICATED', none.status === 401 && none.json?.code === 'UNAUTHENTICATED', short(none.text));
  const bad = await api('GET', '/api/v1/messages', { token: 'not.a.jwt' });
  check('garbage token -> 401', bad.status === 401, String(bad.status));
  const tampered = await api('GET', '/api/v1/messages', { token: T.sender.slice(0, -4) + 'abcd' });
  check('tampered signature -> 401', tampered.status === 401, String(tampered.status));

  const auditorSend = await submit(T.auditor, emailBody('rcp-001'));
  check('AUDITOR cannot submit -> 403 FORBIDDEN', auditorSend.status === 403 && auditorSend.json?.code === 'FORBIDDEN', short(auditorSend.text));
  const operatorTemplate = await api('POST', '/api/v1/templates', { token: T.operator, body: { code: 'X', name: 'x', channel: 'EMAIL', category: 'SERVICE', locale: 'en', subject: 's', body: 'b' } });
  check('OPERATOR cannot create templates -> 403', operatorTemplate.status === 403, String(operatorTemplate.status));
  const senderFault = await setFaultAs(T.sender);
  check('SENDER cannot set fault mode -> 403', senderFault.status === 403, String(senderFault.status));
  const operatorPause = await api('POST', '/api/v1/ops/consumers/pause', { token: T.operator });
  check('OPERATOR cannot pause consumers -> 403', operatorPause.status === 403, String(operatorPause.status));
  const senderAudit = await api('GET', '/api/v1/audit-events', { token: T.sender });
  check('SENDER cannot read audit events -> 403', senderAudit.status === 403, String(senderAudit.status));
}
const setFaultAs = (token) => api('PUT', '/api/v1/providers/simulated-email/fault-mode', { token, body: { mode: 'NONE', probability: 1 } });

async function submitChannels() {
  heading('2. Submit email, sms and push; delivery receipts');
  const email = await submit(T.sender, emailBody('rcp-003'));
  check('email accepted (202)', email.status === 202 && !!email.json?.id, short(email.text));
  check('202 carries Location header', (email.headers.get('location') ?? '').endsWith(`/api/v1/messages/${email.json?.id}`), email.headers.get('location'));
  check('202 carries correlation id header', !!email.headers.get('x-correlation-id'));
  check('API-Version header present', email.headers.get('api-version') === '1', email.headers.get('api-version'));
  const emailId = email.json.id;
  const emailStatus = await waitStatus(T.sender, emailId, 'SENT_TO_PROVIDER');
  check('email reaches SENT_TO_PROVIDER (provider acceptance != delivery)', emailStatus === 'SENT_TO_PROVIDER', emailStatus);
  const detail = await detailOf(T.sender, emailId);
  check('detail has one ACCEPTED attempt via simulated-email', detail.attempts?.length === 1 && detail.attempts[0].outcome === 'ACCEPTED' && detail.attempts[0].providerId === 'simulated-email', short(detail.attempts));
  check('detail masks the address and never returns body', /^r\*+@example\.test$/.test(detail.recipient?.maskedAddress ?? '') || /\*\*\*/.test(detail.recipient?.maskedAddress ?? ''), short(detail.recipient));
  check('detail never contains rendered content or raw address', !/recipient003@example\.test|Your order to/.test(JSON.stringify(detail)));
  check('detail pins template version', detail.template?.code === 'TRADE_CONFIRMATION' && detail.template?.versionNo === 1, short(detail.template));
  const emitted = await emit(emailId, 'DELIVERED');
  check('simulator emits signed DELIVERED callback (202)', emitted.status === 202, short(emitted.text));
  check('email becomes DELIVERED', (await waitStatus(T.sender, emailId, 'DELIVERED', 20_000)) === 'DELIVERED');
  const done = await api('GET', `/api/v1/messages/${emailId}/status`, { token: T.sender });
  check('status endpoint: terminal=true', done.json?.terminal === true, short(done.text));

  const sms = await submit(T.sender, { ...emailBody('rcp-003'), channel: 'SMS' });
  check('sms accepted (202)', sms.status === 202, short(sms.text));
  check('sms reaches SENT_TO_PROVIDER', (await waitStatus(T.sender, sms.json.id, 'SENT_TO_PROVIDER')) === 'SENT_TO_PROVIDER');
  const smsDetail = await detailOf(T.sender, sms.json.id);
  check('sms attempt used simulated-sms', smsDetail.attempts?.[0]?.providerId === 'simulated-sms', short(smsDetail.attempts));

  const push = await submit(T.sender, {
    clientReference: `push-${RUN}`, recipientId: 'rcp-003', channel: 'PUSH', category: 'SERVICE', priority: 'HIGH',
    template: { code: 'PRICE_ALERT', locale: 'en' }, variables: { symbol: 'ACME', change: '+2.1%', price: '251.40' },
    channelOptions: { push: { collapseKey: `acme-${RUN}`, ttlSeconds: 600 } },
  });
  check('push accepted (202)', push.status === 202, short(push.text));
  check('push reaches SENT_TO_PROVIDER', (await waitStatus(T.sender, push.json.id, 'SENT_TO_PROVIDER')) === 'SENT_TO_PROVIDER');
  const pushDetail = await detailOf(T.sender, push.json.id);
  check('push attempt used simulated-push', pushDetail.attempts?.[0]?.providerId === 'simulated-push', short(pushDetail.attempts));

  const list = await api('GET', `/api/v1/messages?channel=SMS&status=SENT_TO_PROVIDER&size=5&sort=createdAt,desc`, { token: T.sender });
  check('message list filters by channel+status with PageResponse envelope', list.status === 200 && Array.isArray(list.json.content) && list.json.content.every((m) => m.channel === 'SMS') && typeof list.json.totalElements === 'number', short(list.text));
  check('list items carry channelUsed/templateCode', list.json.content.length > 0 && list.json.content.every((m) => m.templateCode && m.channelUsed), short(list.json.content?.[0]));
  const byCorr = await api('GET', `/api/v1/messages?correlationId=${email.json.correlationId}`, { token: T.sender });
  check('list filters by correlationId', byCorr.json?.content?.length === 1 && byCorr.json.content[0].id === emailId, short(byCorr.text));
  const bigPage = await api('GET', '/api/v1/messages?size=100000', { token: T.sender });
  check('page size is capped/validated (<=200)', bigPage.status === 400 || (bigPage.status === 200 && bigPage.json.size <= 200), `${bigPage.status} size=${bigPage.json?.size}`);
  return { emailId };
}

async function idempotency() {
  heading('3. Idempotency');
  const body = emailBody('rcp-004');
  const first = await submit(T.sender, body, `idem-${RUN}`);
  check('first submit 202', first.status === 202, short(first.text));
  const replay = await submit(T.sender, body, `idem-${RUN}`);
  check('same key + same body replays original (200 + Idempotent-Replay: true)', replay.status === 200 && replay.headers.get('idempotent-replay') === 'true' && replay.json?.id === first.json?.id, `${replay.status} ${short(replay.text)}`);
  const reuse = await submit(T.sender, { ...body, variables: { ...VARS, quantity: '11' } }, `idem-${RUN}`);
  check('same key + different body -> 422 IDEMPOTENCY_KEY_REUSED', reuse.status === 422 && reuse.json?.code === 'IDEMPOTENCY_KEY_REUSED', short(reuse.text));
  const otherTenant = await submit(T.wealth, { ...emailBody('rcp-041'), template: { code: 'TRADE_CONFIRMATION', locale: 'en' } }, `idem-${RUN}`);
  check('same key in another tenant is independent (202)', otherTenant.status === 202, short(otherTenant.text));
  const noKey = await api('POST', '/api/v1/messages', { token: T.sender, body });
  check('missing Idempotency-Key -> 400', noKey.status === 400, short(noKey.text));
  // concurrent identical submissions create exactly one message
  const key = `idem-race-${RUN}`;
  const raceBody = emailBody('rcp-005');
  const results = await Promise.all(Array.from({ length: 8 }, () => submit(T.sender, raceBody, key)));
  const ids = new Set(results.filter((r) => r.json?.id).map((r) => r.json.id));
  check('8 concurrent identical submissions yield exactly one message id', ids.size === 1, `ids=${[...ids]} statuses=${results.map((r) => r.status)}`);
  check('concurrent duplicates answered 202/200/409(in progress) only', results.every((r) => [202, 200, 409].includes(r.status)), results.map((r) => r.status).join(','));
}

async function validation() {
  heading('4. Malformed input, validation and error contract');
  const malformed = await api('POST', '/api/v1/messages', { token: T.sender, body: '{"channel": "EMAIL", ', headers: { 'Idempotency-Key': `mal-${RUN}` } });
  check('malformed JSON -> 400 MALFORMED_REQUEST', malformed.status === 400 && malformed.json?.code === 'MALFORMED_REQUEST', short(malformed.text));
  check('error body has ApiError fields', ['timestamp', 'status', 'error', 'code', 'message', 'path', 'correlationId'].every((k) => k in (malformed.json ?? {})), short(malformed.text));
  check('error body does not leak stack/class names', !/Exception|at com\.|org\.springframework/.test(malformed.text), short(malformed.text));
  const channel = await submit(T.sender, emailBody('rcp-001', { channel: 'FAX' }));
  check('unsupported channel -> 400 UNSUPPORTED_CHANNEL', channel.status === 400 && channel.json?.code === 'UNSUPPORTED_CHANNEL', short(channel.text));
  const missing = await submit(T.sender, { recipientId: 'rcp-001' });
  check('missing fields -> 400 VALIDATION_FAILED with field details', missing.status === 400 && missing.json?.code === 'VALIDATION_FAILED' && missing.json.details?.length > 0, short(missing.text));
  const badEnum = await submit(T.sender, emailBody('rcp-001', { priority: 'URGENT' }));
  check('unknown priority enum -> 400', badEnum.status === 400, short(badEnum.text));
  const noTpl = await submit(T.sender, emailBody('rcp-001', { template: { code: 'NO_SUCH_TEMPLATE', locale: 'en' } }));
  check('unknown template -> 422 TEMPLATE_NOT_FOUND', noTpl.status === 422 && noTpl.json?.code === 'TEMPLATE_NOT_FOUND', short(noTpl.text));
  const noVar = await submit(T.sender, emailBody('rcp-001', { variables: { accountLast4: '1' } }));
  check('missing template variables -> 422 TEMPLATE_VARIABLE_MISSING', noVar.status === 422 && noVar.json?.code === 'TEMPLATE_VARIABLE_MISSING', short(noVar.text));
  const noRcp = await submit(T.sender, emailBody('rcp-does-not-exist'));
  check('unknown recipient -> 422 RECIPIENT_NOT_FOUND', noRcp.status === 422 && noRcp.json?.code === 'RECIPIENT_NOT_FOUND', short(noRcp.text));
  const huge = await api('POST', '/api/v1/messages', { token: T.sender, body: JSON.stringify({ ...emailBody('rcp-001'), metadata: { blob: 'x'.repeat(300_000) } }), headers: { 'Idempotency-Key': `huge-${RUN}` } });
  check('body > 256 KB -> 413 PAYLOAD_TOO_LARGE', huge.status === 413, `${huge.status} ${short(huge.text)}`);
  const getBad = await api('GET', '/api/v1/messages/does-not-exist', { token: T.sender });
  check('unknown message id -> 404 NOT_FOUND', getBad.status === 404 && getBad.json?.code === 'NOT_FOUND', short(getBad.text));
  const injected = await submit(T.sender, emailBody('rcp-001', { variables: { ...VARS, symbol: '<script>alert(1)</script>{{evil}}' } }));
  check('HTML/template injection in variables is accepted as data (202), not evaluated', injected.status === 202, short(injected.text));
  const wrongMethod = await api('DELETE', '/api/v1/messages', { token: T.sender });
  check('unsupported method -> 405 with ApiError', wrongMethod.status === 405 && wrongMethod.json?.code, short(wrongMethod.text));
  const unknownPath = await api('GET', '/api/v1/nope', { token: T.sender });
  check('unknown path -> 404', unknownPath.status === 404, String(unknownPath.status));
  const xCid = await api('GET', '/api/v1/me', { token: T.sender, headers: { 'X-Correlation-Id': `my-corr-${RUN}` } });
  check('X-Correlation-Id is echoed', xCid.headers.get('x-correlation-id') === `my-corr-${RUN}`, xCid.headers.get('x-correlation-id'));
}

async function tenancy(emailId) {
  heading('5. Tenant isolation and platform-admin scoping');
  const cross = await api('GET', `/api/v1/messages/${emailId}`, { token: T.wealth });
  check('other tenant gets 404 (not 403) for a message', cross.status === 404, String(cross.status));
  const crossCancel = await api('POST', `/api/v1/messages/${emailId}/cancel`, { token: T.wealth });
  check('other tenant cannot cancel -> 404', crossCancel.status === 404, String(crossCancel.status));
  const crossList = await api('GET', '/api/v1/messages?size=200', { token: T.wealth });
  check('other tenant list never contains retail messages', crossList.json.content.every((m) => m.id !== emailId), '');
  const crossHistory = await api('GET', `/api/v1/messages/${emailId}/history`, { token: T.wealth });
  check('other tenant cannot read history -> 404', crossHistory.status === 404, String(crossHistory.status));
  const crossRecipient = await submit(T.wealth, emailBody('rcp-001'));
  check('sending to another tenant\'s recipient -> 422 RECIPIENT_NOT_FOUND', crossRecipient.status === 422, short(crossRecipient.text));
  const headerSpoof = await api('GET', `/api/v1/messages/${emailId}`, { token: T.wealth, headers: { 'X-Tenant-Id': RETAIL } });
  check('X-Tenant-Id from a non-admin is ignored (still 404)', headerSpoof.status === 404, String(headerSpoof.status));
  const adminRead = await api('GET', `/api/v1/messages/${emailId}`, { token: T.admin });
  check('platform admin reads across tenants', adminRead.status === 200, String(adminRead.status));
  const adminWriteNoTenant = await submit(T.admin, emailBody('rcp-001'));
  check('platform admin write without X-Tenant-Id -> 400', adminWriteNoTenant.status === 400, short(adminWriteNoTenant.text));
  const adminWrite = await submit(T.admin, emailBody('rcp-001'), undefined, { 'X-Tenant-Id': RETAIL });
  check('platform admin write with X-Tenant-Id -> 202', adminWrite.status === 202, short(adminWrite.text));
  const adminBadTenant = await submit(T.admin, emailBody('rcp-001'), undefined, { 'X-Tenant-Id': 'tenant-nope' });
  check('platform admin with unknown tenant -> 400', adminBadTenant.status === 400, short(adminBadTenant.text));
}

async function policy() {
  heading('6. Consent, suppression and quiet hours');
  const marketing = (rcp, extra = {}) => ({
    clientReference: `mk-${RUN}-${rcp}`, recipientId: rcp, channel: 'EMAIL', category: 'MARKETING', priority: 'LOW',
    template: { code: 'MARKETING_PROMO', locale: 'en' }, variables: { offerTitle: 'Spring offer', deadline: '2026-12-31' }, ...extra,
  });
  // rcp-002 has marketing_opt_in = false in the seed
  const optOut = await submit(T.sender, marketing('rcp-002'));
  check('opted-out recipient: marketing accepted as REJECTED (202)', optOut.status === 202 && optOut.json?.status === 'REJECTED', short(optOut.text));
  const optOutDetail = await detailOf(T.sender, optOut.json.id);
  check('rejection reason is OPTED_OUT and message is terminal', optOutDetail.statusReason === 'OPTED_OUT' && optOutDetail.terminal === true, short(optOutDetail.statusReason));
  check('no provider attempt for the rejected message', optOutDetail.attempts.length === 0);
  const txToOptOut = await submit(T.sender, emailBody('rcp-002'));
  check('transactional to the same recipient ignores marketing opt-out', txToOptOut.status === 202 && txToOptOut.json.status !== 'REJECTED', short(txToOptOut.text));
  const noAddress = await submit(T.sender, { clientReference: `na-${RUN}`, recipientId: 'rcp-009', channel: 'PUSH', category: 'SERVICE', priority: 'NORMAL', template: { code: 'PRICE_ALERT', locale: 'en' }, variables: { symbol: 'ACME', change: '+1%', price: '1.00' } });
  const naDetail = await detailOf(T.sender, noAddress.json.id);
  check('recipient without push token: REJECTED (no address)', noAddress.status === 202 && noAddress.json.status === 'REJECTED' && /ADDRESS/.test(naDetail.statusReason ?? ''), short(naDetail.statusReason));
  const sup = await api('POST', '/api/v1/recipients/rcp-012/suppressions', { token: T.operator, body: { channel: 'EMAIL', reason: 'e2e complaint' } });
  check('operator adds a suppression', sup.status === 200 || sup.status === 201, short(sup.text));
  const suppressed = await submit(T.sender, emailBody('rcp-012'));
  check('suppressed address blocks even transactional email (REJECTED)', suppressed.status === 202 && suppressed.json.status === 'REJECTED', short(suppressed.text));
  const unsup = await api('DELETE', '/api/v1/recipients/rcp-012/suppressions/EMAIL', { token: T.operator });
  check('operator removes the suppression', unsup.status === 200 || unsup.status === 204, String(unsup.status));
  const afterUnsup = await submit(T.sender, emailBody('rcp-012'));
  check('after removal the same recipient is deliverable', afterUnsup.status === 202 && afterUnsup.json.status !== 'REJECTED', short(afterUnsup.text));

  // quiet hours: rcp-001 (America/New_York, marketing opt-in) gets a quiet window around "now"
  const prefs = await api('GET', '/api/v1/recipients/rcp-001/preferences', { token: T.operator });
  check('preferences readable', prefs.status === 200 && typeof prefs.json?.version === 'number', short(prefs.text));
  const hhmm = (offsetH) => {
    const d = new Date(Date.now() + offsetH * 3_600_000);
    return new Intl.DateTimeFormat('en-GB', { timeZone: 'America/New_York', hour: '2-digit', minute: '2-digit', hour12: false }).format(d);
  };
  const put = await api('PUT', '/api/v1/recipients/rcp-001/preferences', { token: T.operator, body: { ...pickPrefs(prefs.json), quietStart: hhmm(-1), quietEnd: hhmm(+2) } });
  check('quiet-hours window set around now', put.status === 200, short(put.text));
  const stale = await api('PUT', '/api/v1/recipients/rcp-001/preferences', { token: T.operator, body: { ...pickPrefs(prefs.json), version: prefs.json.version } });
  check('stale preference write -> 409 CONFLICT_OPTIMISTIC_LOCK', stale.status === 409 && stale.json?.code === 'CONFLICT_OPTIMISTIC_LOCK', short(stale.text));
  const deferred = await submit(T.sender, marketing('rcp-001'));
  const dDetail = await detailOf(T.sender, deferred.json.id);
  check('marketing inside quiet hours is deferred (VALIDATED with future scheduledAt)', deferred.status === 202 && dDetail.status === 'VALIDATED' && dDetail.scheduledAt && new Date(dDetail.scheduledAt) > new Date(), short({ s: dDetail.status, at: dDetail.scheduledAt }));
  await sleep(1500);
  check('deferred message is not dispatched yet (no attempts)', (await detailOf(T.sender, deferred.json.id)).attempts.length === 0);
  const secAlert = await submit(T.sender, { clientReference: `sec-${RUN}`, recipientId: 'rcp-001', channel: 'EMAIL', category: 'SECURITY_ALERT', priority: 'CRITICAL', template: { code: 'PASSWORD_RESET', locale: 'en' }, variables: { code: '123456' } });
  check('security alert ignores quiet hours (dispatched)', (await waitStatus(T.sender, secAlert.json.id, ['SENT_TO_PROVIDER', 'DELIVERED'])) === 'SENT_TO_PROVIDER');
  const cancelDeferred = await api('POST', `/api/v1/messages/${deferred.json.id}/cancel`, { token: T.sender });
  check('deferred message can be cancelled', cancelDeferred.status === 200, short(cancelDeferred.text));
  // restore
  const cur = await api('GET', '/api/v1/recipients/rcp-001/preferences', { token: T.operator });
  const restore = await api('PUT', '/api/v1/recipients/rcp-001/preferences', { token: T.operator, body: { ...pickPrefs(cur.json), quietStart: null, quietEnd: null } });
  check('quiet hours restored', restore.status === 200, short(restore.text));
}
function pickPrefs(p) {
  return { marketingOptIn: p.marketingOptIn, emailEnabled: p.emailEnabled, smsEnabled: p.smsEnabled, pushEnabled: p.pushEnabled, quietStart: p.quietStart, quietEnd: p.quietEnd, timeZone: p.timeZone, fallbackOrder: p.fallbackOrder, version: p.version };
}

async function templates() {
  heading('7. Template lifecycle: create -> submit -> approve (four-eyes) -> publish -> use -> new version');
  const code = `E2E_NOTICE_${RUN.toUpperCase()}`;
  const create = await api('POST', '/api/v1/templates', { token: T.author, body: { code, name: 'E2E notice', channel: 'EMAIL', category: 'TRANSACTIONAL', locale: 'en', subject: 'Notice {{note}}', body: '<p>Hello {{firstName|friend}}, note: {{note}} (v1)</p>', variables: ['firstName', 'note'] } });
  check('author creates template (201/200) with a DRAFT version', [200, 201].includes(create.status) && create.json?.versions?.[0]?.status === 'DRAFT', short(create.text));
  const id = create.json.id;
  const dup = await api('POST', '/api/v1/templates', { token: T.author, body: { code, name: 'dup', channel: 'EMAIL', category: 'TRANSACTIONAL', locale: 'en', subject: 's', body: 'b' } });
  check('duplicate code+channel -> 409', dup.status === 409, short(dup.text));
  const useDraft = await submit(T.sender, emailBody('rcp-006', { template: { code, locale: 'en' }, variables: { note: 'x' } }));
  check('a DRAFT template cannot be used for sending (422 TEMPLATE_NOT_FOUND)', useDraft.status === 422, short(useDraft.text));
  const preview = await api('POST', '/api/v1/templates/preview', { token: T.author, body: { channel: 'EMAIL', subject: 'Hi {{note}}', body: '<b>{{note}}</b>', variables: ['note'], sampleData: { note: '<script>x</script>' }, locale: 'en' } });
  check('preview renders and HTML-escapes sample data', preview.status === 200 && preview.json.valid === true && !/<script>/.test(preview.json.body ?? ''), short(preview.text));
  const badPreview = await api('POST', '/api/v1/templates/preview', { token: T.author, body: { channel: 'EMAIL', subject: 's', body: '{{undeclared}}', variables: [], sampleData: {}, locale: 'en' } });
  check('preview reports undeclared variables as invalid', badPreview.status === 200 ? badPreview.json.valid === false : badPreview.status === 422 || badPreview.status === 400, short(badPreview.text));
  const submitV = await api('POST', `/api/v1/templates/${id}/versions/1/submit?locale=en`, { token: T.author });
  check('author submits for approval', submitV.status === 200, short(submitV.text));
  const selfApprove = await api('POST', `/api/v1/templates/${id}/versions/1/approve?locale=en`, { token: T.author });
  check('author cannot approve own version -> 403', selfApprove.status === 403, short(selfApprove.text));
  const tenantAdminApprove = await api('POST', `/api/v1/templates/${id}/versions/1/approve?locale=en`, { token: T.tenantAdmin });
  check('TENANT_ADMIN is not an approver -> 403', tenantAdminApprove.status === 403, String(tenantAdminApprove.status));
  const editPending = await api('PUT', `/api/v1/templates/${id}/versions/1?locale=en`, { token: T.author, body: { subject: 's', body: 'tampered {{note}}', variables: ['note'], version: 0 } });
  check('PENDING_APPROVAL version cannot be edited -> 409', editPending.status === 409, short(editPending.text));
  const approve = await api('POST', `/api/v1/templates/${id}/versions/1/approve?locale=en`, { token: T.approver });
  check('different approver publishes v1', approve.status === 200, short(approve.text));
  const got = await api('GET', `/api/v1/templates/${id}`, { token: T.sender });
  check('v1 is PUBLISHED', got.json?.versions?.find((v) => v.versionNo === 1)?.status === 'PUBLISHED', short(got.json?.versions));

  const m1 = await submit(T.sender, emailBody('rcp-006', { template: { code, locale: 'en' }, variables: { note: 'first' } }));
  check('published template can be used', m1.status === 202 && m1.json.status !== 'REJECTED', short(m1.text));
  const sched = await submit(T.sender, emailBody('rcp-007', { template: { code, locale: 'en' }, variables: { note: 'scheduled' }, schedule: { sendAt: new Date(Date.now() + 3_600_000).toISOString() } }));
  check('scheduled message accepted (not yet dispatched)', sched.status === 202, short(sched.text));
  const pinned1 = await detailOf(T.sender, m1.json.id);
  check('message pinned to version 1 at accept time', pinned1.template.versionNo === 1, short(pinned1.template));

  const v2 = await api('POST', `/api/v1/templates/${id}/versions`, { token: T.author, body: { locale: 'en', subject: 'Notice {{note}} v2', body: '<p>Hello {{firstName|friend}}, note: {{note}} (v2)</p>', variables: ['firstName', 'note'] } });
  check('author creates a new DRAFT version (v2)', [200, 201].includes(v2.status), short(v2.text));
  const v2No = v2.json?.versionNo ?? 2;
  check('v2 submit', (await api('POST', `/api/v1/templates/${id}/versions/${v2No}/submit?locale=en`, { token: T.author })).status === 200);
  const reject = await api('POST', `/api/v1/templates/${id}/versions/${v2No}/reject?locale=en`, { token: T.approver, body: { comment: 'please reword' } });
  check('approver rejects with comment (back to DRAFT)', reject.status === 200, short(reject.text));
  check('v2 resubmit', (await api('POST', `/api/v1/templates/${id}/versions/${v2No}/submit?locale=en`, { token: T.author })).status === 200);
  check('approver publishes v2', (await api('POST', `/api/v1/templates/${id}/versions/${v2No}/approve?locale=en`, { token: T.approver })).status === 200);
  const after = await api('GET', `/api/v1/templates/${id}`, { token: T.sender });
  const st = Object.fromEntries((after.json?.versions ?? []).map((v) => [v.versionNo, v.status]));
  check('v1 RETIRED and v2 PUBLISHED', st[1] === 'RETIRED' && st[v2No] === 'PUBLISHED', short(st));
  const pinnedAfter = await detailOf(T.sender, m1.json.id);
  check('the already accepted message keeps version 1', pinnedAfter.template.versionNo === 1, short(pinnedAfter.template));
  const schedAfter = await detailOf(T.sender, sched.json.id);
  check('the scheduled (not yet sent) message keeps version 1', schedAfter.template.versionNo === 1, short(schedAfter.template));
  const m2 = await submit(T.sender, emailBody('rcp-008', { template: { code, locale: 'en' }, variables: { note: 'second' } }));
  const pinned2 = await detailOf(T.sender, m2.json.id);
  check('a new message pins version 2', pinned2.template.versionNo === v2No, short(pinned2.template));
  check('cancel the scheduled message', (await api('POST', `/api/v1/messages/${sched.json.id}/cancel`, { token: T.sender })).status === 200);
  const hist = await api('GET', `/api/v1/templates/${id}/history`, { token: T.author });
  check('template change history is available', hist.status === 200 && JSON.stringify(hist.json).length > 20, short(hist.text));
  const list = await api('GET', `/api/v1/templates?code=${code}`, { token: T.sender });
  check('template list filter by code', list.status === 200 && list.json.content?.some((t) => t.id === id), short(list.text));
  const wealthSee = await api('GET', `/api/v1/templates/${id}`, { token: T.wealth });
  check('other tenant cannot see the template -> 404', wealthSee.status === 404, String(wealthSee.status));
}

async function simulatorModes() {
  heading('8. Simulator fault modes: retry, permanent failure, timeout, ambiguous, rate limit, outage -> DLQ -> replay');
  const rcp = { transient: 'rcp-010', permanent: 'rcp-011', timeout: 'rcp-017', ambiguous: 'rcp-014', rate: 'rcp-015', outage: 'rcp-016' };

  // TRANSIENT -> retry -> eventually delivered
  await setFault('TRANSIENT', 1);
  const t1 = await submit(T.sender, emailBody(rcp.transient));
  const s1 = await waitStatus(T.sender, t1.json.id, 'RETRY_PENDING', 20_000);
  check('transient failure -> RETRY_PENDING', s1 === 'RETRY_PENDING', s1);
  const d1 = await detailOf(T.sender, t1.json.id);
  check('failed attempt recorded with TRANSIENT_FAILURE and nextAttemptAt set', d1.attempts[0]?.outcome === 'TRANSIENT_FAILURE' && !!d1.nextAttemptAt, short(d1.attempts));
  await setFault('NONE');
  const s1b = await waitStatus(T.sender, t1.json.id, ['SENT_TO_PROVIDER', 'DELIVERED'], 30_000);
  check('after the fault clears the retry is sent (SENT_TO_PROVIDER)', s1b === 'SENT_TO_PROVIDER', s1b);
  await emit(t1.json.id, 'DELIVERED');
  check('retried message becomes DELIVERED', (await waitStatus(T.sender, t1.json.id, 'DELIVERED', 20_000)) === 'DELIVERED');
  const d1b = await detailOf(T.sender, t1.json.id);
  check('attempt count > 1 and a later attempt ACCEPTED', d1b.attempts.length >= 2 && d1b.attempts.some((a) => a.outcome === 'ACCEPTED'), short(d1b.attempts.map((a) => a.outcome)));

  // PERMANENT -> FAILED
  await setFault('PERMANENT', 1);
  const p1 = await submit(T.sender, emailBody(rcp.permanent));
  check('permanent failure -> FAILED', (await waitStatus(T.sender, p1.json.id, 'FAILED', 20_000)) === 'FAILED');
  const pd = await detailOf(T.sender, p1.json.id);
  check('exactly one attempt for a permanent failure (no retry)', pd.attempts.length === 1 && pd.attempts[0].outcome === 'PERMANENT_FAILURE', short(pd.attempts));
  const failedList = await api('GET', '/api/v1/failed-messages?size=50', { token: T.operator });
  check('FAILED message appears in the failed-messages investigation list', failedList.status === 200 && failedList.json.content.some((m) => m.id === p1.json.id), short(failedList.text?.slice(0, 200)));
  await setFault('NONE');
  const rq = await api('POST', `/api/v1/messages/${p1.json.id}/requeue`, { token: T.operator });
  check('operator requeue of a FAILED message', rq.status === 200, short(rq.text));
  check('requeued message reaches the provider', (await waitStatus(T.sender, p1.json.id, ['SENT_TO_PROVIDER', 'DELIVERED'], 30_000)) === 'SENT_TO_PROVIDER');
  const rqSender = await api('POST', `/api/v1/messages/${p1.json.id}/requeue`, { token: T.sender });
  check('SENDER cannot requeue -> 403', rqSender.status === 403, String(rqSender.status));

  // TIMEOUT -> DELIVERY_UNKNOWN -> reconciliation (provider says NOT_RECEIVED) -> safe retry
  await setFault('TIMEOUT', 1);
  const to1 = await submit(T.sender, emailBody(rcp.timeout));
  const sTo = await waitStatus(T.sender, to1.json.id, 'DELIVERY_UNKNOWN', 30_000);
  check('provider timeout -> DELIVERY_UNKNOWN (not blindly retried)', sTo === 'DELIVERY_UNKNOWN', sTo);
  const toDetail = await detailOf(T.sender, to1.json.id);
  check('exactly one attempt while unknown', toDetail.attempts.length === 1 && toDetail.attempts[0].outcome === 'TIMEOUT', short(toDetail.attempts));
  await setFault('NONE');
  const sTo2 = await waitStatus(T.sender, to1.json.id, ['SENT_TO_PROVIDER', 'DELIVERED'], 40_000);
  check('reconciliation finds NOT_RECEIVED and safely re-sends', sTo2 === 'SENT_TO_PROVIDER', sTo2);
  const toAudit = await api('GET', `/api/v1/audit-events?entityType=MESSAGE&entityId=${to1.json.id}&size=100`, { token: T.auditor });
  check('RECONCILIATION_CHECK is audited', toAudit.json?.content?.some((e) => e.action === 'RECONCILIATION_CHECK'), short(toAudit.json?.content?.map((e) => e.action)));

  // AMBIGUOUS -> DELIVERY_UNKNOWN -> provider has it (ACCEPTED) -> stays unknown until receipt
  await setFault('AMBIGUOUS', 1);
  const am = await submit(T.sender, emailBody(rcp.ambiguous));
  check('ambiguous provider answer -> DELIVERY_UNKNOWN', (await waitStatus(T.sender, am.json.id, 'DELIVERY_UNKNOWN', 20_000)) === 'DELIVERY_UNKNOWN');
  await setFault('NONE');
  await sleep(8000);
  const amStill = await detailOf(T.sender, am.json.id);
  check('reconciliation sees the provider accepted it: no duplicate send, still DELIVERY_UNKNOWN', amStill.status === 'DELIVERY_UNKNOWN' && amStill.attempts.length === 1, short({ s: amStill.status, n: amStill.attempts.length }));
  const lateCb = await callback(receipt(am.json.id, 'delivered', `amb-${RUN}`), { eventId: `amb-${RUN}` });
  check('late DELIVERED callback accepted', lateCb.status === 202, short(lateCb.text));
  check('DELIVERY_UNKNOWN -> DELIVERED by the late callback', (await waitStatus(T.sender, am.json.id, 'DELIVERED', 15_000)) === 'DELIVERED');

  // RATE_LIMIT -> RETRY_PENDING -> delivered
  await setFault('RATE_LIMIT', 1);
  const rl = await submit(T.sender, emailBody(rcp.rate));
  check('provider rate limit (429) -> RETRY_PENDING', (await waitStatus(T.sender, rl.json.id, 'RETRY_PENDING', 20_000)) === 'RETRY_PENDING');
  const rlDetail = await detailOf(T.sender, rl.json.id);
  check('attempt recorded as RATE_LIMITED', rlDetail.attempts[0]?.outcome === 'RATE_LIMITED', short(rlDetail.attempts));
  await setFault('NONE');
  check('rate-limited message eventually sent', (await waitStatus(T.sender, rl.json.id, ['SENT_TO_PROVIDER', 'DELIVERED'], 30_000)) === 'SENT_TO_PROVIDER');

  // Provider config: tiny token bucket slows dispatch but loses nothing
  const cfg = await api('PUT', '/api/v1/providers/simulated-sms/config', { token: T.admin, body: { rateLimitPerSec: 2 } });
  check('platform admin lowers simulated-sms rate limit', cfg.status === 200, short(cfg.text));
  const burst = await Promise.all(Array.from({ length: 6 }, (_, i) => submit(T.sender, { ...emailBody(`rcp-0${20 + i}`), channel: 'SMS' })));
  const burstStatuses = await Promise.all(burst.map((r) => waitStatus(T.sender, r.json.id, ['SENT_TO_PROVIDER', 'DELIVERED'], 40_000)));
  check('6 SMS through a 2/s limiter all dispatched (none lost)', burstStatuses.every((s) => s === 'SENT_TO_PROVIDER'), short(burstStatuses));
  await api('PUT', '/api/v1/providers/simulated-sms/config', { token: T.admin, body: { rateLimitPerSec: 100 } });

  // OUTAGE -> retries exhausted -> DEAD_LETTERED -> replay
  await setFault('TRANSIENT', 1);
  const out = await submit(T.sender, emailBody(rcp.outage));
  const sOut = await waitStatus(T.sender, out.json.id, 'DEAD_LETTERED', 90_000);
  check('outage: retries exhausted -> DEAD_LETTERED', sOut === 'DEAD_LETTERED', sOut);
  const outDetail = await detailOf(T.sender, out.json.id);
  check('dead-lettered after max attempts (5)', outDetail.attempts.length === 5 && outDetail.attemptCount === 5, short({ n: outDetail.attempts.length, c: outDetail.attemptCount }));
  const dlq = await api('GET', '/api/v1/dead-letters?size=50', { token: T.operator });
  const dlqRow = dlq.json?.content?.find((m) => (m.messageId ?? m.id) === out.json.id);
  check('dead-letter list shows RETRIES_EXHAUSTED', !!dlqRow && (dlqRow.reason ?? dlqRow.dlqReason) === 'RETRIES_EXHAUSTED', short(dlqRow ?? dlq.text?.slice(0, 200)));
  const providers = await api('GET', '/api/v1/providers', { token: T.operator });
  const email = providers.json?.find?.((p) => p.providerId === 'simulated-email');
  check('provider health reflects the outage (failures, lastError)', !!email && email.successRate < 1 && !!email.lastError, short(email));
  const sendDuringOutage = await api('POST', `/api/v1/dead-letters/${out.json.id}/replay`, { token: T.sender });
  check('SENDER cannot replay dead letters -> 403', sendDuringOutage.status === 403, String(sendDuringOutage.status));
  await setFault('NONE');
  const replay = await api('POST', `/api/v1/dead-letters/${out.json.id}/replay`, { token: T.operator });
  check('operator replays the dead letter', replay.status === 200 || replay.status === 202, short(replay.text));
  const sReplay = await waitStatus(T.sender, out.json.id, ['SENT_TO_PROVIDER', 'DELIVERED'], 60_000);
  check('replayed message is sent after the outage ends (circuit recovers)', sReplay === 'SENT_TO_PROVIDER', sReplay);
  const replayAgain = await api('POST', `/api/v1/dead-letters/${out.json.id}/replay`, { token: T.operator });
  check('replaying a message that is no longer dead-lettered -> 409', replayAgain.status === 409, short(replayAgain.text));

  // discard path
  await setFault('PERMANENT', 1, 'simulated-sms');
  const dis = await submit(T.sender, { ...emailBody('rcp-026'), channel: 'SMS' });
  await waitStatus(T.sender, dis.json.id, 'FAILED', 20_000);
  await setFault('NONE', 1, 'simulated-sms');
}

async function callbacks() {
  heading('9. Signed provider callbacks: valid, bad signature, replay, duplicate, out-of-order');
  const m = await submit(T.sender, emailBody('rcp-027'));
  check('callback target reaches SENT_TO_PROVIDER', (await waitStatus(T.sender, m.json.id, 'SENT_TO_PROVIDER')) === 'SENT_TO_PROVIDER');
  const id = m.json.id;
  const ok = await callback(receipt(id, 'sent', `cb-sent-${RUN}`), { eventId: `cb-sent-${RUN}` });
  check('valid SENT callback accepted (202 accepted)', ok.status === 202 && ok.json?.result === 'accepted', short(ok.text));
  const forged = await callback(receipt(id, 'delivered', `cb-forged-${RUN}`), { eventId: `cb-forged-${RUN}`, secret: 'wrong-secret' });
  check('wrong secret -> 401 INVALID_SIGNATURE', forged.status === 401 && forged.json?.code === 'INVALID_SIGNATURE', short(forged.text));
  const old = await callback(receipt(id, 'delivered', `cb-old-${RUN}`), { eventId: `cb-old-${RUN}`, t: Math.floor(Date.now() / 1000) - 3600 });
  check('timestamp outside the 5 minute tolerance -> 401', old.status === 401, short(old.text));
  const wrongKey = await callback(receipt(id, 'delivered', `cb-key-${RUN}`), { eventId: `cb-key-${RUN}`, keyId: 'simulated-sms-key' });
  check('key id not bound to the provider -> 401', wrongKey.status === 401, short(wrongKey.text));
  const unknownProvider = await callback(receipt(id, 'delivered', `cb-up-${RUN}`), { eventId: `cb-up-${RUN}`, provider: 'no-such-provider', keyId: 'x' });
  check('unknown provider -> 401 (same answer as a bad signature)', unknownProvider.status === 401, short(unknownProvider.text));
  const noHeaders = await api('POST', '/api/v1/callbacks/simulated-email', { body: JSON.stringify(receipt(id, 'delivered', 'x')) });
  check('callback without signature headers -> 401', noHeaders.status === 401, short(noHeaders.text));
  check('rejected callbacks changed nothing (still SENT_TO_PROVIDER)', (await statusOf(T.sender, id)) === 'SENT_TO_PROVIDER');
  const replay = await callback(receipt(id, 'sent', `cb-sent-${RUN}`), { eventId: `cb-sent-${RUN}` });
  check('same X-Event-Id again -> 200 replayed', replay.status === 200 && replay.json?.result === 'replayed', short(replay.text));
  const dupPayload = await callback(receipt(id, 'sent', `cb-sent-${RUN}`), { eventId: `cb-hdr-other-${RUN}` });
  check('same provider event id under another header id -> duplicate (no second row)', dupPayload.status === 202 && ['duplicate', 'stale', 'out_of_order'].includes(dupPayload.json?.result), short(dupPayload.text));
  const malformed = await callback('{"event": ', { eventId: `cb-mal-${RUN}` });
  check('signed but malformed payload -> 400', malformed.status === 400, short(malformed.text));
  const unknownMsg = await callback({ event: 'delivered', event_id: `cb-unk-${RUN}`, timestamp: new Date().toISOString(), meta: { idempotencyRef: 'no-such-message:1' } }, { eventId: `cb-unk-${RUN}` });
  check('event for an unknown message -> 202 unknown_message', unknownMsg.status === 202 && unknownMsg.json?.result === 'unknown_message', short(unknownMsg.text));
  const delivered = await callback(receipt(id, 'delivered', `cb-del-${RUN}`), { eventId: `cb-del-${RUN}` });
  check('DELIVERED callback accepted', delivered.status === 202 && delivered.json?.result === 'accepted', short(delivered.text));
  check('message DELIVERED', (await waitStatus(T.sender, id, 'DELIVERED', 10_000)) === 'DELIVERED');
  const late = await callback(receipt(id, 'sent', `cb-late-${RUN}`, { timestamp: new Date(Date.now() - 60_000).toISOString() }), { eventId: `cb-late-${RUN}` });
  check('late SENT after DELIVERED is out_of_order and ignored', late.status === 202 && late.json?.result === 'out_of_order', short(late.text));
  const bounced = await callback(receipt(id, 'hard_bounce', `cb-bounce-${RUN}`), { eventId: `cb-bounce-${RUN}` });
  check('BOUNCED after DELIVERED is a conflict; first terminal state wins', bounced.status === 202 && bounced.json?.result === 'conflict', short(bounced.text));
  check('status is still DELIVERED', (await statusOf(T.sender, id)) === 'DELIVERED');
  const events = await api('GET', `/api/v1/delivery-events?messageId=${id}&size=50`, { token: T.operator });
  const ev = events.json?.content ?? [];
  check('delivery events: applied DELIVERED and ignored OUT_OF_ORDER SENT recorded', ev.some((e) => e.type === 'DELIVERED' && e.applied) && ev.some((e) => e.type === 'SENT' && e.applied === false && e.ignoredReason === 'OUT_OF_ORDER'), short(ev.map((e) => `${e.type}:${e.applied}:${e.ignoredReason}`)));
  const evTenant = await api('GET', `/api/v1/delivery-events?messageId=${id}`, { token: T.wealth });
  check('delivery events are tenant scoped (other tenant sees none)', evTenant.status === 403 || (evTenant.status === 200 && evTenant.json.content.length === 0), `${evTenant.status}`);
  const diag = await api('GET', `/api/v1/diagnostics/messages/${id}`, { token: T.operator });
  check('provider diagnostics available to operator (Mongo archive)', diag.status === 200, short(diag.text?.slice(0, 200)));
  const diagSender = await api('GET', `/api/v1/diagnostics/messages/${id}`, { token: T.sender });
  check('diagnostics forbidden for SENDER', diagSender.status === 403, String(diagSender.status));
}

async function cancelAndHistory() {
  heading('10. Cancel, history timeline, audit');
  const future = new Date(Date.now() + 3_600_000).toISOString();
  const sched = await submit(T.sender, emailBody('rcp-028', { schedule: { sendAt: future } }));
  check('scheduled message accepted', sched.status === 202 && !!sched.json.scheduledAt, short(sched.text));
  const cancel = await api('POST', `/api/v1/messages/${sched.json.id}/cancel`, { token: T.sender });
  check('cancel a scheduled message -> 200 CANCELLED', cancel.status === 200 && (cancel.json?.status === 'CANCELLED' || (await statusOf(T.sender, sched.json.id)) === 'CANCELLED'), short(cancel.text));
  const again = await api('POST', `/api/v1/messages/${sched.json.id}/cancel`, { token: T.sender });
  check('cancel again -> 409 ILLEGAL_STATE_TRANSITION', again.status === 409 && again.json?.code === 'ILLEGAL_STATE_TRANSITION', short(again.text));
  const requeueCancelled = await api('POST', `/api/v1/messages/${sched.json.id}/requeue`, { token: T.operator });
  check('a CANCELLED message cannot be requeued -> 409', requeueCancelled.status === 409, short(requeueCancelled.text));

  const done = await submit(T.sender, emailBody('rcp-029'));
  await waitStatus(T.sender, done.json.id, 'SENT_TO_PROVIDER');
  await emit(done.json.id, 'DELIVERED');
  await waitStatus(T.sender, done.json.id, 'DELIVERED', 20_000);
  const cancelDelivered = await api('POST', `/api/v1/messages/${done.json.id}/cancel`, { token: T.sender });
  check('cancel after delivery -> 409', cancelDelivered.status === 409, short(cancelDelivered.text));

  const history = await api('GET', `/api/v1/messages/${done.json.id}/history`, { token: T.sender });
  const h = history.json ?? [];
  check('history is a time-ordered merge of AUDIT, ATTEMPT and DELIVERY_EVENT entries', history.status === 200 && ['AUDIT', 'ATTEMPT', 'DELIVERY_EVENT'].every((k) => h.some((e) => e.kind === k)) && h.every((e, i) => i === 0 || new Date(h[i - 1].at) <= new Date(e.at)), short(h.map((e) => e.kind)));
  check('history entries follow TimelineEntry {at,kind,type,summary,detail}', h.every((e) => e.at && e.kind && e.type && e.summary && typeof e.detail === 'object'), short(h[0]));
  check('audit entries carry the actor in detail.actor', h.filter((e) => e.kind === 'AUDIT').every((e) => typeof e.detail.actor === 'string'), short(h.find((e) => e.kind === 'AUDIT')));
  check('history never leaks content or raw addresses', !/recipient029@example\.test|Your order to/.test(JSON.stringify(h)));

  const audit = await api('GET', `/api/v1/audit-events?entityType=MESSAGE&entityId=${done.json.id}&size=100`, { token: T.auditor });
  check('auditor reads audit events of the message', audit.status === 200 && audit.json.content.length >= 3, short(audit.text?.slice(0, 200)));
  check('audit contains MESSAGE_ACCEPTED', audit.json.content.some((e) => /ACCEPT/.test(e.action)), short(audit.json.content.map((e) => e.action)));
  const auditWealth = await api('GET', `/api/v1/audit-events?entityType=MESSAGE&entityId=${done.json.id}`, { token: T.wealth });
  check('audit is tenant scoped (other tenant: 403 or empty)', auditWealth.status === 403 || auditWealth.json?.content?.length === 0, String(auditWealth.status));
  const auditAction = await api('GET', '/api/v1/audit-events?action=TEMPLATE_APPROVED&size=5', { token: T.tenantAdmin });
  check('audit filter by action (template approval audited)', auditAction.status === 200 && auditAction.json.content.length > 0, short(auditAction.text?.slice(0, 200)));
  const auditPost = await api('POST', '/api/v1/audit-events', { token: T.admin, body: {} });
  check('audit trail is append-only through the API (no POST)', auditPost.status === 405 || auditPost.status === 404, String(auditPost.status));
}

async function bulk() {
  heading('11. Bulk jobs: generate-synthetic, progress, failures, upload, authorization, stop/restart');
  const noScope = await api('POST', '/api/v1/bulk-jobs/generate-synthetic', { token: T.senderNoBulk, body: { rows: 10 } });
  check('bulk without scope messages:bulk -> 403', noScope.status === 403, short(noScope.text));
  const auditorBulk = await api('POST', '/api/v1/bulk-jobs/generate-synthetic', { token: T.auditor, body: { rows: 10 } });
  check('AUDITOR cannot start bulk -> 403', auditorBulk.status === 403, String(auditorBulk.status));
  const invalid = await api('POST', '/api/v1/bulk-jobs/generate-synthetic', { token: T.sender, body: { rows: 0 } });
  check('rows=0 -> 400', invalid.status === 400, short(invalid.text));

  const job = await api('POST', '/api/v1/bulk-jobs/generate-synthetic', { token: T.sender, body: { rows: 500, duplicateRate: 0.02, invalidRate: 0.02, templateCode: 'TRADE_CONFIRMATION', channel: 'EMAIL', category: 'TRANSACTIONAL' } });
  check('synthetic 500-row job accepted (202) with Location', job.status === 202 && !!job.json?.id && (job.headers.get('location') ?? '').includes(job.json?.id), short(job.text));
  const id = job.json.id;
  let seenRunning = false;
  let final;
  try {
    final = await waitFor(async () => {
      const j = (await api('GET', `/api/v1/bulk-jobs/${id}`, { token: T.sender })).json;
      if (j.status === 'RUNNING') seenRunning = true;
      return ['COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'STOPPED', 'ABANDONED'].includes(j.status) ? j : null;
    }, { timeoutMs: 120_000, intervalMs: 150, what: 'bulk job to finish' });
  } catch (e) {
    final = (await api('GET', `/api/v1/bulk-jobs/${id}`, { token: T.sender })).json;
  }
  check('job finished COMPLETED_WITH_ERRORS (invalid + duplicate rows)', final.status === 'COMPLETED_WITH_ERRORS', short(final));
  check('total counts the rows', final.total >= 500, `total=${final.total}`);
  check('processed = succeeded + failed + duplicate', final.processed === final.succeeded + final.failed + final.duplicate, short(final));
  check('some rows failed and some were duplicates', final.failed > 0 && final.duplicate > 0, short(final));
  check('most rows succeeded', final.succeeded > 400, `succeeded=${final.succeeded}`);
  if (!seenRunning) skip('observed RUNNING state while polling', 'job finished between polls');
  const failuresPage = await api('GET', `/api/v1/bulk-jobs/${id}/failures?size=50&sort=lineNo,asc`, { token: T.sender });
  check('failures endpoint lists rejected rows with line numbers and hashes only', failuresPage.status === 200 && failuresPage.json.content.length > 0 && failuresPage.json.content.every((f) => typeof f.lineNo === 'number' && f.reason && !/@example\.test/.test(JSON.stringify(f))), short(failuresPage.json?.content?.[0]));
  check('failure reasons include DUPLICATE_EXTERNAL_REF and a validation/recipient reason', failuresPage.json.content.some((f) => /DUPLICATE_EXTERNAL_REF/.test(f.reason)) && failuresPage.json.content.some((f) => !/DUPLICATE/.test(f.reason)), short(failuresPage.json.content.map((f) => f.reason)));
  const jobs = await api('GET', '/api/v1/bulk-jobs?size=5&sort=createdAt,desc', { token: T.tenantAdmin });
  check('job list readable by tenant admin, newest first', jobs.status === 200 && jobs.json.content[0]?.id === id, short(jobs.text?.slice(0, 200)));
  const wealthJob = await api('GET', `/api/v1/bulk-jobs/${id}`, { token: T.wealth });
  check('other tenant cannot see the job -> 404', wealthJob.status === 404, String(wealthJob.status));
  const sample = await api('GET', '/api/v1/messages?size=5&sort=createdAt,desc&q=bulk', { token: T.sender });
  check('bulk messages flow through the same pipeline (list works)', sample.status === 200, String(sample.status));
  const stopDone = await api('POST', `/api/v1/bulk-jobs/${id}/stop`, { token: T.sender });
  check('stop on a finished job -> 409', stopDone.status === 409, short(stopDone.text));

  // upload CSV
  const rows = ['externalRef,locale,var_accountLast4,var_quantity,var_symbol,var_price'];
  for (let i = 1; i <= 40; i++) rows.push(`CUST-${String(i).padStart(3, '0')},en,${1000 + i},${i},ACME,10.${i}`);
  rows.push('CUST-001,en,1001,1,ACME,10.1'); // duplicate
  rows.push('CUST-999,en,9999,1,ACME,10.1'); // unknown recipient
  rows.push('CUST-002,en,1002'); // too few columns
  const form = new FormData();
  form.append('file', new Blob([rows.join('\n') + '\n'], { type: 'text/csv' }), 'e2e-recipients.csv');
  form.append('name', `e2e upload ${RUN}`);
  form.append('templateCode', 'TRADE_CONFIRMATION');
  form.append('channel', 'EMAIL');
  form.append('category', 'TRANSACTIONAL');
  form.append('ratePerSecond', '500');
  const up = await api('POST', '/api/v1/bulk-jobs', { token: T.sender, body: form });
  check('CSV upload accepted (202)', up.status === 202 && !!up.json?.id, short(up.text));
  const upFinal = await waitFor(async () => {
    const j = (await api('GET', `/api/v1/bulk-jobs/${up.json.id}`, { token: T.sender })).json;
    return ['COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'STOPPED'].includes(j.status) ? j : null;
  }, { timeoutMs: 60_000, what: 'upload job to finish' });
  // 40 valid rows (a few seeded recipients are suppressed for EMAIL and are counted as POLICY_REJECTED failures),
  // 1 duplicate reference, 1 unknown recipient, 1 short row
  check('upload: counters add up (43 rows = succeeded + failed + 1 duplicate), bad rows rejected', upFinal.status === 'COMPLETED_WITH_ERRORS' && upFinal.total === 43 && upFinal.duplicate === 1 && upFinal.succeeded + upFinal.failed === 42 && upFinal.succeeded >= 36 && upFinal.failed >= 2, short(upFinal));
  const upFailures = await api('GET', `/api/v1/bulk-jobs/${up.json.id}/failures?size=50`, { token: T.sender });
  const reasons = (upFailures.json?.content ?? []).map((f) => f.reason).join(' | ');
  check('upload failures name RECIPIENT_NOT_FOUND, a malformed row and the duplicate', /RECIPIENT_NOT_FOUND/.test(reasons) && /MALFORMED|COLUMN/i.test(reasons) && /DUPLICATE_EXTERNAL_REF/.test(reasons), reasons);
  const emptyForm = new FormData();
  emptyForm.append('name', 'no file');
  emptyForm.append('templateCode', 'TRADE_CONFIRMATION');
  emptyForm.append('channel', 'EMAIL');
  emptyForm.append('category', 'TRANSACTIONAL');
  const noFile = await api('POST', '/api/v1/bulk-jobs', { token: T.sender, body: emptyForm });
  check('upload without file -> 400', noFile.status === 400, short(noFile.text));
  const badForm = new FormData();
  badForm.append('file', new Blob(['not,a,valid,header\n1,2,3,4\n'], { type: 'text/csv' }), 'bad.csv');
  badForm.append('name', 'bad header');
  badForm.append('templateCode', 'TRADE_CONFIRMATION');
  badForm.append('channel', 'EMAIL');
  badForm.append('category', 'TRANSACTIONAL');
  const badHeader = await api('POST', '/api/v1/bulk-jobs', { token: T.sender, body: badForm });
  check('upload with invalid CSV header -> 400', badHeader.status === 400, short(badHeader.text));

  // stop / restart on a large job
  const big = await api('POST', '/api/v1/bulk-jobs/generate-synthetic', { token: T.sender, body: { rows: 20000, duplicateRate: 0, invalidRate: 0, templateCode: 'TRADE_CONFIRMATION', channel: 'EMAIL', category: 'TRANSACTIONAL' } });
  check('large synthetic job accepted', big.status === 202, short(big.text));
  const bigId = big.json.id;
  await waitFor(async () => {
    const j = (await api('GET', `/api/v1/bulk-jobs/${bigId}`, { token: T.sender })).json;
    return j.status === 'RUNNING' && j.processed > 0;
  }, { timeoutMs: 30_000, intervalMs: 100, what: 'big job to be running' }).catch(() => null);
  const stop = await api('POST', `/api/v1/bulk-jobs/${bigId}/stop`, { token: T.sender });
  if (stop.status === 200) {
    const stopped = await waitFor(async () => {
      const j = (await api('GET', `/api/v1/bulk-jobs/${bigId}`, { token: T.sender })).json;
      return ['STOPPED', 'COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED'].includes(j.status) ? j : null;
    }, { timeoutMs: 60_000, what: 'job to stop' });
    if (stopped.status === 'STOPPED') {
      check('running job stopped between chunks (partial progress kept)', stopped.processed > 0 && stopped.processed < 20000, `processed=${stopped.processed}`);
      const restart = await api('POST', `/api/v1/bulk-jobs/${bigId}/restart`, { token: T.sender });
      check('restart accepted', restart.status === 200, short(restart.text));
      const resumed = await waitFor(async () => {
        const j = (await api('GET', `/api/v1/bulk-jobs/${bigId}`, { token: T.sender })).json;
        return ['COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'STOPPED'].includes(j.status) ? j : null;
      }, { timeoutMs: 240_000, intervalMs: 500, what: 'restarted job to finish' });
      check('restarted job completes all rows exactly once (succeeded = 20000, no duplicates)', resumed.status === 'COMPLETED' && resumed.succeeded === 20000 && resumed.duplicate === 0, short(resumed));
    } else {
      skip('stop/restart', `job reached ${stopped.status} before the stop took effect`);
    }
  } else {
    skip('stop/restart', `stop returned ${stop.status} (job probably already finished): ${short(stop.text)}`);
  }
  const noStopAuth = await api('POST', `/api/v1/bulk-jobs/${bigId}/stop`, { token: T.senderNoBulk });
  check('stop without scope messages:bulk -> 403', noStopAuth.status === 403, String(noStopAuth.status));
}

async function drain() {
  heading('11b. Let the bulk backlog drain (API backpressure would otherwise answer 503 to new submissions)');
  let last;
  const ok = await waitFor(async () => {
    const sum = (await api('GET', '/api/v1/ops/summary', { token: T.admin })).json?.byStatus ?? {};
    last = sum;
    return ['VALIDATED', 'QUEUED', 'PROCESSING', 'RETRY_PENDING'].every((k) => !sum[k]);
  }, { timeoutMs: 600_000, intervalMs: 2000, what: 'pipeline to drain' }).catch(() => false);
  check('pipeline drained (no VALIDATED/QUEUED/PROCESSING/RETRY_PENDING left)', !!ok, short(last));
}

async function ops() {
  heading('12. Ops, providers, metrics, log hygiene');
  const queues = await api('GET', '/api/v1/ops/queues', { token: T.operator });
  const q = queues.json ?? {};
  check('queues: depth per priority, in-flight, consumers, DLQ, retry-pending, oldest age, paused flags', queues.status === 200 && q.depthByPriority && 'inFlight' in q && 'consumers' in q && 'dlqDepth' in q && 'retryPending' in q && 'oldestMessageAgeSeconds' in q && 'consumersPaused' in q, short(queues.text));
  const senderQueues = await api('GET', '/api/v1/ops/queues', { token: T.sender });
  check('SENDER cannot read queue stats -> 403', senderQueues.status === 403, String(senderQueues.status));
  const summary = await api('GET', '/api/v1/ops/summary', { token: T.sender });
  const s = summary.json ?? {};
  check('summary: byStatus, byChannel, total, successRate, p95 dispatch delay', summary.status === 200 && s.byStatus && s.byChannel && s.total > 0 && typeof s.successRate === 'number' && 'p95DispatchDelayMs' in s, short(summary.text));
  check('summary total equals sum(byStatus)', s.total === Object.values(s.byStatus).reduce((a, b) => a + b, 0), short({ total: s.total, byStatus: s.byStatus }));
  const wealthSummary = await api('GET', '/api/v1/ops/summary', { token: T.wealth });
  check('summary is tenant scoped (wealth total < global total)', wealthSummary.json.total < (await api('GET', '/api/v1/ops/summary', { token: T.admin })).json.total);

  const pause = await api('POST', '/api/v1/ops/consumers/pause', { token: T.admin });
  check('platform admin pauses consumers', pause.status === 200 && pause.json?.paused === true, short(pause.text));
  const paused = await submit(T.sender, emailBody('rcp-030'));
  check('submission while consumers are paused is still accepted (202)', paused.status === 202, short(paused.text));
  await sleep(2500);
  const st = await statusOf(T.sender, paused.json.id);
  check('while paused a new message is queued but not processed', ['VALIDATED', 'QUEUED'].includes(st), st);
  const qPaused = await api('GET', '/api/v1/ops/queues', { token: T.operator });
  check('queue stats report consumersPaused=true and a positive depth', qPaused.json.consumersPaused === true && Object.values(qPaused.json.depthByPriority).reduce((a, b) => a + b, 0) >= 1, short(qPaused.text));
  const resume = await api('POST', '/api/v1/ops/consumers/resume', { token: T.admin });
  check('platform admin resumes consumers', resume.status === 200 && resume.json?.paused === false, short(resume.text));
  check('queued message is processed after resume', (await waitStatus(T.sender, paused.json.id, 'SENT_TO_PROVIDER', 30_000)) === 'SENT_TO_PROVIDER');

  const providers = await api('GET', '/api/v1/providers', { token: T.tenantAdmin });
  check('provider health lists the four providers with circuit state, rate limit, fault mode', providers.status === 200 && providers.json.length === 4 && providers.json.every((p) => p.providerId && p.circuitState && 'rateLimitPerSec' in p && 'faultMode' in p && 'faultProbability' in p), short(providers.text));
  const cfgDeny = await api('PUT', '/api/v1/providers/simulated-email/config', { token: T.tenantAdmin, body: { enabled: true } });
  check('TENANT_ADMIN cannot change provider config -> 403', cfgDeny.status === 403, String(cfgDeny.status));
  const faultBad = await setFault('EXPLODE');
  check('invalid fault mode -> 400', faultBad.status === 400, short(faultBad.text));
  const faultUnknown = await api('PUT', '/api/v1/providers/no-such/fault-mode', { token: T.admin, body: { mode: 'NONE', probability: 1 } });
  check('fault mode on unknown provider -> 404', faultUnknown.status === 404, String(faultUnknown.status));

  const prom = await api('GET', '/actuator/prometheus', { raw: true });
  check('prometheus endpoint served', prom.status === 200 && prom.text.length > 1000, String(prom.status));
  for (const meter of [
    'messaging_messages_accepted_total', 'messaging_transitions_total', 'messaging_provider_calls_total', 'messaging_provider_latency_seconds',
    'messaging_dispatch_delay_seconds', 'messaging_queue_depth', 'messaging_queue_inflight', 'messaging_queue_dlq_depth', 'messaging_retry_scheduled_total',
    'messaging_deadletter_total', 'messaging_callbacks_total', 'messaging_idempotency_hits_total', 'messaging_provider_circuit_state', 'messaging_bulk_progress',
    'http_server_requests_seconds', 'hikaricp_connections', 'jvm_memory_used_bytes',
  ]) {
    check(`meter present: ${meter}`, prom.text.includes(meter), '');
  }
  check('callback results counted by result tag', ['accepted', 'replayed', 'invalid_signature', 'out_of_order', 'conflict'].every((r) => new RegExp(`messaging_callbacks_total\\{[^}]*result="${r}"`).test(prom.text)), '');
  check('no PII/tenant data in metric labels (no addresses or message ids)', !/@example\.test|\+1555|messageId=/.test(prom.text));
  const metrics = await api('GET', '/actuator/metrics', { token: T.admin });
  check('actuator exposure limited (env/heap dump not exposed)', (await api('GET', '/actuator/env', { token: T.admin })).status === 404 && (await api('GET', '/actuator/heapdump', { token: T.admin })).status === 404, '');
  void metrics;

  const docs = await api('GET', '/v3/api-docs');
  check('OpenAPI served (local profile) and lists the bulk-jobs and callbacks paths', docs.status === 200 && !!docs.json?.paths?.['/api/v1/bulk-jobs'] && !!docs.json?.paths?.['/api/v1/callbacks/{providerId}'], String(docs.status));
}

async function rateLimit() {
  heading('13. API rate limit (token bucket per client)');
  const burstToken = await devToken(`e2e-burst-${RUN}`, RETAIL, ['OPERATOR']);
  const rs = [];
  let next = 0;
  const worker = async () => {
    while (next < 400) {
      next += 1;
      try {
        rs.push(await api('GET', '/api/v1/me', { token: burstToken }));
      } catch (e) {
        rs.push({ status: 0, json: undefined, headers: new Headers(), error: e.message });
      }
    }
  };
  await Promise.all(Array.from({ length: 40 }, worker));
  check('no connection errors under 40 parallel clients', rs.every((r) => r.status !== 0), `errors=${rs.filter((r) => r.status === 0).length}`);
  const limited = rs.filter((r) => r.status === 429);
  check('burst of 400 parallel requests triggers 429 RATE_LIMITED', limited.length > 0 && limited[0].json?.code === 'RATE_LIMITED', `429s=${limited.length}/${rs.length}`);
  check('429 carries Retry-After', limited.length > 0 && !!limited[0].headers.get('retry-after'), '');
  check('requests before the limit were served (200)', rs.some((r) => r.status === 200));
  const other = await api('GET', '/api/v1/me', { token: T.sender });
  check('another client is unaffected', other.status === 200, String(other.status));
}

function logHygiene() {
  heading('14. Backend log redaction spot check');
  if (!BACKEND_LOG) {
    skip('log checks', 'BACKEND_LOG not set');
    return;
  }
  let log = '';
  try {
    log = readFileSync(BACKEND_LOG, 'utf8');
  } catch (e) {
    skip('log checks', `cannot read ${BACKEND_LOG}: ${e.message}`);
    return;
  }
  check('log is non-empty (dispatch activity logged)', log.length > 10_000, `bytes=${log.length}`);
  check('no raw email addresses in the log', !/recipient\d{3}@example\.test/.test(log), (log.match(/recipient\d{3}@example\.test/) ?? [''])[0]);
  check('no phone numbers in the log', !/\+1555010\d{4}/.test(log));
  check('no push tokens in the log', !/push-token-synthetic/.test(log));
  check('no JWT / bearer tokens in the log', !/eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}/.test(log) && !/Bearer [A-Za-z0-9._-]{20,}/.test(log));
  check('no template variable values or rendered content in the log', !/Your order to|Hello friend|<script>alert\(1\)/.test(log));
  check('callback HMAC secret never logged', !log.includes(SECRET));
  check('no stack traces for handled client errors (no "at com.example" frames from 4xx)', !/ApiException[\s\S]{0,200}\n\s+at com\.example/.test(log));
  check('correlation id MDC present in log lines', /cid=[0-9a-f-]{8,}/.test(log));
}

// ------------------------------------------------------------------------------------------------ main
const started = Date.now();
console.log(`E2E run ${RUN} against ${BASE} at ${new Date().toISOString()}`);
try {
  await personas();
  const { emailId } = await submitChannels();
  await idempotency();
  await validation();
  await tenancy(emailId);
  await policy();
  await templates();
  await simulatorModes();
  await callbacks();
  await cancelAndHistory();
  await bulk();
  await drain();
  await ops();
  await rateLimit();
  logHygiene();
} catch (e) {
  failed += 1;
  failures.push(`${section}: scenario aborted: ${e.stack ?? e}`);
  console.log(`  FAIL  scenario aborted: ${e.stack ?? e}`);
}
try {
  await setFault('NONE');
  await setFault('NONE', 1, 'simulated-sms');
  await api('POST', '/api/v1/ops/consumers/resume', { token: T.admin });
} catch {
  /* best-effort cleanup */
}

console.log(`\n==== RESULT: ${passed} passed, ${failed} failed, ${skipped.length} skipped in ${((Date.now() - started) / 1000).toFixed(1)} s`);
for (const f of failures) console.log(`  FAILED: ${f}`);
for (const s of skipped) console.log(`  SKIPPED: ${s}`);
process.exit(failed === 0 ? 0 : 1);
