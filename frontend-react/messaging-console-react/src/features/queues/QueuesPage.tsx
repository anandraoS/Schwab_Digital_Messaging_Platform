import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { BarChart, type ChartDatum } from '../../components/Charts';
import { ErrorBanner } from '../../components/ErrorBanner';
import { formatAge } from '../../components/format';
import { errOf } from '../../components/hooks';
import { opsApi } from '../../core/api/ops';
import { PRIORITIES, type QueueStats } from '../../core/models';

export const QUEUE_REFRESH_MS = 5000;

export function depthData(s: QueueStats | undefined): ChartDatum[] {
  const d = s?.depthByPriority ?? {};
  const known = PRIORITIES.map((p) => ({ label: p, value: d[p] ?? 0 }));
  const extra = Object.keys(d).filter((k) => !(PRIORITIES as string[]).includes(k)).map((k) => ({ label: k, value: d[k] }));
  return [...known, ...extra];
}

export function QueuesPage({ refreshMs = QUEUE_REFRESH_MS }: { refreshMs?: number }) {
  const [auto, setAuto] = useState(true);
  // Quiet polling: does not drive the global loading bar. Stops when the page unmounts or auto-refresh is off.
  const q = useQuery({
    queryKey: ['queues'],
    queryFn: () => opsApi.queues(true),
    refetchInterval: auto ? refreshMs : false,
  });
  const s = q.data;
  const updated = q.dataUpdatedAt ? new Date(q.dataUpdatedAt).toLocaleTimeString() : '';

  return (
    <div className="page">
      <div className="toolbar-row">
        <h1 className="page-title grow">Queues</h1>
        <label className="check">
          <input type="checkbox" checked={auto} onChange={(e) => setAuto(e.target.checked)} /> Auto-refresh ({refreshMs / 1000}s)
        </label>
        <button type="button" className="btn" onClick={() => void q.refetch()}>Refresh now</button>
      </div>
      <ErrorBanner error={errOf(q.error)} onRetry={() => void q.refetch()} />
      {s ? (
        <>
          {s.consumersPaused ? <p className="paused" role="status">Consumers are paused.</p> : null}
          <div className="grid kpis">
            <section className="card"><div className="muted">In flight</div><div className="kpi">{s.inFlight}</div></section>
            <section className="card"><div className="muted">Consumers</div><div className="kpi">{s.consumers}</div></section>
            <section className="card"><div className="muted">Retry pending</div><div className="kpi">{s.retryPending}</div></section>
            <section className="card"><div className="muted">Dead-letter depth</div><div className="kpi">{s.dlqDepth}</div><Link to="/dead-letters">View dead letters</Link></section>
            <section className="card"><div className="muted">Oldest message age</div><div className="kpi">{formatAge(s.oldestMessageAgeSeconds)}</div></section>
          </div>
          <section className="card"><h2>Queue depth by priority</h2><BarChart data={depthData(s)} title="Queue depth by priority" /></section>
          <p className="muted">Last updated {updated}</p>
        </>
      ) : !q.error ? <p role="status">Loading queue statistics…</p> : null}
    </div>
  );
}
