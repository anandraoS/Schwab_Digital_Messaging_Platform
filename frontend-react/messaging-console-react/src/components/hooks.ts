import { useCallback, useState } from 'react';
import { toNormalizedError, type NormalizedError } from '../core/http/errorMapper';
import type { PageChange } from './Pager';

/** Zero-based paging state shared by list pages. */
export function usePaging(initialSize = 20): {
  page: number; size: number; onChange: (c: PageChange) => void; reset: () => void;
} {
  const [state, setState] = useState({ page: 0, size: initialSize });
  const onChange = useCallback((c: PageChange) => setState({ page: c.page, size: c.size }), []);
  const reset = useCallback(() => setState((s) => ({ ...s, page: 0 })), []);
  return { ...state, onChange, reset };
}

/** Normalized error of a query/mutation (or null). */
export function errOf(e: unknown): NormalizedError | null {
  return e ? toNormalizedError(e) : null;
}

/** Runs an async action tracking busy/error/notice state; used for button-driven mutations. */
export function useAction(): {
  busy: boolean; error: NormalizedError | null; notice: string;
  run: (fn: () => Promise<unknown>, notice?: string) => Promise<boolean>;
  clear: () => void;
} {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<NormalizedError | null>(null);
  const [notice, setNotice] = useState('');
  const run = useCallback(async (fn: () => Promise<unknown>, done = ''): Promise<boolean> => {
    setBusy(true);
    setError(null);
    setNotice('');
    try {
      await fn();
      setNotice(done);
      return true;
    } catch (e) {
      setError(toNormalizedError(e));
      return false;
    } finally {
      setBusy(false);
    }
  }, []);
  const clear = useCallback(() => { setError(null); setNotice(''); }, []);
  return { busy, error, notice, run, clear };
}
