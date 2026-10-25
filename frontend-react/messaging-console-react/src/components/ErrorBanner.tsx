import type { NormalizedError } from '../core/http/errorMapper';

/** Renders a normalized API error with actionable text, field details and the correlation id. */
export function ErrorBanner({ error, retryable = true, onRetry }: { error: NormalizedError | null | undefined; retryable?: boolean; onRetry?: () => void }) {
  if (!error) {
    return null;
  }
  return (
    <div className="banner" role="alert">
      <div className="head">
        <strong>{error.message}</strong>
        {retryable && onRetry ? <button type="button" className="btn" onClick={onRetry}>Retry</button> : null}
      </div>
      {error.serverMessage && error.serverMessage !== error.message ? <div className="srv">{error.serverMessage}</div> : null}
      {error.details.length > 0 ? (
        <ul className="details">
          {error.details.map((d, i) => <li key={i}><code>{d.field}</code>: {d.message}</li>)}
        </ul>
      ) : null}
      <div className="meta mono">
        code={error.code} · status={error.status}
        {error.correlationId ? ` · correlationId=${error.correlationId}` : ''}
        {error.retryAfterSeconds !== undefined ? ` · retry after ${error.retryAfterSeconds}s` : ''}
      </div>
    </div>
  );
}
