import { useEffect, type ReactNode } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../core/auth/session';
import type { Role } from '../core/models';

/**
 * NOTE: guards are a UX convenience ONLY. They avoid showing pages the user cannot use.
 * Real authorization (roles, tenant isolation) is enforced by the backend on every request.
 */
export function RequireAuth() {
  const { isAuthenticated, expired } = useAuth();
  const loc = useLocation();
  if (!isAuthenticated) {
    const q = new URLSearchParams({ returnUrl: loc.pathname + loc.search });
    if (expired) {
      q.set('reason', 'expired');
    }
    return <Navigate to={`/login?${q.toString()}`} replace />;
  }
  return <Outlet />;
}

/** Route-level role check. UX only (see above). */
export function RequireRole({ roles }: { roles: readonly Role[] }) {
  const { isAuthenticated, hasAnyRole } = useAuth();
  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }
  return hasAnyRole(roles) ? <Outlet /> : <Navigate to="/forbidden" replace />;
}

/** Sets the document title (and keeps it per route) for screen-reader and tab users. */
export function Titled({ title, children }: { title: string; children: ReactNode }) {
  useEffect(() => {
    document.title = `${title} · Messaging Console`;
  }, [title]);
  return <>{children}</>;
}
