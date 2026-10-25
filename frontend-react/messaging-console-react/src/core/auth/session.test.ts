import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { server } from '../../test/server';
import { clearSession, seedSession } from '../../test/utils';
import { NAV_ITEMS, ACCESS } from './access';
import { PERSONAS, SESSION_KEY, auth, decodeJwtPayload, normalizeRoles } from './session';

function jwt(claims: Record<string, unknown>): string {
  const b64 = (o: unknown) => btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify(o)))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${b64({ alg: 'none' })}.${b64(claims)}.sig`;
}

describe('session', () => {
  it('offers the seven dev personas, same as the Angular console', () => {
    expect(PERSONAS.map((p) => p.id)).toEqual(['platform-admin', 'tenant-admin', 'sender', 'operator', 'template-author', 'template-approver', 'auditor']);
    const bulk = PERSONAS.filter((p) => p.request.scopes?.includes('messages:bulk')).map((p) => p.id);
    expect(bulk).toEqual(['platform-admin', 'tenant-admin', 'sender']);
    expect(PERSONAS.find((p) => p.id === 'platform-admin')?.request.tenant).toBe('*');
  });

  it('strips ROLE_ and drops unknown roles', () => {
    expect(normalizeRoles(['ROLE_OPERATOR', 'SENDER', 'ROLE_NOPE'])).toEqual(['OPERATOR', 'SENDER']);
    expect(normalizeRoles('x')).toEqual([]);
  });

  it('decodes a JWT payload (utf-8 safe) and tolerates garbage', () => {
    expect(decodeJwtPayload(jwt({ sub: 'zoë', roles: ['SENDER'] }))).toMatchObject({ sub: 'zoë' });
    expect(decodeJwtPayload('not-a-jwt')).toBeNull();
    expect(decodeJwtPayload('a.%%%.c')).toBeNull();
  });

  it.each([
    ['access_token', { access_token: '' }],
    ['accessToken', { accessToken: '' }],
    ['token', { token: '' }],
  ])('login accepts the %s response field', async (field, shape) => {
    const token = jwt({ sub: 'sender-retail', roles: ['ROLE_SENDER'], tenant: 'tenant-retail' });
    server.use(http.post('*/dev/token', async ({ request }) => {
      const body = (await request.json()) as { subject: string; roles: string[]; tenant: string; scopes?: string[] };
      expect(body).toEqual({ subject: 'sender-retail', roles: ['SENDER'], tenant: 'tenant-retail', scopes: ['messages:bulk'] });
      return HttpResponse.json({ ...shape, [field]: token, expires_in: 600 });
    }));
    await auth.login(PERSONAS[2].request);
    expect(auth.isAuthenticated()).toBe(true);
    expect(auth.roles).toEqual(['SENDER']);
    expect(auth.subject).toBe('sender-retail');
    expect(auth.tenant).toBe('tenant-retail');
    expect(JSON.parse(sessionStorage.getItem(SESSION_KEY) ?? '{}').token).toBe(token);
    clearSession();
  });

  it('login falls back to the requested roles when the token has none', async () => {
    server.use(http.post('*/dev/token', () => HttpResponse.json({ accessToken: 'opaque-token' })));
    await auth.login(PERSONAS[3].request);
    expect(auth.roles).toEqual(['OPERATOR']);
    expect(auth.subject).toBe('operator-retail');
  });

  it('login rejects when no token comes back', async () => {
    server.use(http.post('*/dev/token', () => HttpResponse.json({})));
    await expect(auth.login(PERSONAS[0].request)).rejects.toThrow(/access token/);
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('logout clears the session, storage and tenant scope', () => {
    seedSession(['PLATFORM_ADMIN']);
    auth.setTenantScope('tenant-x');
    auth.logout();
    expect(auth.isAuthenticated()).toBe(false);
    expect(sessionStorage.getItem(SESSION_KEY)).toBeNull();
    expect(auth.tenantScope).toBe('');
  });

  it('restores an unexpired session from sessionStorage and ignores an expired one', () => {
    seedSession(['AUDITOR']);
    expect(auth.hasAnyRole(['AUDITOR'])).toBe(true);
    sessionStorage.setItem(SESSION_KEY, JSON.stringify({ token: 't', subject: 's', tenant: 'x', roles: ['AUDITOR'], expiresAt: Date.now() - 1 }));
    auth.hydrate();
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.token).toBeNull();
  });

  it('hasAnyRole is false for the wrong role and true for no restriction when signed in', () => {
    seedSession(['OPERATOR']);
    expect(auth.hasAnyRole(['SENDER'])).toBe(false);
    expect(auth.hasAnyRole(undefined)).toBe(true);
    expect(auth.hasAnyRole([])).toBe(true);
    clearSession();
    expect(auth.hasAnyRole(undefined)).toBe(false);
  });
});

describe('role matrix and navigation', () => {
  const navFor = (...roles: string[]) => NAV_ITEMS.filter((i) => !i.roles || i.roles.some((r) => roles.includes(r))).map((i) => i.label);

  it('platform admin sees everything except nothing hidden by role', () => {
    expect(navFor('PLATFORM_ADMIN')).toContain('Admin');
    expect(navFor('PLATFORM_ADMIN')).toContain('Queues');
  });

  it('auditor sees audit and delivery events but not operations pages', () => {
    const n = navFor('AUDITOR');
    expect(n).toContain('Audit history');
    expect(n).toContain('Delivery events');
    expect(n).not.toContain('Queues');
    expect(n).not.toContain('Admin');
    expect(n).not.toContain('New message');
  });

  it('sender can submit and run bulk jobs, but not see dead letters', () => {
    const n = navFor('SENDER');
    expect(n).toContain('New message');
    expect(n).toContain('Bulk jobs');
    expect(n).not.toContain('Dead letters');
  });

  it('template author/approver have the open pages only', () => {
    expect(navFor('TEMPLATE_AUTHOR')).toEqual(['Dashboard', 'Messages', 'Templates']);
    expect(navFor('TEMPLATE_APPROVER')).toEqual(['Dashboard', 'Messages', 'Templates']);
  });

  it('only the approver may approve, authors edit, tenant admins retire', () => {
    expect(ACCESS.templateApprove).toEqual(['TEMPLATE_APPROVER']);
    expect(ACCESS.templateEdit).toEqual(['TEMPLATE_AUTHOR']);
    expect(ACCESS.templateRetire).toContain('TENANT_ADMIN');
  });
});
