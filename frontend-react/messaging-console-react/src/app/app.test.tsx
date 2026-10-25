import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { describe, expect, it } from 'vitest';
import { auth, SESSION_KEY } from '../core/auth/session';
import { LoginPage, safeReturnUrl } from '../features/login/LoginPage';
import { server } from '../test/server';
import { json, page, renderApp, renderWithProviders, seedSession } from '../test/utils';

const jwtFor = (claims: Record<string, unknown>): string => {
  const b64 = (o: unknown) => btoa(JSON.stringify(o)).replace(/=+$/, '');
  return `${b64({ alg: 'none' })}.${b64(claims)}.s`;
};

describe('login page', () => {
  it('offers all seven personas with role and tenant', () => {
    renderWithProviders(<LoginPage />, { route: '/login' });
    expect(screen.getAllByRole('listitem')).toHaveLength(7);
    expect(screen.getByRole('button', { name: /Platform admin/ })).toHaveTextContent('PLATFORM_ADMIN · *');
    expect(screen.getByRole('button', { name: /Auditor/ })).toHaveTextContent('AUDITOR · tenant-retail');
  });

  it('signs in through POST /dev/token and stores the session', async () => {
    let body: unknown;
    server.use(http.post('*/dev/token', async ({ request }) => {
      body = await request.json();
      return json({ accessToken: jwtFor({ sub: 'operator-retail', roles: ['ROLE_OPERATOR'], tenant: 'tenant-retail' }), expiresIn: 900 });
    }));
    renderWithProviders(<LoginPage />, { route: '/login' });
    await userEvent.click(screen.getByRole('button', { name: /Operator/ }));
    await waitFor(() => expect(auth.isAuthenticated()).toBe(true));
    expect(body).toEqual({ subject: 'operator-retail', roles: ['OPERATOR'], tenant: 'tenant-retail' });
    expect(JSON.parse(sessionStorage.getItem(SESSION_KEY) ?? '{}').roles).toEqual(['OPERATOR']);
  });

  it('shows an actionable error when the dev token endpoint fails', async () => {
    server.use(http.post('*/dev/token', () => json({ code: 'NOT_FOUND', message: 'dev endpoint disabled' }, { status: 404 })));
    renderWithProviders(<LoginPage />, { route: '/login' });
    await userEvent.click(screen.getByRole('button', { name: /Sender/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('not found');
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('shows the expired-session note', () => {
    renderWithProviders(<LoginPage />, { route: '/login?reason=expired' });
    expect(screen.getByRole('status')).toHaveTextContent('Your session expired');
  });

  it('only follows same-app relative return URLs (no open redirect)', () => {
    expect(safeReturnUrl('/messages?x=1')).toBe('/messages?x=1');
    expect(safeReturnUrl('//evil.test')).toBe('/dashboard');
    expect(safeReturnUrl('/\\evil.test')).toBe('/dashboard');
    expect(safeReturnUrl('https://evil.test')).toBe('/dashboard');
    expect(safeReturnUrl(null)).toBe('/dashboard');
  });
});

describe('route guards and shell', () => {
  const dashboardHandlers = () => server.use(http.get('*/api/v1/ops/summary', () => json({ total: 0, byStatus: {}, byChannel: {}, successRate: 0 })));

  it('redirects an anonymous visitor to /login with a return URL', async () => {
    renderApp('/messages');
    expect(await screen.findByRole('heading', { name: 'Messaging Operations Console' })).toBeInTheDocument();
    expect(screen.getAllByRole('listitem')).toHaveLength(7);
  });

  it('redirects a signed-in user without the role to the forbidden page (UX guard only)', async () => {
    seedSession(['AUDITOR']);
    renderApp('/queues');
    expect(await screen.findByText('Not permitted')).toBeInTheDocument();
  });

  it('lets an allowed role through', async () => {
    seedSession(['OPERATOR']);
    server.use(http.get('*/api/v1/ops/queues', () => json({ depthByPriority: {}, inFlight: 0, consumers: 2, dlqDepth: 0, retryPending: 0, oldestMessageAgeSeconds: 0 })));
    renderApp('/queues');
    expect(await screen.findByRole('heading', { name: 'Queues' })).toBeInTheDocument();
  });

  it('shows only the nav entries for the role', async () => {
    seedSession(['AUDITOR']);
    dashboardHandlers();
    renderApp('/dashboard');
    const nav = await screen.findByRole('navigation', { name: 'Main' });
    expect(within(nav).getAllByRole('link').map((l) => l.textContent)).toEqual(['Dashboard', 'Messages', 'Templates', 'Delivery events', 'Audit history']);
  });

  it('platform admin sees every page and the tenant scope input, which feeds X-Tenant-Id', async () => {
    seedSession(['PLATFORM_ADMIN'], 'platform-admin', '*');
    dashboardHandlers();
    renderApp('/dashboard');
    const nav = await screen.findByRole('navigation', { name: 'Main' });
    expect(within(nav).getAllByRole('link')).toHaveLength(13);
    const scope = screen.getByLabelText(/Tenant scope/);
    await userEvent.type(scope, 'tenant-wealth');
    expect(auth.tenantScope).toBe('tenant-wealth');
  });

  it('non-admins have no tenant scope input', async () => {
    seedSession(['SENDER']);
    dashboardHandlers();
    renderApp('/dashboard');
    await screen.findByRole('navigation', { name: 'Main' });
    expect(screen.queryByLabelText(/Tenant scope/)).not.toBeInTheDocument();
  });

  it('sign out clears the session and returns to the login page', async () => {
    seedSession(['SENDER'], 'sender-retail');
    dashboardHandlers();
    renderApp('/dashboard');
    expect(await screen.findByLabelText('Signed in as sender-retail')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(await screen.findByRole('heading', { name: 'Messaging Operations Console' })).toBeInTheDocument();
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('a 401 from the API signs the user out and shows the expired notice on the login page', async () => {
    seedSession(['SENDER']);
    server.use(http.get('*/api/v1/ops/summary', () => json({ code: 'UNAUTHENTICATED' }, { status: 401 })));
    renderApp('/dashboard');
    expect(await screen.findByText('Your session expired. Please sign in again.')).toBeInTheDocument();
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('shows the global loading indicator while a request is in flight', async () => {
    seedSession(['SENDER']);
    let release: () => void = () => undefined;
    const gate = new Promise<void>((r) => { release = r; });
    server.use(http.get('*/api/v1/ops/summary', async () => { await gate; return json({ total: 0, byStatus: {}, byChannel: {}, successRate: 0 }); }));
    renderApp('/dashboard');
    expect(await screen.findByRole('progressbar', { name: 'Loading' })).toBeInTheDocument();
    release();
    await waitFor(() => expect(screen.queryByRole('progressbar', { name: 'Loading' })).not.toBeInTheDocument());
  });

  it('unknown routes render the not-found page inside the shell', async () => {
    seedSession(['SENDER']);
    renderApp('/definitely/not/here');
    expect(await screen.findByText('Page not found')).toBeInTheDocument();
  });

  it('provides a skip link and a labelled main landmark', async () => {
    seedSession(['SENDER']);
    dashboardHandlers();
    renderApp('/dashboard');
    expect(await screen.findByRole('link', { name: 'Skip to content' })).toHaveAttribute('href', '#main-content');
    expect(screen.getByRole('main')).toHaveAttribute('id', 'main-content');
  });
});

describe('dashboard', () => {
  it('renders KPIs and charts from the ops summary', async () => {
    seedSession(['OPERATOR']);
    server.use(http.get('*/api/v1/ops/summary', () => json({
      windowHours: 24, total: 120, successRate: 0.9375, p95DispatchDelayMs: 0,
      byStatus: { DELIVERED: 100, FAILED: 5, QUEUED: 15, WEIRD_NEW: 0 }, byChannel: { EMAIL: 80, SMS: 40 },
    })));
    renderApp('/dashboard');
    expect(await screen.findByLabelText('Total messages')).toHaveTextContent('120');
    expect(screen.getByLabelText('Success rate', { selector: 'section' })).toHaveTextContent('93.8%');
    expect(screen.getByLabelText('p95 dispatch delay')).toHaveTextContent('—');
    expect(screen.getByRole('img', { name: 'Messages by status' })).toBeInTheDocument();
    expect(screen.getByText(/EMAIL:/)).toHaveTextContent('EMAIL: 80 (66.7%)');
  });

  it('shows an empty state when nothing was sent in the window', async () => {
    seedSession(['OPERATOR']);
    server.use(http.get('*/api/v1/ops/summary', () => json({ total: 0, byStatus: {}, byChannel: {}, successRate: 0 })));
    renderApp('/dashboard');
    expect(await screen.findByText('No messages in the last 24 hours')).toBeInTheDocument();
  });

  it('shows the error banner with a retry that recovers', async () => {
    seedSession(['OPERATOR']);
    let fail = true;
    server.use(http.get('*/api/v1/ops/summary', () => (fail
      ? json({ code: 'SERVICE_UNAVAILABLE', message: 'x', correlationId: 'cc-1' }, { status: 503 })
      : json({ total: 3, byStatus: { DELIVERED: 3 }, byChannel: { SMS: 3 }, successRate: 1 }))));
    renderApp('/dashboard');
    expect(await screen.findByRole('alert')).toHaveTextContent('temporarily unavailable');
    expect(screen.getByRole('alert')).toHaveTextContent('correlationId=cc-1');
    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByLabelText('Total messages')).toHaveTextContent('3');
    expect(page([1]).totalElements).toBe(1);
  });
});
