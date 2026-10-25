import { auth, DEFAULT_WRITE_TENANT } from '../auth/session';
import { NormalizedError, normalizeFailure, toNormalizedError } from './errorMapper';
import { loadingStore } from './loading';

export const API = '/api/v1';

export function newCorrelationId(): string {
  const c = globalThis.crypto as Crypto | undefined;
  if (c?.randomUUID) {
    return c.randomUUID();
  }
  return 'cid-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
}

/** Builds a query string, dropping null/undefined/empty-string values. */
export function toQuery(obj: object): string {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(obj as Record<string, unknown>)) {
    if (v === null || v === undefined || v === '') {
      continue;
    }
    p.set(k, String(v));
  }
  const s = p.toString();
  return s ? `?${s}` : '';
}

export interface RequestOptions {
  query?: object;
  /** JSON body (objects) or FormData (sent as multipart; the browser sets the boundary). */
  body?: unknown;
  headers?: Record<string, string>;
  /** Quiet requests (background polling) do not drive the global loading indicator. */
  quiet?: boolean;
  /** Never attach credentials (used for /dev/token). */
  anonymous?: boolean;
  signal?: AbortSignal;
}

export interface ApiResponse<T> {
  status: number;
  headers: Headers;
  body: T;
}

/** Called after a 401 on an authenticated request. The app routes to /login because the session is cleared. */
export function handleUnauthorized(): void {
  auth.expire();
}

/**
 * The single fetch wrapper (equivalent of the Angular interceptor chain): correlation id, bearer token,
 * X-Tenant-Id for platform admins, global loading counter, error normalization and 401 sign-out.
 */
export async function apiRequestRaw<T>(method: string, path: string, opts: RequestOptions = {}): Promise<ApiResponse<T>> {
  const headers: Record<string, string> = { Accept: 'application/json', ...opts.headers };
  if (!Object.keys(headers).some((h) => h.toLowerCase() === 'x-correlation-id')) {
    headers['X-Correlation-Id'] = newCorrelationId();
  }
  const token = opts.anonymous ? null : auth.token;
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
    if (auth.roles.includes('PLATFORM_ADMIN')) {
      // Platform admins: reads are cross-tenant unless a scope is typed; writes must target one tenant.
      const isWrite = !['GET', 'HEAD', 'OPTIONS'].includes(method.toUpperCase());
      const scope = auth.tenantScope.trim() || (isWrite ? DEFAULT_WRITE_TENANT : '');
      if (scope) {
        headers['X-Tenant-Id'] = scope;
      }
    }
  }
  let body: BodyInit | undefined;
  if (opts.body instanceof FormData) {
    body = opts.body;
  } else if (opts.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(opts.body);
  }
  const url = new URL(path + toQuery(opts.query ?? {}), window.location.href).toString();

  if (!opts.quiet) {
    loadingStore.begin();
  }
  try {
    let res: Response;
    try {
      res = await fetch(url, { method, headers, body, signal: opts.signal });
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') {
        throw e;
      }
      throw normalizeFailure({ status: 0, body: null });
    }
    const text = await res.text();
    let parsed: unknown = null;
    if (text) {
      try {
        parsed = JSON.parse(text);
      } catch {
        parsed = text; // not JSON (e.g. an HTML gateway page): never rendered, only used for the status fallback
      }
    }
    if (!res.ok) {
      if (res.status === 401 && !opts.anonymous) {
        handleUnauthorized();
      }
      throw normalizeFailure({ status: res.status, body: parsed, headers: res.headers });
    }
    return { status: res.status, headers: res.headers, body: parsed as T };
  } catch (e) {
    if (e instanceof NormalizedError || (e instanceof DOMException && e.name === 'AbortError')) {
      throw e;
    }
    throw toNormalizedError(e);
  } finally {
    if (!opts.quiet) {
      loadingStore.end();
    }
  }
}

export async function apiRequest<T>(method: string, path: string, opts: RequestOptions = {}): Promise<T> {
  return (await apiRequestRaw<T>(method, path, opts)).body;
}

export const get = <T>(path: string, query?: object, opts: RequestOptions = {}): Promise<T> =>
  apiRequest<T>('GET', path, { ...opts, query });
export const post = <T>(path: string, body: unknown = {}, opts: RequestOptions = {}): Promise<T> =>
  apiRequest<T>('POST', path, { ...opts, body });
export const put = <T>(path: string, body: unknown, opts: RequestOptions = {}): Promise<T> =>
  apiRequest<T>('PUT', path, { ...opts, body });
