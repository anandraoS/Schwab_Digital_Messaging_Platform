import { useQuery } from '@tanstack/react-query';
import { BarChart, DonutChart, type ChartDatum } from '../../components/Charts';
import { EmptyState } from '../../components/EmptyState';
import { ErrorBanner } from '../../components/ErrorBanner';
import { errOf } from '../../components/hooks';
import { opsApi } from '../../core/api/ops';
import { MESSAGE_STATUSES, type OpsSummary } from '../../core/models';

export function statusData(s: OpsSummary): ChartDatum[] {
  const by = s.byStatus ?? {};
  const known = MESSAGE_STATUSES.filter((x) => (by[x] ?? 0) > 0).map((x) => ({ label: x, value: by[x] }));
  const extra = Object.keys(by).filter((k) => !(MESSAGE_STATUSES as string[]).includes(k) && by[k] > 0).map((k) => ({ label: k, value: by[k] }));
  return [...known, ...extra];
}

export function channelData(s: OpsSummary): ChartDatum[] {
  return Object.entries(s.byChannel ?? {}).map(([label, value]) => ({ label, value }));
}

export function successPercent(s: OpsSummary): number {
  return Math.round((s.successRate ?? 0) * 1000) / 10;
}

export function DashboardPage() {
  const q = useQuery({ queryKey: ['ops-summary'], queryFn: opsApi.summary });
  const s = q.data;
  const pctValue = s ? successPercent(s) : 0;
  return (
    <div className="page">
      <div className="toolbar-row">
        <h1 className="page-title grow">Dashboard</h1>
        <button type="button" className="btn" onClick={() => void q.refetch()}>Refresh</button>
      </div>
      <ErrorBanner error={errOf(q.error)} onRetry={() => void q.refetch()} />
      {q.isLoading ? <p role="status">Loading summary…</p> : null}
      {s ? (
        s.total === 0 ? (
          <EmptyState title="No messages in the last 24 hours" message="Submit a message to see activity here." />
        ) : (
          <>
            <div className="grid kpis">
              <section className="card" aria-label="Total messages">
                <div className="muted">Messages (last {s.windowHours ?? 24}h)</div><div className="kpi">{s.total}</div>
              </section>
              <section className="card" aria-label="Success rate">
                <div className="muted">Success rate</div><div className="kpi">{pctValue}%</div>
                <div className="meter" role="meter" aria-valuemin={0} aria-valuemax={100} aria-valuenow={pctValue} aria-label="Success rate">
                  <div className="fill" style={{ width: `${pctValue}%` }} />
                </div>
              </section>
              <section className="card" aria-label="p95 dispatch delay">
                <div className="muted">p95 dispatch delay</div>
                <div className="kpi">{s.p95DispatchDelayMs ? s.p95DispatchDelayMs : '—'}<small> ms</small></div>
              </section>
            </div>
            <div className="grid charts">
              <section className="card"><h2>By status</h2><BarChart data={statusData(s)} title="Messages by status" /></section>
              <section className="card"><h2>By channel</h2><DonutChart data={channelData(s)} title="Messages by channel" /></section>
            </div>
          </>
        )
      ) : null}
    </div>
  );
}
