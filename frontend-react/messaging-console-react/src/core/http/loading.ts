import { useSyncExternalStore } from 'react';

/** Counts in-flight HTTP requests; drives the global progress bar. Quiet (polling) requests do not count. */
let pending = 0;
const listeners = new Set<() => void>();

function emit(): void {
  listeners.forEach((l) => l());
}

export const loadingStore = {
  begin(): void {
    pending += 1;
    emit();
  },
  end(): void {
    pending = Math.max(0, pending - 1);
    emit();
  },
  get active(): boolean {
    return pending > 0;
  },
  reset(): void {
    pending = 0;
    emit();
  },
  subscribe(l: () => void): () => void {
    listeners.add(l);
    return () => listeners.delete(l);
  },
};

export function useGlobalLoading(): boolean {
  return useSyncExternalStore(loadingStore.subscribe, () => loadingStore.active);
}
