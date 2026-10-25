// Shared helpers for the failure-recovery scripts. Plain Node 18+ (global fetch, node:crypto), no dependencies.
// Target: a messaging-platform started with --spring.profiles.active=local (dev token endpoint, simulators, in-memory bus).
import { createHmac, randomUUID } from 'node:crypto';

export const BASE = process.env.BASE_URL ?? 'http://localhost:18080';
// Dummy secret from application-local.yml (messaging.callback.secrets.*); never a real credential.
export const CALLBACK_SECRET = process.env.CALLBACK_SECRET ?? 'local-dev-secret-not-for-production';
export const TENANT = 'tenant-retail';

export async function api(method, path, { token, body, headers = {} } = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: {
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body),
  });
  const text = await res.text();
  let json;
  try {
    json = text ? JSON.parse(text) : undefined;
  } catch {
    json = undefined;
  }
  return { status: res.status, headers: res.headers, json, text };
}

export async function devToken(subject, tenant, roles, scopes = []) {
  const r = await api('POST', '/dev/token', { body: { subject, tenant, roles, scopes } });
  if (r.status !== 200) throw new Error(`dev token failed: ${r.status} ${r.text}`);
  return r.json.accessToken;
}

export const tokens = async () => ({
  sender: await devToken('fr-sender', TENANT, ['SENDER']),
  operator: await devToken('fr-operator', TENANT, ['OPERATOR']),
  admin: await devToken('fr-platform-admin', '*', ['PLATFORM_ADMIN']),
});

export async function submitEmail(token, recipientId = 'rcp-002') {
  const r = await api('POST', '/api/v1/messages', {
    token,
    headers: { 'Idempotency-Key': `fr-${randomUUID()}` },
    body: {
      clientReference: `fr-${Date.now()}`,
      recipientId,
      channel: 'EMAIL',
      category: 'TRANSACTIONAL',
      priority: 'NORMAL',
      template: { code: 'TRADE_CONFIRMATION', locale: 'en-US' },
      variables: { accountLast4: '1234', quantity: '10', symbol: 'ACME', price: '250.00' },
    },
  });
  if (r.status !== 202) throw new Error(`submit failed: ${r.status} ${r.text}`);
  return r.json.id;
}

export async function status(token, id) {
  const r = await api('GET', `/api/v1/messages/${id}/status`, { token });
  if (r.status !== 200) throw new Error(`status failed: ${r.status} ${r.text}`);
  return r.json.status;
}

export async function waitFor(token, id, wanted, timeoutMs = 60_000) {
  const targets = Array.isArray(wanted) ? wanted : [wanted];
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    last = await status(token, id);
    if (targets.includes(last)) return last;
    await new Promise((r) => setTimeout(r, 250));
  }
  throw new Error(`message ${id} did not reach ${targets.join('|')} within ${timeoutMs} ms (last: ${last})`);
}

export function setFault(adminToken, providerId, mode, probability = 1) {
  return api('PUT', `/api/v1/providers/${providerId}/fault-mode`, { token: adminToken, body: { mode, probability } });
}

/** Signed provider webhook exactly as a provider would send it. */
export function postCallback(providerId, payload, eventId) {
  const body = typeof payload === 'string' ? payload : JSON.stringify(payload);
  const t = Math.floor(Date.now() / 1000);
  const v1 = createHmac('sha256', CALLBACK_SECRET).update(`${t}.${body}`).digest('hex');
  return api('POST', `/api/v1/callbacks/${providerId}`, {
    body,
    headers: { 'X-Signature': `t=${t},v1=${v1}`, 'X-Provider-Key-Id': `${providerId}-key`, 'X-Event-Id': eventId },
  });
}

export async function attempts(token, id) {
  const r = await api('GET', `/api/v1/messages/${id}`, { token });
  return r.json.attempts;
}

let failures = 0;
export function check(name, condition, detail = '') {
  if (condition) {
    console.log(`  ok    ${name}`);
  } else {
    failures += 1;
    console.log(`  FAIL  ${name} ${detail}`);
  }
}

export function finish(title) {
  console.log(failures === 0 ? `${title}: all checks passed` : `${title}: ${failures} check(s) FAILED`);
  process.exit(failures === 0 ? 0 : 1);
}
