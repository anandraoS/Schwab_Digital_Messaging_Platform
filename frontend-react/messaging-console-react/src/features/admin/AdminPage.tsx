import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useConfirm } from '../../components/ConfirmDialog';
import { ErrorBanner } from '../../components/ErrorBanner';
import { CheckField, TextField } from '../../components/Fields';
import { errOf, useAction } from '../../components/hooks';
import { opsApi } from '../../core/api/ops';
import { isIntegerInRange } from '../../core/forms/validators';
import type { ProviderHealth } from '../../core/models';

interface RowEdit { enabled?: boolean; rateLimitPerSec?: string; timeoutMs?: string }

export function rowErrors(rate: string, timeout: string): { rate?: string; timeout?: string } {
  return {
    rate: !rate || !isIntegerInRange(rate, 1, 10000) ? '1 to 10000' : undefined,
    timeout: !timeout || !isIntegerInRange(timeout, 100, 120000) ? '100 to 120000' : undefined,
  };
}

function ProviderRow({ p, onSave, busy }: { p: ProviderHealth; onSave: (p: ProviderHealth, v: { enabled: boolean; rate: number; timeout: number }) => void; busy: boolean }) {
  const [e, setE] = useState<RowEdit>({});
  const enabled = e.enabled ?? p.enabled;
  const rate = e.rateLimitPerSec ?? String(p.rateLimitPerSec);
  const timeout = e.timeoutMs ?? String(p.timeoutMs ?? 2000);
  const errs = rowErrors(rate, timeout);
  const invalid = !!(errs.rate || errs.timeout);
  return (
    <div className="var-row" role="group" aria-label={`Configuration of ${p.providerId}`}>
      <strong className="mono" style={{ minWidth: 160 }}>{p.providerId}</strong>
      <CheckField label="Enabled" name={`enabled-${p.providerId}`} checked={enabled} onChange={(ev) => setE({ ...e, enabled: ev.target.checked })} />
      <TextField label="Rate limit /s" name={`rate-${p.providerId}`} type="number" value={rate} error={errs.rate} onChange={(ev) => setE({ ...e, rateLimitPerSec: ev.target.value })} />
      <TextField label="Timeout ms" name={`timeout-${p.providerId}`} type="number" value={timeout} error={errs.timeout} onChange={(ev) => setE({ ...e, timeoutMs: ev.target.value })} />
      <button type="button" className="btn primary" disabled={invalid || busy} onClick={() => onSave(p, { enabled, rate: Number(rate), timeout: Number(timeout) })}>Save</button>
    </div>
  );
}

export function AdminPage() {
  const confirm = useConfirm();
  const action = useAction();
  const providers = useQuery({ queryKey: ['providers'], queryFn: opsApi.providers });

  const save = (p: ProviderHealth, v: { enabled: boolean; rate: number; timeout: number }): void => {
    void action.run(
      () => opsApi.updateProviderConfig(p.providerId, { enabled: v.enabled, rateLimitPerSec: v.rate, timeoutMs: v.timeout }),
      `Provider ${p.providerId} configuration saved.`);
  };

  const consumers = async (pause: boolean): Promise<void> => {
    action.clear();
    const ok = await confirm({
      title: pause ? 'Pause consumers?' : 'Resume consumers?',
      message: pause ? 'No new messages will be dispatched until consumers are resumed.' : 'Dispatch consumers will start taking work again.',
      confirmLabel: pause ? 'Pause' : 'Resume', danger: pause,
    });
    if (ok) {
      await action.run(() => (pause ? opsApi.pauseConsumers() : opsApi.resumeConsumers()), pause ? 'Consumers paused.' : 'Consumers resumed.');
    }
  };

  return (
    <div className="page">
      <h1 className="page-title">Administration</h1>
      <p className="muted">Platform-admin operations. This page is hidden for other roles as a UX convenience; the API enforces PLATFORM_ADMIN.</p>
      <ErrorBanner error={action.error} retryable={false} />
      {action.notice ? <p role="status" className="ok">{action.notice}</p> : null}

      <section className="card">
        <h2>Consumers</h2>
        <p className="muted">Pausing stops dispatch consumers from taking new work; accepted messages stay queued.</p>
        <div className="toolbar-row">
          <button type="button" className="btn primary" onClick={() => void consumers(true)}>Pause consumers</button>
          <button type="button" className="btn" onClick={() => void consumers(false)}>Resume consumers</button>
        </div>
      </section>

      <section className="card">
        <h2>Provider configuration</h2>
        <ErrorBanner error={errOf(providers.error)} onRetry={() => void providers.refetch()} />
        {providers.isLoading ? <p role="status">Loading providers…</p> : null}
        {(providers.data ?? []).map((p) => <ProviderRow key={p.providerId} p={p} busy={action.busy} onSave={save} />)}
      </section>
    </div>
  );
}
