import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, map, tap } from 'rxjs';
import { DevTokenRequest, DevTokenResponse, Persona, Role, Session } from '../models/api.models';

export const SESSION_KEY = 'mc.session';

interface RawTokenResponse {
  access_token?: string;
  accessToken?: string;
  expires_in?: number;
  expiresIn?: number;
}

/** Dev personas offered on the login page. Roles are sent without the ROLE_ prefix (see README). */
export const PERSONAS: Persona[] = [
  { id: 'platform-admin', label: 'Platform admin', description: 'All tenants, admin + fault injection',
    request: { subject: 'platform-admin', roles: ['PLATFORM_ADMIN'], tenant: '*', scopes: ['messages:bulk'] } },
  { id: 'tenant-admin', label: 'Tenant admin (retail)', description: 'Manage tenant data, templates, bulk jobs',
    request: { subject: 'tenant-admin-retail', roles: ['TENANT_ADMIN'], tenant: 'tenant-retail', scopes: ['messages:bulk'] } },
  { id: 'sender', label: 'Sender (retail)', description: 'Service client: submit messages and bulk jobs',
    request: { subject: 'sender-retail', roles: ['SENDER'], tenant: 'tenant-retail', scopes: ['messages:bulk'] } },
  { id: 'operator', label: 'Operator', description: 'Queues, failures, dead letters, replay',
    request: { subject: 'operator-retail', roles: ['OPERATOR'], tenant: 'tenant-retail' } },
  { id: 'template-author', label: 'Template author', description: 'Create/edit/submit templates',
    request: { subject: 'author-retail', roles: ['TEMPLATE_AUTHOR'], tenant: 'tenant-retail' } },
  { id: 'template-approver', label: 'Template approver', description: 'Approve/reject/retire templates (four-eyes)',
    request: { subject: 'approver-retail', roles: ['TEMPLATE_APPROVER'], tenant: 'tenant-retail' } },
  { id: 'auditor', label: 'Auditor', description: 'Read-only audit and delivery events',
    request: { subject: 'auditor-retail', roles: ['AUDITOR'], tenant: 'tenant-retail' } },
];

const KNOWN_ROLES: Role[] = [
  'PLATFORM_ADMIN', 'TENANT_ADMIN', 'TEMPLATE_AUTHOR', 'TEMPLATE_APPROVER', 'OPERATOR', 'SENDER', 'AUDITOR',
];

/** Strips an optional ROLE_ prefix and keeps only known roles. */
export function normalizeRoles(raw: unknown): Role[] {
  if (!Array.isArray(raw)) {
    return [];
  }
  return raw
    .map((r) => String(r).replace(/^ROLE_/, ''))
    .filter((r): r is Role => (KNOWN_ROLES as string[]).includes(r));
}

/** Decodes a JWT payload WITHOUT verifying it. For display / UX only; the backend is authoritative. */
export function decodeJwtPayload(token: string): Record<string, unknown> | null {
  try {
    const part = token.split('.')[1];
    if (!part) {
      return null;
    }
    const b64 = part.replace(/-/g, '+').replace(/_/g, '/');
    const padded = b64 + '='.repeat((4 - (b64.length % 4)) % 4);
    const json = decodeURIComponent(
      atob(padded).split('').map((c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0')).join(''),
    );
    return JSON.parse(json) as Record<string, unknown>;
  } catch {
    return null;
  }
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly _session = signal<Session | null>(this.restore());

  readonly session = this._session.asReadonly();
  readonly isAuthenticated = computed(() => {
    const s = this._session();
    return !!s && s.expiresAt > Date.now();
  });
  readonly roles = computed<Role[]>(() => this._session()?.roles ?? []);
  readonly subject = computed(() => this._session()?.subject ?? '');
  readonly tenant = computed(() => this._session()?.tenant ?? '');
  /** Optional tenant scope header for platform admins (X-Tenant-Id). */
  readonly tenantScope = signal<string>('');
  /**
   * Tenant used for platform-admin WRITES (POST/PUT/DELETE) when no scope is typed in: the backend refuses writes
   * without X-Tenant-Id because a write must target exactly one tenant. Reads stay cross-tenant.
   */
  readonly defaultWriteTenant = signal<string>('tenant-retail');

  get token(): string | null {
    return this.isAuthenticated() ? this._session()!.token : null;
  }

  /** UX-only check. The server enforces authorization; this just hides controls that would 403. */
  hasAnyRole(allowed?: readonly Role[]): boolean {
    if (!allowed || allowed.length === 0) {
      return this.isAuthenticated();
    }
    const mine = this.roles();
    return allowed.some((r) => mine.includes(r));
  }

  login(request: DevTokenRequest): Observable<DevTokenResponse> {
    return this.http.post<RawTokenResponse>('/dev/token', request).pipe(
      // Accept both snake_case (documented) and camelCase (Jackson default) response property names.
      map((raw): DevTokenResponse => ({
        access_token: raw.access_token ?? raw.accessToken ?? '',
        expires_in: raw.expires_in ?? raw.expiresIn ?? 3600,
      })),
      tap((res) => this.establish(res, request)),
    );
  }

  logout(): void {
    this._session.set(null);
    this.tenantScope.set('');
    try {
      sessionStorage.removeItem(SESSION_KEY);
    } catch {
      /* storage unavailable */
    }
  }

  private establish(res: DevTokenResponse, request: DevTokenRequest): void {
    const claims = decodeJwtPayload(res.access_token) ?? {};
    const roles = normalizeRoles(claims['roles']);
    const session: Session = {
      token: res.access_token,
      subject: String(claims['sub'] ?? request.subject),
      tenant: String(claims['tenant'] ?? request.tenant),
      roles: roles.length ? roles : normalizeRoles(request.roles),
      expiresAt: Date.now() + Math.max(1, res.expires_in) * 1000,
    };
    this._session.set(session);
    try {
      sessionStorage.setItem(SESSION_KEY, JSON.stringify(session));
    } catch {
      /* storage unavailable: session stays in memory only */
    }
  }

  private restore(): Session | null {
    try {
      const raw = sessionStorage.getItem(SESSION_KEY);
      if (!raw) {
        return null;
      }
      const s = JSON.parse(raw) as Session;
      return s.expiresAt > Date.now() ? s : null;
    } catch {
      return null;
    }
  }
}
