import {
  HttpContextToken, HttpErrorResponse, HttpEvent, HttpHandlerFn, HttpInterceptorFn, HttpRequest,
} from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, catchError, finalize, throwError } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { normalizeHttpError } from './error-mapper';
import { LoadingService } from './loading.service';

/** Set on background polling requests so they do not flash the global progress bar. */
export const SKIP_LOADING = new HttpContextToken<boolean>(() => false);

export function newCorrelationId(): string {
  const c = globalThis.crypto as Crypto | undefined;
  if (c?.randomUUID) {
    return c.randomUUID();
  }
  return 'cid-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
}

export const correlationInterceptor: HttpInterceptorFn = (req, next) => {
  if (req.headers.has('X-Correlation-Id')) {
    return next(req);
  }
  return next(req.clone({ setHeaders: { 'X-Correlation-Id': newCorrelationId() } }));
};

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const token = auth.token;
  if (!token || req.url.includes('/dev/token')) {
    return next(req);
  }
  const headers: Record<string, string> = { Authorization: `Bearer ${token}` };
  if (auth.roles().includes('PLATFORM_ADMIN')) {
    const isWrite = !['GET', 'HEAD', 'OPTIONS'].includes(req.method);
    const scope = auth.tenantScope().trim() || (isWrite ? auth.defaultWriteTenant().trim() : '');
    if (scope) {
      headers['X-Tenant-Id'] = scope;
    }
  }
  return next(req.clone({ setHeaders: headers }));
};

export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return next(req).pipe(
    catchError((err: unknown) => {
      if (err instanceof HttpErrorResponse) {
        const normalized = normalizeHttpError(err);
        if (err.status === 401 && !req.url.includes('/dev/token')) {
          auth.logout();
          void router.navigate(['/login'], { queryParams: { reason: 'expired' } });
        }
        return throwError(() => normalized);
      }
      return throwError(() => err);
    }),
  );
};

export const loadingInterceptor: HttpInterceptorFn = (
  req: HttpRequest<unknown>,
  next: HttpHandlerFn,
): Observable<HttpEvent<unknown>> => {
  if (req.context.get(SKIP_LOADING)) {
    return next(req);
  }
  const loading = inject(LoadingService);
  loading.begin();
  return next(req).pipe(finalize(() => loading.end()));
};
