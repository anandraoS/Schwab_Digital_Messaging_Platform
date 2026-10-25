import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import { auth } from '../auth/session';
import { messagesApi } from '../api/messages';
import { bulkJobsApi } from '../api/bulkJobs';
import { templatesApi } from '../api/templates';
import { server } from '../../test/server';
import { clearSession, seedSession } from '../../test/utils';
import { apiRequest, newCorrelationId, toQuery } from './client';
import { NormalizedError } from './errorMapper';
import { loadingStore } from './loading';

function capture(path: string, method: 'get' | 'post' | 'put' = 'get', body: unknown = {}, status = 200) {
  const seen: { headers: Headers; url: URL; body: string }[] = [];
  server.use(http[method](`*${path}`, async ({ request }) => {
    seen.push({ headers: request.headers, url: new URL(request.url), body: await request.text() });
    return HttpResponse.json(body as Record<string, unknown>, { status });
  }));
  return seen;
}

describe('api client', () => {
  it('adds a generated X-Correlation-Id to each request', async () => {
    const seen = capture('/api/v1/ping');
    await apiRequest('GET', '/api/v1/ping');
    expect(seen[0].headers.get('x-correlation-id')).toMatch(/.{8,}/);
  });

  it('keeps a caller-provided correlation id', async () => {
    const seen = capture('/api/v1/ping');
    await apiRequest('GET', '/api/v1/ping', { headers: { 'X-Correlation-Id': 'mine' } });
    expect(seen[0].headers.get('x-correlation-id')).toBe('mine');
  });

  it('generates distinct correlation ids', () => {
    expect(newCorrelationId()).not.toBe(newCorrelationId());
  });

  it('adds the bearer token when signed in', async () => {
    seedSession(['SENDER']);
    const seen = capture('/api/v1/messages');
    await apiRequest('GET', '/api/v1/messages');
    expect(seen[0].headers.get('authorization')).toBe('Bearer header.payload.sig');
  });

  it('sends no Authorization header when signed out', async () => {
    const seen = capture('/api/v1/messages');
    await apiRequest('GET', '/api/v1/messages');
    expect(seen[0].headers.has('authorization')).toBe(false);
  });

  it('never attaches the token to anonymous requests (/dev/token)', async () => {
    seedSession(['SENDER']);
    const seen = capture('/dev/token', 'post');
    await apiRequest('POST', '/dev/token', { body: {}, anonymous: true });
    expect(seen[0].headers.has('authorization')).toBe(false);
  });

  it('adds X-Tenant-Id for platform admins with a scope set', async () => {
    seedSession(['PLATFORM_ADMIN'], 'admin', '*');
    auth.setTenantScope('tenant-wealth');
    const seen = capture('/api/v1/messages');
    await apiRequest('GET', '/api/v1/messages');
    expect(seen[0].headers.get('x-tenant-id')).toBe('tenant-wealth');
  });

  it('platform admin without a scope: reads are cross-tenant, writes carry the default write tenant', async () => {
    seedSession(['PLATFORM_ADMIN'], 'admin', '*');
    const read = capture('/api/v1/messages');
    await apiRequest('GET', '/api/v1/messages');
    expect(read[0].headers.has('x-tenant-id')).toBe(false);
    const write = capture('/api/v1/messages', 'post');
    await apiRequest('POST', '/api/v1/messages', { body: {} });
    expect(write[0].headers.get('x-tenant-id')).toBe('tenant-retail');
  });

  it('does not add X-Tenant-Id for non-admins even if a scope is set', async () => {
    seedSession(['OPERATOR']);
    auth.setTenantScope('tenant-wealth');
    const seen = capture('/api/v1/messages');
    await apiRequest('GET', '/api/v1/messages');
    expect(seen[0].headers.has('x-tenant-id')).toBe(false);
  });

  it('normalizes error responses into NormalizedError', async () => {
    server.use(http.get('*/api/v1/x', () => HttpResponse.json({ code: 'FORBIDDEN', message: 'no' }, { status: 403 })));
    const err = await apiRequest('GET', '/api/v1/x').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(NormalizedError);
    expect((err as NormalizedError).code).toBe('FORBIDDEN');
    expect((err as NormalizedError).status).toBe(403);
  });

  it('on 401 signs the user out and flags the session as expired', async () => {
    seedSession(['SENDER']);
    server.use(http.get('*/api/v1/x', () => HttpResponse.json({ code: 'UNAUTHENTICATED' }, { status: 401 })));
    await expect(apiRequest('GET', '/api/v1/x')).rejects.toBeInstanceOf(NormalizedError);
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.getState().expired).toBe(true);
  });

  it('a 401 from an anonymous request (bad dev login) does not sign out', async () => {
    seedSession(['SENDER']);
    server.use(http.post('*/dev/token', () => HttpResponse.json({ code: 'UNAUTHENTICATED' }, { status: 401 })));
    await expect(apiRequest('POST', '/dev/token', { body: {}, anonymous: true })).rejects.toBeInstanceOf(NormalizedError);
    expect(auth.isAuthenticated()).toBe(true);
  });

  it('reports a network failure as NETWORK_ERROR', async () => {
    server.use(http.get('*/api/v1/down', () => HttpResponse.error()));
    const err = (await apiRequest('GET', '/api/v1/down').catch((e: unknown) => e)) as NormalizedError;
    expect(err.code).toBe('NETWORK_ERROR');
    expect(err.status).toBe(0);
  });

  it('toggles the global loading indicator around a request and clears it on failure', async () => {
    let release: () => void = () => undefined;
    const gate = new Promise<void>((r) => { release = r; });
    server.use(http.get('*/api/v1/slow', async () => { await gate; return HttpResponse.json({}); }));
    const p = apiRequest('GET', '/api/v1/slow');
    await new Promise((r) => setTimeout(r, 20));
    expect(loadingStore.active).toBe(true);
    release();
    await p;
    expect(loadingStore.active).toBe(false);
    server.use(http.get('*/api/v1/bad', () => HttpResponse.json({}, { status: 500 })));
    await apiRequest('GET', '/api/v1/bad').catch(() => undefined);
    expect(loadingStore.active).toBe(false);
  });

  it('skips the loading indicator for quiet (polling) requests', async () => {
    let release: () => void = () => undefined;
    const gate = new Promise<void>((r) => { release = r; });
    server.use(http.get('*/api/v1/poll', async () => { await gate; return HttpResponse.json({}); }));
    const p = apiRequest('GET', '/api/v1/poll', { quiet: true });
    await new Promise((r) => setTimeout(r, 20));
    expect(loadingStore.active).toBe(false);
    release();
    await p;
  });

  it('keeps loading active until all overlapping requests finish', async () => {
    const gates: (() => void)[] = [];
    server.use(http.get('*/api/v1/o', async () => { await new Promise<void>((r) => gates.push(r)); return HttpResponse.json({}); }));
    const a = apiRequest('GET', '/api/v1/o');
    const b = apiRequest('GET', '/api/v1/o');
    await new Promise((r) => setTimeout(r, 30));
    gates[0]();
    await a;
    expect(loadingStore.active).toBe(true);
    gates[1]();
    await b;
    expect(loadingStore.active).toBe(false);
  });

  it('toQuery drops empty values and encodes the rest', () => {
    expect(toQuery({ a: 'x y', b: '', c: null, d: undefined, e: 0 })).toBe('?a=x+y&e=0');
    expect(toQuery({})).toBe('');
  });
});

describe('api modules', () => {
  it('submit sends the Idempotency-Key and reports a 202 as a fresh accept', async () => {
    seedSession(['SENDER']);
    const seen = capture('/api/v1/messages', 'post', { id: 'm1', status: 'ACCEPTED', channel: 'EMAIL', correlationId: 'c' }, 202);
    const r = await messagesApi.submit({ channel: 'EMAIL', category: 'SERVICE', priority: 'NORMAL', template: { code: 'T', locale: 'en' } }, 'key-1');
    expect(seen[0].headers.get('idempotency-key')).toBe('key-1');
    expect(seen[0].headers.get('content-type')).toContain('application/json');
    expect(r).toMatchObject({ httpStatus: 202, replay: false, body: { id: 'm1' } });
  });

  it('submit recognises an idempotent replay (200 + Idempotent-Replay)', async () => {
    seedSession(['SENDER']);
    server.use(http.post('*/api/v1/messages', () => HttpResponse.json({ id: 'm1', status: 'QUEUED' }, { status: 200, headers: { 'Idempotent-Replay': 'true' } })));
    const r = await messagesApi.submit({ channel: 'EMAIL', category: 'SERVICE', priority: 'NORMAL', template: { code: 'T', locale: 'en' } }, 'key-1');
    expect(r.replay).toBe(true);
    expect(r.httpStatus).toBe(200);
  });

  it('bulk upload posts multipart form data with the file and parameters', async () => {
    seedSession(['SENDER']);
    // jsdom's FormData is not understood by Node's fetch, so inspect what the client hands to fetch instead.
    let sent: { body?: unknown; headers?: Record<string, string> } = {};
    const spy = vi.spyOn(globalThis, 'fetch').mockImplementation((_url, init) => {
      sent = { body: init?.body, headers: init?.headers as Record<string, string> };
      return Promise.resolve(new Response(JSON.stringify({ id: 'j1' }), { status: 200 }));
    });
    await bulkJobsApi.upload(new File(['a,b\n1,2'], 'rows.csv', { type: 'text/csv' }), {
      name: 'n', templateCode: 'TRADE_CONFIRMATION', channel: 'EMAIL', category: 'MARKETING', ratePerSecond: 20,
    });
    spy.mockRestore();
    expect(sent.body).toBeInstanceOf(FormData);
    const form = sent.body as FormData;
    expect((form.get('file') as File).name).toBe('rows.csv');
    expect(form.get('templateCode')).toBe('TRADE_CONFIRMATION');
    expect(form.get('ratePerSecond')).toBe('20');
    // no JSON content type: the browser must set the multipart boundary itself
    expect(Object.keys(sent.headers ?? {}).map((h) => h.toLowerCase())).not.toContain('content-type');
    expect(sent.headers?.['Authorization']).toBe('Bearer header.payload.sig');
  });

  it('template actions pass the locale query and a reject comment', async () => {
    seedSession(['TEMPLATE_APPROVER']);
    const seen = capture('/api/v1/templates/t1/versions/2/reject', 'post');
    await templatesApi.act('t1', 2, 'en-US', 'reject', 'needs work');
    expect(seen[0].url.searchParams.get('locale')).toBe('en-US');
    expect(JSON.parse(seen[0].body)).toEqual({ comment: 'needs work' });
  });

  it('path segments are URL-encoded', async () => {
    seedSession(['SENDER']);
    const seen = capture('/api/v1/messages/a%2Fb');
    await messagesApi.get('a/b');
    expect(seen[0].url.pathname).toBe('/api/v1/messages/a%2Fb');
    clearSession();
  });
});
