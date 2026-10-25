import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ErrorBanner } from '../../components/ErrorBanner';
import { auth, PERSONAS } from '../../core/auth/session';
import { toNormalizedError, type NormalizedError } from '../../core/http/errorMapper';
import type { Persona } from '../../core/models';

/** Only allow same-app relative redirects (avoid open redirects). */
export function safeReturnUrl(returnUrl: string | null): string {
  return returnUrl && returnUrl.startsWith('/') && !returnUrl.startsWith('//') && !returnUrl.startsWith('/\\') ? returnUrl : '/dashboard';
}

/** Dev persona picker. Calls POST /dev/token (local/test profiles only on the backend). */
export function LoginPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<NormalizedError | null>(null);
  const expired = params.get('reason') === 'expired';

  const signIn = async (p: Persona): Promise<void> => {
    setBusy(true);
    setError(null);
    try {
      await auth.login(p.request);
      navigate(safeReturnUrl(params.get('returnUrl')), { replace: true });
    } catch (e) {
      setError(toNormalizedError(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="login-wrap">
      <h1>Messaging Operations Console</h1>
      <p className="muted">
        Development sign-in: pick a persona. A signed dev token is requested from <code>/dev/token</code> and kept in memory and
        sessionStorage. This page does not exist in production.
      </p>
      {expired ? <p role="status" className="muted">Your session expired. Please sign in again.</p> : null}
      <ErrorBanner error={error} retryable={false} />
      <ul className="personas" aria-label="Dev personas">
        {PERSONAS.map((p) => (
          <li key={p.id}>
            <button type="button" className="btn persona" disabled={busy} data-persona={p.id} onClick={() => void signIn(p)}>
              <span className="label">{p.label}</span>
              <span className="desc muted">{p.description}</span>
              <span className="mono">{p.request.roles.join(', ')} · {p.request.tenant}</span>
            </button>
          </li>
        ))}
      </ul>
    </main>
  );
}
