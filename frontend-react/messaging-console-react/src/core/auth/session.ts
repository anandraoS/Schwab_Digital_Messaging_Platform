import { useSyncExternalStore } from 'react';
import { apiRequest } from '../http/client';
import type { DevTokenRequest, DevTokenResponse, Persona, Role, Session } from '../models';

export const SESSION_KEY = 'mc.session';

interface RawTokenResponse {
  access_token?: string;
  accessToken?: string;
  token?: string;
  expires_in?: number;
  expiresIn?: number;
}

/** Dev personas offered on the login page (identical to the Angular console). Roles are sent without ROLE_. */
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
    const bytes = Uint8Array.from(atob(padded), (c) => c.charCodeAt(0));
    return JSON.parse(new TextDecoder().decode(bytes)) as Record<string, unknown>;
  } catch {
    return null;
  }
}

/** Platform-admin writes must target exactly one tenant; used when no tenant scope is typed in. */
export const DEFAULT_WRITE_TENANT = 'tenant-retail';

function restore(): Session | null {
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

interface State {
  session: Session | null;
  /** Optional tenant scope header for platform admins (X-Tenant-Id). */
  tenantScope: string;
  /** True after a 401 cleared the session (login page shows "session expired"). */
  expired: boolean;
}

let state: State = { session: restore(), tenantScope: '', expired: false };
const listeners = new Set<() => void>();

function set(next: Partial<State>): void {
  state = { ...state, ...next };
  listeners.forEach((l) => l());
}

export const auth = {
  subscribe(l: () => void): () => void {
    listeners.add(l);
    return () => listeners.delete(l);
  },
  getState: (): State => state,
  get session(): Session | null {
    return state.session;
  },
  isAuthenticated(): boolean {
    return !!state.session && state.session.expiresAt > Date.now();
  },
  /** Bearer token, or null when signed out / expired. */
  get token(): string | null {
    return auth.isAuthenticated() ? state.session!.token : null;
  },
  get roles(): Role[] {
    return state.session?.roles ?? [];
  },
  get subject(): string {
    return state.session?.subject ?? '';
  },
  get tenant(): string {
    return state.session?.tenant ?? '';
  },
  get tenantScope(): string {
    return state.tenantScope;
  },
  setTenantScope(scope: string): void {
    set({ tenantScope: scope });
  },
  /** UX-only check. The server enforces authorization; this just hides controls that would 403. */
  hasAnyRole(allowed?: readonly Role[]): boolean {
    if (!allowed || allowed.length === 0) {
      return auth.isAuthenticated();
    }
    return allowed.some((r) => state.session?.roles.includes(r) ?? false);
  },
  async login(request: DevTokenRequest): Promise<DevTokenResponse> {
    const raw = await apiRequest<RawTokenResponse>('POST', '/dev/token', { body: request, anonymous: true });
    // Accept snake_case (documented), camelCase (Jackson default) and a bare `token` property.
    const res: DevTokenResponse = {
      access_token: raw.access_token ?? raw.accessToken ?? raw.token ?? '',
      expires_in: raw.expires_in ?? raw.expiresIn ?? 3600,
    };
    if (!res.access_token) {
      throw new Error('The token endpoint did not return an access token.');
    }
    establish(res, request);
    return res;
  },
  /** Re-reads the session from sessionStorage (e.g. after another component wrote it). */
  hydrate(): void {
    set({ session: restore(), expired: false });
  },
  /** Clears the session because the server rejected the token (401). */
  expire(): void {
    auth.logout();
    set({ expired: true });
  },
  logout(): void {
    try {
      sessionStorage.removeItem(SESSION_KEY);
    } catch {
      /* storage unavailable */
    }
    set({ session: null, tenantScope: '', expired: false });
  },
};

function establish(res: DevTokenResponse, request: DevTokenRequest): void {
  const claims = decodeJwtPayload(res.access_token) ?? {};
  const roles = normalizeRoles(claims['roles']);
  const session: Session = {
    token: res.access_token,
    subject: String(claims['sub'] ?? request.subject),
    tenant: String(claims['tenant'] ?? request.tenant),
    roles: roles.length ? roles : normalizeRoles(request.roles),
    expiresAt: Date.now() + Math.max(1, res.expires_in) * 1000,
  };
  try {
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(session));
  } catch {
    /* storage unavailable: session stays in memory only */
  }
  set({ session, expired: false });
}

/** React binding: re-renders on sign-in/out and tenant-scope change. */
export function useAuth(): {
  session: Session | null; isAuthenticated: boolean; roles: Role[]; subject: string; tenant: string;
  tenantScope: string; expired: boolean; hasAnyRole: (allowed?: readonly Role[]) => boolean;
} {
  const s = useSyncExternalStore(auth.subscribe, auth.getState);
  const authed = !!s.session && s.session.expiresAt > Date.now();
  const roles = authed ? s.session!.roles : [];
  return {
    session: s.session,
    isAuthenticated: authed,
    roles,
    subject: s.session?.subject ?? '',
    tenant: s.session?.tenant ?? '',
    tenantScope: s.tenantScope,
    expired: s.expired,
    hasAnyRole: (allowed) => (!allowed || allowed.length === 0 ? authed : allowed.some((r) => roles.includes(r))),
  };
}
