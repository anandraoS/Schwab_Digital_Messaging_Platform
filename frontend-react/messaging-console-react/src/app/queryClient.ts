import { QueryClient } from '@tanstack/react-query';

/** Server-state defaults: no automatic retries (errors are shown with a Retry button) and no focus refetch. */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, refetchOnWindowFocus: false, staleTime: 0, gcTime: 5 * 60_000 },
      mutations: { retry: false },
    },
  });
}
