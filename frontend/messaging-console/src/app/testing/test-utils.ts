import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { provideRouter } from '@angular/router';
import { Role, Session } from '../core/models/api.models';
import { SESSION_KEY } from '../core/auth/auth.service';

/** Seeds sessionStorage so a freshly created AuthService starts signed in with the given roles. */
export function seedSession(roles: Role[], subject = 'tester', tenant = 'tenant-retail'): void {
  const s: Session = { token: 'header.payload.sig', subject, tenant, roles, expiresAt: Date.now() + 3_600_000 };
  sessionStorage.setItem(SESSION_KEY, JSON.stringify(s));
}

/** Providers shared by component specs: router + HttpClient with the testing backend (no interceptors). */
export function testProviders(): (EnvironmentProviders | Provider)[] {
  return [provideRouter([]), provideHttpClient(), provideHttpClientTesting()];
}

export function clearSession(): void {
  sessionStorage.removeItem(SESSION_KEY);
}

export function apiPage<T>(content: T[], extra: Partial<{ page: number; size: number; totalElements: number }> = {}) {
  return {
    content, page: extra.page ?? 0, size: extra.size ?? 20,
    totalElements: extra.totalElements ?? content.length, totalPages: 1, sort: '',
  };
}

/** Builds a fake (unsigned) JWT whose payload is the given claims; for decode tests only. */
export function fakeJwt(claims: Record<string, unknown>): string {
  const enc = (o: unknown): string => btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${enc({ alg: 'none' })}.${enc(claims)}.sig`;
}
