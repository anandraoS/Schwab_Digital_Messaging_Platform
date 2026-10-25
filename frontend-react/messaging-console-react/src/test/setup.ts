import '@testing-library/jest-dom/vitest';
import { cleanup, configure } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { auth } from '../core/auth/session';
import { loadingStore } from '../core/http/loading';
import { server } from './server';

configure({ asyncUtilTimeout: 5000 });
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  cleanup();
  server.resetHandlers();
  auth.logout();
  sessionStorage.clear();
  loadingStore.reset();
});
afterAll(() => server.close());
