import { HttpClient, HttpContext, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { clearSession, seedSession } from '../../testing/test-utils';
import { isNormalizedError, NormalizedError } from './error-mapper';
import {
  SKIP_LOADING, authInterceptor, correlationInterceptor, errorInterceptor, loadingInterceptor, newCorrelationId,
} from './interceptors';
import { LoadingService } from './loading.service';

describe('HTTP interceptors', () => {
  let http: HttpClient;
  let ctrl: HttpTestingController;

  function setup(): void {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([correlationInterceptor, authInterceptor, loadingInterceptor, errorInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    ctrl = TestBed.inject(HttpTestingController);
  }

  afterEach(() => {
    clearSession();
  });

  it('adds a generated X-Correlation-Id to each request', () => {
    setup();
    http.get('/api/v1/ping').subscribe();
    const req = ctrl.expectOne('/api/v1/ping');
    expect(req.request.headers.get('X-Correlation-Id')).toMatch(/.{8,}/);
    req.flush({});
  });

  it('keeps a caller-provided correlation id', () => {
    setup();
    http.get('/api/v1/ping', { headers: { 'X-Correlation-Id': 'mine' } }).subscribe();
    const req = ctrl.expectOne('/api/v1/ping');
    expect(req.request.headers.get('X-Correlation-Id')).toBe('mine');
    req.flush({});
  });

  it('generates distinct correlation ids', () => {
    expect(newCorrelationId()).not.toBe(newCorrelationId());
  });

  it('adds the bearer token when signed in', () => {
    seedSession(['SENDER']);
    setup();
    http.get('/api/v1/messages').subscribe();
    const req = ctrl.expectOne('/api/v1/messages');
    expect(req.request.headers.get('Authorization')).toBe('Bearer header.payload.sig');
    req.flush({});
  });

  it('sends no Authorization header when signed out', () => {
    setup();
    http.get('/api/v1/messages').subscribe();
    const req = ctrl.expectOne('/api/v1/messages');
    expect(req.request.headers.has('Authorization')).toBeFalse();
    req.flush({});
  });

  it('never attaches the token to /dev/token', () => {
    seedSession(['SENDER']);
    setup();
    http.post('/dev/token', {}).subscribe();
    const req = ctrl.expectOne('/dev/token');
    expect(req.request.headers.has('Authorization')).toBeFalse();
    req.flush({});
  });

  it('adds X-Tenant-Id only for platform admins with a scope set', () => {
    seedSession(['PLATFORM_ADMIN'], 'admin', '*');
    setup();
    TestBed.inject(AuthService).tenantScope.set('tenant-wealth');
    http.get('/api/v1/messages').subscribe();
    const req = ctrl.expectOne('/api/v1/messages');
    expect(req.request.headers.get('X-Tenant-Id')).toBe('tenant-wealth');
    req.flush({});
  });

  it('platform admin without a scope: reads are cross-tenant, writes carry the default write tenant', () => {
    seedSession(['PLATFORM_ADMIN'], 'admin', '*');
    setup();
    http.get('/api/v1/messages').subscribe();
    const read = ctrl.expectOne('/api/v1/messages');
    expect(read.request.headers.has('X-Tenant-Id')).toBeFalse();
    read.flush({});
    http.post('/api/v1/messages', {}).subscribe();
    const write = ctrl.expectOne('/api/v1/messages');
    expect(write.request.headers.get('X-Tenant-Id')).toBe('tenant-retail');
    write.flush({});
  });

  it('does not add X-Tenant-Id for non-admins even if a scope is set', () => {
    seedSession(['OPERATOR']);
    setup();
    TestBed.inject(AuthService).tenantScope.set('tenant-wealth');
    http.get('/api/v1/messages').subscribe();
    const req = ctrl.expectOne('/api/v1/messages');
    expect(req.request.headers.has('X-Tenant-Id')).toBeFalse();
    req.flush({});
  });

  it('normalizes error responses into NormalizedError', (done) => {
    setup();
    http.get('/api/v1/x').subscribe({
      error: (e: unknown) => {
        expect(isNormalizedError(e)).toBeTrue();
        expect((e as NormalizedError).code).toBe('FORBIDDEN');
        done();
      },
    });
    ctrl.expectOne('/api/v1/x').flush({ code: 'FORBIDDEN', message: 'no' }, { status: 403, statusText: 'Forbidden' });
  });

  it('on 401 signs out and redirects to /login', () => {
    seedSession(['SENDER']);
    setup();
    const router = TestBed.inject(Router);
    const nav = spyOn(router, 'navigate').and.resolveTo(true);
    http.get('/api/v1/x').subscribe({ error: () => undefined });
    ctrl.expectOne('/api/v1/x').flush({ code: 'UNAUTHENTICATED' }, { status: 401, statusText: 'Unauthorized' });
    expect(TestBed.inject(AuthService).isAuthenticated()).toBeFalse();
    expect(nav).toHaveBeenCalled();
    expect((nav.calls.mostRecent().args[0] as string[])[0]).toBe('/login');
  });

  it('toggles the global loading indicator around a request', () => {
    setup();
    const loading = TestBed.inject(LoadingService);
    http.get('/api/v1/x').subscribe();
    expect(loading.active()).toBeTrue();
    ctrl.expectOne('/api/v1/x').flush({});
    expect(loading.active()).toBeFalse();
  });

  it('keeps loading active until all overlapping requests finish', () => {
    setup();
    const loading = TestBed.inject(LoadingService);
    http.get('/a').subscribe();
    http.get('/b').subscribe();
    ctrl.expectOne('/a').flush({});
    expect(loading.active()).toBeTrue();
    ctrl.expectOne('/b').flush({});
    expect(loading.active()).toBeFalse();
  });

  it('skips the loading indicator for quiet (polling) requests', () => {
    setup();
    const loading = TestBed.inject(LoadingService);
    http.get('/poll', { context: new HttpContext().set(SKIP_LOADING, true) }).subscribe();
    expect(loading.active()).toBeFalse();
    ctrl.expectOne('/poll').flush({});
  });

  it('clears loading even when the request fails', () => {
    setup();
    const loading = TestBed.inject(LoadingService);
    http.get('/x').subscribe({ error: () => undefined });
    ctrl.expectOne('/x').flush({}, { status: 500, statusText: 'err' });
    expect(loading.active()).toBeFalse();
  });
});
