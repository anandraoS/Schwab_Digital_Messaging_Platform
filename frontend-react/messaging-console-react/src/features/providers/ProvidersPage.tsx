import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { EmptyState } from '../../components/EmptyState';
import { ErrorBanner } from '../../components/ErrorBanner';
import { pct } from '../../components/format';
import { errOf, useAction } from '../../components/hooks';
import { StatusChip } from '../../components/StatusChip';
import { opsApi } from '../../core/api/ops';
import { ACCESS } from '../../core/auth/access';
import { useAuth } from '../../core/auth/session';
import { FAULT_MODES, type FaultMode, type ProviderHealth } from '../../core/models';

export function ProvidersPage() {
  const { hasAnyRole } = useAuth();
  const qc = useQueryClient();
  const isAdmin = hasAnyRole(ACCESS.platformAdmin);
  const q = useQuery({ queryKey: ['providers'], queryFn: opsApi.providers });
  const action = useAction();
  const [modes, setModes] = useState<Record<string, FaultMode>>({});
  const [probs, setProbs] = useState<Record<string, string>>({});

  const modeFor = (p: ProviderHealth): FaultMode => modes[p.providerId] ?? ((p.faultMode as FaultMode | undefined) ?? 'NONE');
  const probFor = (p: ProviderHealth): string => probs[p.providerId] ?? String(p.faultProbability ?? 0);

  const apply = async (p: ProviderHealth): Promise<void> => {
    const probability = Math.min(1, Math.max(0, Number(probFor(p)) || 0));
    const mode = modeFor(p);
    if (await action.run(() => opsApi.setFaultMode(p.providerId, { mode, probability }), `Fault mode for ${p.providerId} set to ${mode} (p=${probability}).`)) {
      void qc.invalidateQueries({ queryKey: ['providers'] });
    }
  };

  return (
    <div className="page">
      <div className="toolbar-row">
        <h1 className="page-title grow">Providers</h1>
        <button type="button" className="btn" onClick={() => void q.refetch()}>Refresh</button>
      </div>
      <p className="muted">All providers are simulators in this repository; no real provider is ever called.</p>
      <ErrorBanner error={errOf(q.error)} onRetry={() => void q.refetch()} />
      <ErrorBanner error={action.error} retryable={false} />
      {action.notice ? <p role="status" className="ok">{action.notice}</p> : null}
      {q.isLoading ? <p role="status">Loading providers…</p> : null}
      {q.data ? (
        <>
          {q.data.length === 0 ? <EmptyState title="No providers configured" /> : null}
          <div className="grid">
            {q.data.map((p) => (
              <section key={p.providerId} className="card" aria-label={`Provider ${p.providerId}`}>
                <div className="toolbar-row"><strong className="grow">{p.providerId}</strong> <StatusChip status={p.circuitState} /></div>
                <dl className="kv">
                  <div><dt>Channel</dt><dd>{p.channel}</dd></div>
                  <div><dt>Enabled</dt><dd><StatusChip status={p.enabled ? 'ENABLED' : 'DISABLED'} /></dd></div>
                  <div><dt>Success rate</dt><dd>{pct(p.successRate)}</dd></div>
                  <div><dt>p95 latency</dt><dd>{p.p95LatencyMs > 0 ? `${p.p95LatencyMs} ms` : '— (no calls in the last minutes)'}</dd></div>
                  <div><dt>Rate limit</dt><dd>{p.rateLimitPerSec}/s</dd></div>
                  <div><dt>Fault mode</dt><dd>{p.faultMode ?? 'n/a'}</dd></div>
                  <div><dt>Last error</dt><dd>{p.lastError ?? '—'}</dd></div>
                </dl>
                {isAdmin ? (
                  <fieldset className="fault">
                    <legend>Fault injection (simulator only)</legend>
                    <div className="field">
                      <label htmlFor={`mode-${p.providerId}`}>Mode</label>
                      <select id={`mode-${p.providerId}`} value={modeFor(p)} onChange={(e) => setModes((s) => ({ ...s, [p.providerId]: e.target.value as FaultMode }))}>
                        {FAULT_MODES.map((m) => <option key={m} value={m}>{m}</option>)}
                      </select>
                    </div>
                    <div className="field">
                      <label htmlFor={`prob-${p.providerId}`}>Probability (0-1)</label>
                      <input id={`prob-${p.providerId}`} type="number" step="0.1" min="0" max="1" value={probFor(p)}
                        onChange={(e) => setProbs((s) => ({ ...s, [p.providerId]: e.target.value }))} />
                    </div>
                    <button type="button" className="btn primary" disabled={action.busy} onClick={() => void apply(p)}>Apply</button>
                  </fieldset>
                ) : null}
              </section>
            ))}
          </div>
        </>
      ) : null}
    </div>
  );
}
