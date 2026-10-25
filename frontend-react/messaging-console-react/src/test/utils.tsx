import { render } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AppRoutes } from '../app/routes';
import { createQueryClient } from '../app/queryClient';
import { ConfirmProvider } from '../components/ConfirmDialog';
import { SESSION_KEY, auth } from '../core/auth/session';
import type { Role, Session } from '../core/models';

/** Puts a session into sessionStorage AND the in-memory store (as after a login). */
export function seedSession(roles: Role[], subject = 'tester', tenant = 'tenant-retail'): Session {
  const session: Session = { token: 'header.payload.sig', subject, tenant, roles, expiresAt: Date.now() + 3_600_000 };
  sessionStorage.setItem(SESSION_KEY, JSON.stringify(session));
  auth.hydrate();
  return session;
}

export function clearSession(): void {
  auth.logout();
}

/** Renders `ui` inside Query + Router + Confirm providers at `route`. */
export function renderWithProviders(ui: ReactElement, { route = '/', path = '*' }: { route?: string; path?: string } = {}) {
  const client = createQueryClient();
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[route]}>
        <ConfirmProvider>
          <Routes>
            <Route path={path} element={ui} />
            <Route path="*" element={<div data-testid="elsewhere" />} />
          </Routes>
        </ConfirmProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** Renders the whole route table (guards, shell, lazy pages) at `route`. */
export function renderApp(route: string) {
  const client = createQueryClient();
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[route]}>
        <ConfirmProvider>
          <AppRoutes />
        </ConfirmProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** JSON response helper for MSW handlers. */
export const json = (body: unknown, init: ResponseInit = {}): Response =>
  new Response(JSON.stringify(body), { ...init, headers: { 'Content-Type': 'application/json', ...(init.headers ?? {}) } });

export const page = <T,>(content: T[], size = 20, pageNo = 0, total = content.length) => ({
  content, page: pageNo, size, totalElements: total, totalPages: Math.max(1, Math.ceil(total / size)),
});
