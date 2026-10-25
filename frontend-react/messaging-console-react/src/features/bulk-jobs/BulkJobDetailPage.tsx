import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useConfirm } from '../../components/ConfirmDialog';
import { DataTable, type Column } from '../../components/DataTable';
import { ErrorBanner } from '../../components/ErrorBanner';
import { dateOrDash } from '../../components/format';
import { errOf, useAction } from '../../components/hooks';
import { StatusChip } from '../../components/StatusChip';
import { bulkJobsApi } from '../../core/api/bulkJobs';
import type { BulkJob, BulkJobFailure, BulkJobStatus } from '../../core/models';

export const POLL_INTERVAL_MS = 2000;
const ACTIVE: BulkJobStatus[] = ['CREATED', 'RUNNING'];

export function isActiveJob(j: Pick<BulkJob, 'status'> | null | undefined): boolean {
  return !!j && ACTIVE.includes(j.status);
}

export function progressPercent(j: Pick<BulkJob, 'processed' | 'total'>): number {
  return j.total > 0 ? Math.min(100, Math.round((j.processed / j.total) * 100)) : 0;
}

const failureCols: Column<BulkJobFailure>[] = [
  { key: 'lineNo', header: 'Line' }, { key: 'reason', header: 'Reason' }, { key: 'rawLineHash', header: 'Row hash', type: 'mono' },
];

export function BulkJobDetailPage({ pollMs = POLL_INTERVAL_MS }: { pollMs?: number }) {
  const { id = '' } = useParams();
  const confirm = useConfirm();
  const qc = useQueryClient();
  const action = useAction();
  const [fPage, setFPage] = useState({ page: 0, size: 20 });

  // Polls (quietly, without the global loading bar) until the job leaves CREATED/RUNNING.
  const jobQ = useQuery({
    queryKey: ['bulk-job', id],
    queryFn: () => bulkJobsApi.get(id, true),
    refetchInterval: (q) => (isActiveJob(q.state.data) || q.state.error ? pollMs : false),
  });
  const job = jobQ.data;
  const active = isActiveJob(job);
  // The failure list is reloaded when the job finishes (key includes `active`).
  const failures = useQuery({
    queryKey: ['bulk-failures', id, fPage.page, fPage.size, active],
    queryFn: () => bulkJobsApi.failures(id, fPage),
  });
  const percent = job ? progressPercent(job) : 0;

  const act = async (call: () => Promise<unknown>): Promise<void> => {
    if (await action.run(call)) {
      void qc.invalidateQueries({ queryKey: ['bulk-job', id] });
    }
  };

  const stop = async (): Promise<void> => {
    action.clear();
    if (await confirm({
      title: 'Stop bulk job?', message: 'Rows already submitted continue through the pipeline. You can restart the job later.',
      confirmLabel: 'Stop', danger: true,
    })) {
      await act(() => bulkJobsApi.stop(id));
    }
  };

  return (
    <div className="page">
      <div className="toolbar-row">
        <h1 className="page-title grow">Bulk job <span className="mono">{id}</span></h1>
        <Link className="btn" to="/bulk-jobs">Back to jobs</Link>
        {job && active ? <button type="button" className="btn" onClick={() => void stop()}>Stop</button> : null}
        {job && ['STOPPED', 'FAILED', 'COMPLETED_WITH_ERRORS'].includes(job.status) ? (
          <button type="button" className="btn" onClick={() => { action.clear(); void act(() => bulkJobsApi.restart(id)); }}>Restart</button>
        ) : null}
      </div>
      <ErrorBanner error={errOf(jobQ.error)} onRetry={() => void jobQ.refetch()} />
      <ErrorBanner error={action.error} retryable={false} />
      {job ? (
        <>
          <section className="card" aria-label="Progress">
            <div className="toolbar-row">
              <strong>{job.name}</strong> <StatusChip status={job.status} />
              {active ? <span className="muted" role="status">auto-refreshing every {pollMs / 1000}s</span> : null}
            </div>
            <progress className="bulk" max={100} value={percent} aria-label={`Progress ${percent} percent`} />
            <p>{job.processed} / {job.total} processed ({percent}%) · succeeded {job.succeeded} · failed {job.failed} · duplicates {job.duplicate}</p>
            <p className="muted">
              Started {dateOrDash(job.startedAt)} · finished {dateOrDash(job.finishedAt)}{job.ratePerSecond ? ` · ${job.ratePerSecond}/s` : ''}
            </p>
          </section>
          <h2>Failures</h2>
          <DataTable
            caption="Bulk job failures" columns={failureCols} rows={failures.data?.content ?? []} loading={failures.isFetching}
            error={errOf(failures.error)} total={failures.data?.totalElements ?? 0} page={fPage.page} size={fPage.size}
            emptyTitle="No failed rows" emptyMessage="Rejected rows appear here with a reason."
            onPageChange={setFPage} onRetry={() => void failures.refetch()}
          />
        </>
      ) : !jobQ.error ? <p role="status">Loading job…</p> : null}
    </div>
  );
}
