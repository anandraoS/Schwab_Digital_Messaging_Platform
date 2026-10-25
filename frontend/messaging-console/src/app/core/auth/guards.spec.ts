import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { clearSession, seedSession } from '../../testing/test-utils';
import { Role } from '../models/api.models';
import { authGuard, roleGuard } from './guards';

describe('route guards (UX only)', () => {
  function setup(): Router {
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    return TestBed.inject(Router);
  }
  const state = (url: string) => ({ url }) as RouterStateSnapshot;
  const route = (roles?: Role[]) => ({ data: { roles } }) as unknown as ActivatedRouteSnapshot;
  afterEach(() => clearSession());

  it('authGuard redirects anonymous users to /login with returnUrl', () => {
    const router = setup();
    const res = TestBed.runInInjectionContext(() => authGuard(route(), state('/messages?x=1'))) as UrlTree;
    expect(router.serializeUrl(res)).toBe('/login?returnUrl=%2Fmessages%3Fx%3D1');
  });

  it('authGuard allows signed-in users', () => {
    seedSession(['SENDER']);
    setup();
    expect(TestBed.runInInjectionContext(() => authGuard(route(), state('/messages')))).toBeTrue();
  });

  it('roleGuard allows a matching role', () => {
    seedSession(['OPERATOR']);
    setup();
    expect(TestBed.runInInjectionContext(() => roleGuard(route(['OPERATOR', 'PLATFORM_ADMIN']), state('/queues')))).toBeTrue();
  });

  it('roleGuard sends users without the role to /forbidden', () => {
    seedSession(['AUDITOR']);
    const router = setup();
    const res = TestBed.runInInjectionContext(() => roleGuard(route(['PLATFORM_ADMIN']), state('/admin'))) as UrlTree;
    expect(router.serializeUrl(res)).toBe('/forbidden');
  });

  it('roleGuard sends anonymous users to /login', () => {
    const router = setup();
    const res = TestBed.runInInjectionContext(() => roleGuard(route(['SENDER']), state('/x'))) as UrlTree;
    expect(router.serializeUrl(res)).toBe('/login');
  });

  it('roleGuard allows any signed-in user when no roles are specified', () => {
    seedSession(['AUDITOR']);
    setup();
    expect(TestBed.runInInjectionContext(() => roleGuard(route(undefined), state('/x')))).toBeTrue();
  });
});
