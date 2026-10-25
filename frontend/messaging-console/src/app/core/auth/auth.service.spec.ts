import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { clearSession, fakeJwt } from '../../testing/test-utils';
import { AuthService, PERSONAS, SESSION_KEY, decodeJwtPayload, normalizeRoles } from './auth.service';

describe('AuthService', () => {
  let auth: AuthService;
  let ctrl: HttpTestingController;

  beforeEach(() => {
    clearSession();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    auth = TestBed.inject(AuthService);
    ctrl = TestBed.inject(HttpTestingController);
  });
  afterEach(() => clearSession());

  it('offers the seven required personas', () => {
    expect(PERSONAS.map((p) => p.id)).toEqual([
      'platform-admin', 'tenant-admin', 'sender', 'operator', 'template-author', 'template-approver', 'auditor']);
    expect(PERSONAS.find((p) => p.id === 'platform-admin')!.request.tenant).toBe('*');
    expect(PERSONAS.find((p) => p.id === 'tenant-admin')!.request.tenant).toBe('tenant-retail');
  });

  it('bulk-capable personas request the messages:bulk scope', () => {
    for (const id of ['platform-admin', 'tenant-admin', 'sender']) {
      expect(PERSONAS.find((p) => p.id === id)!.request.scopes).toContain('messages:bulk');
    }
  });

  it('posts the persona to /dev/token with the documented body', () => {
    const persona = PERSONAS[2];
    auth.login(persona.request).subscribe();
    const req = ctrl.expectOne('/dev/token');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ subject: 'sender-retail', roles: ['SENDER'], tenant: 'tenant-retail', scopes: ['messages:bulk'] });
    req.flush({ access_token: fakeJwt({ sub: 'sender-retail', roles: ['ROLE_SENDER'], tenant: 'tenant-retail' }), expires_in: 600 });
  });

  it('also accepts camelCase token response properties (Jackson default)', () => {
    auth.login(PERSONAS[3].request).subscribe();
    ctrl.expectOne('/dev/token').flush({ accessToken: fakeJwt({ sub: 'op', roles: ['OPERATOR'], tenant: 't' }), tokenType: 'Bearer', expiresIn: 600 });
    expect(auth.isAuthenticated()).toBeTrue();
    expect(auth.roles()).toEqual(['OPERATOR']);
  });

  it('establishes a session from the token claims and normalizes ROLE_ prefixes', () => {
    auth.login(PERSONAS[0].request).subscribe();
    ctrl.expectOne('/dev/token').flush({ access_token: fakeJwt({ sub: 'admin', roles: ['ROLE_PLATFORM_ADMIN'], tenant: '*' }), expires_in: 600 });
    expect(auth.isAuthenticated()).toBeTrue();
    expect(auth.roles()).toEqual(['PLATFORM_ADMIN']);
    expect(auth.subject()).toBe('admin');
    expect(auth.tenant()).toBe('*');
  });

  it('persists the session in sessionStorage and restores it', () => {
    auth.login(PERSONAS[3].request).subscribe();
    ctrl.expectOne('/dev/token').flush({ access_token: fakeJwt({ sub: 'op', roles: ['OPERATOR'], tenant: 't' }), expires_in: 600 });
    expect(sessionStorage.getItem(SESSION_KEY)).toContain('OPERATOR');
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    expect(TestBed.inject(AuthService).roles()).toEqual(['OPERATOR']);
  });

  it('falls back to requested roles when token claims are unreadable', () => {
    auth.login(PERSONAS[6].request).subscribe();
    ctrl.expectOne('/dev/token').flush({ access_token: 'not-a-jwt', expires_in: 60 });
    expect(auth.roles()).toEqual(['AUDITOR']);
  });

  it('does not restore an expired session', () => {
    sessionStorage.setItem(SESSION_KEY, JSON.stringify({ token: 't', subject: 's', tenant: 'x', roles: ['SENDER'], expiresAt: Date.now() - 1000 }));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    expect(TestBed.inject(AuthService).isAuthenticated()).toBeFalse();
  });

  it('logout clears the session, token and storage', () => {
    auth.login(PERSONAS[1].request).subscribe();
    ctrl.expectOne('/dev/token').flush({ access_token: fakeJwt({ sub: 's', roles: ['TENANT_ADMIN'], tenant: 't' }), expires_in: 60 });
    auth.logout();
    expect(auth.isAuthenticated()).toBeFalse();
    expect(auth.token).toBeNull();
    expect(sessionStorage.getItem(SESSION_KEY)).toBeNull();
  });

  it('hasAnyRole is true when any allowed role matches and false otherwise', () => {
    auth.login(PERSONAS[3].request).subscribe();
    ctrl.expectOne('/dev/token').flush({ access_token: fakeJwt({ sub: 'op', roles: ['OPERATOR'], tenant: 't' }), expires_in: 60 });
    expect(auth.hasAnyRole(['OPERATOR', 'PLATFORM_ADMIN'])).toBeTrue();
    expect(auth.hasAnyRole(['AUDITOR'])).toBeFalse();
    expect(auth.hasAnyRole(undefined)).toBeTrue();
  });

  it('normalizeRoles ignores unknown values and non-arrays', () => {
    expect(normalizeRoles(['ROLE_SENDER', 'ROLE_ROOT', 'AUDITOR'])).toEqual(['SENDER', 'AUDITOR']);
    expect(normalizeRoles('SENDER')).toEqual([]);
  });

  it('decodeJwtPayload handles malformed tokens', () => {
    expect(decodeJwtPayload('abc')).toBeNull();
    expect(decodeJwtPayload(fakeJwt({ sub: 'x' }))?.['sub']).toBe('x');
  });
});
