import { Suspense, useState } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { NAV_ITEMS } from '../core/auth/access';
import { auth, DEFAULT_WRITE_TENANT, useAuth } from '../core/auth/session';
import { useGlobalLoading } from '../core/http/loading';

/**
 * Role-aware navigation shell. Hiding nav entries is a UX nicety only; the backend enforces
 * authorization on every request.
 */
export function Shell() {
  const { subject, roles, tenant, tenantScope, hasAnyRole } = useAuth();
  const loading = useGlobalLoading();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const items = NAV_ITEMS.filter((i) => hasAnyRole(i.roles));

  const logout = (): void => {
    auth.logout();
    qc.clear();
    navigate('/login');
  };

  return (
    <div className="shell">
      <a className="skip" href="#main-content">Skip to content</a>
      <header className="bar">
        <button type="button" className="btn menu-btn" aria-expanded={open} aria-controls="side-nav" onClick={() => setOpen((o) => !o)}>Menu</button>
        <span className="brand">Messaging Operations Console</span>
        <span className="react-badge" title="React console; same backend and contract as the Angular console">React</span>
        <span className="grow" />
        {roles.includes('PLATFORM_ADMIN') ? (
          <label className="scope">
            <span>Tenant scope (X-Tenant-Id)</span>
            <input
              name="tenantScope"
              value={tenantScope}
              onChange={(e) => auth.setTenantScope(e.target.value)}
              placeholder={`reads: all tenants, writes: ${DEFAULT_WRITE_TENANT}`}
            />
          </label>
        ) : null}
        <span className="who" aria-label={`Signed in as ${subject}`}>
          {subject} <small>({roles.join(', ')} · {tenant})</small>
        </span>
        <button type="button" className="btn" onClick={logout}>Sign out</button>
      </header>
      {loading ? <div className="global-loading progress" role="progressbar" aria-label="Loading" /> : null}
      <div className="layout">
        <nav id="side-nav" className={`nav${open ? ' open' : ''}`} aria-label="Main">
          {items.map((i) => (
            <NavLink key={i.path} to={i.path} end={i.path === '/messages'} onClick={() => setOpen(false)}>{i.label}</NavLink>
          ))}
        </nav>
        <main id="main-content" tabIndex={-1}>
          <Suspense fallback={<p role="status" className="page">Loading page…</p>}>
            <Outlet />
          </Suspense>
        </main>
      </div>
    </div>
  );
}
