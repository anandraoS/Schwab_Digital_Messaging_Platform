import { Link, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useConfirm } from '../../components/ConfirmDialog';
import { DataTable, type Column } from '../../components/DataTable';
import { ErrorBanner } from '../../components/ErrorBanner';
import { dateOrDash, fmtDate } from '../../components/format';
import { errOf, useAction } from '../../components/hooks';
import { MaskedValue } from '../../components/MaskedValue';
import { StatusChip } from '../../components/StatusChip';
import { Timeline } from '../../components/Timeline';
import { messagesApi } from '../../core/api/messages';
import { ACCESS } from '../../core/auth/access';
import { useAuth } from '../../core/auth/session';
import type { DeliveryEvent, MessageAttempt } from '../../core/models';

export const CANCELLABLE = ['VALIDATED', 'QUEUED', 'RETRY_PENDING'];
export const REQUEUEABLE = ['FAILED', 'DEAD_LETTERED'];

const attemptCols: Column<MessageAttempt>[] = [
  { key: 'attemptNo', header: '#' }, { key: 'channel', header: 'Channel' }, { key: 'providerId', header: 'Provider' },
  { key: 'outcome', header: 'Outcome', type: 'status' }, { key: 'errorCode', header: 'Error' },
  { key: 'latencyMs', header: 'ms' }, { key: 'startedAt', header: 'Started', type: 'date' },
];
const eventCols: Column<DeliveryEvent>[] = [
  { key: 'type', header: 'Type', type: 'status' }, { key: 'providerId', header: 'Provider' },
  { key: 'occurredAt', header: 'Occurred', type: 'date' }, { key: 'applied', header: 'Applied', type: 'bool' },
  { key: 'ignoredReason', header: 'Ignored reason' },
];

export function MessageDetailPage() {
  const { id = '' } = useParams();
  const { hasAnyRole } = useAuth();
  const confirm = useConfirm();
  const qc = useQueryClient();
  const action = useAction();
  const detail = useQuery({ queryKey: ['message', id], queryFn: () => messagesApi.get(id) });
  const history = useQuery({ queryKey: ['message-history', id], queryFn: () => messagesApi.history(id) });
  const m = detail.data;

  const reload = (): void => {
    void qc.invalidateQueries({ queryKey: ['message', id] });
    void qc.invalidateQueries({ queryKey: ['message-history', id] });
  };

  const run = async (title: string, message: string, label: string, call: () => Promise<unknown>, done: string): Promise<void> => {
    action.clear();
    if (!(await confirm({ title, message, confirmLabel: label, danger: true }))) {
      return;
    }
    if (await action.run(call, done)) {
      reload();
    }
  };

  const canCancel = !!m && CANCELLABLE.includes(m.status) && hasAnyRole(ACCESS.cancel);
  const canRequeue = !!m && REQUEUEABLE.includes(m.status) && hasAnyRole(ACCESS.requeue);

  return (
    <div className="page">
      <div className="toolbar-row">
        <h1 className="page-title grow">Message <span className="mono">{id}</span></h1>
        <Link className="btn" to="/messages">Back to list</Link>
        <button type="button" className="btn" onClick={reload}>Refresh</button>
        {canCancel ? (
          <button type="button" className="btn" onClick={() => void run('Cancel message', 'Cancel this message? It will not be delivered.', 'Cancel message', () => messagesApi.cancel(id), 'Message cancelled.')}>Cancel message</button>
        ) : null}
        {canRequeue ? (
          <button type="button" className="btn" onClick={() => void run('Requeue message', 'Put this message back on the queue for another delivery attempt?', 'Requeue', () => messagesApi.requeue(id), 'Message requeued.')}>Requeue</button>
        ) : null}
      </div>
      <ErrorBanner error={errOf(detail.error)} onRetry={reload} />
      <ErrorBanner error={action.error} retryable={false} />
      {action.notice ? <p role="status" className="notice">{action.notice}</p> : null}
      {detail.isLoading ? <p role="status">Loading message…</p> : null}
      {m ? (
        <>
          <section className="card" aria-label="Summary">
            <dl className="summary">
              <div><dt>Status</dt><dd><StatusChip status={m.status} /> {m.terminal ? <span className="muted">(terminal)</span> : null}</dd></div>
              {m.statusReason ? <div><dt>Reason</dt><dd>{m.statusReason}</dd></div> : null}
              <div><dt>Channel</dt><dd>{m.channel}{m.channelUsed && m.channelUsed !== m.channel ? ` → ${m.channelUsed} (fallback)` : ''}</dd></div>
              <div><dt>Category / priority</dt><dd>{m.category} / {m.priority}</dd></div>
              <div><dt>Recipient</dt><dd><span className="mono">{m.recipient.externalRef ?? m.recipient.id}</span> <MaskedValue value={m.recipient.maskedAddress} /></dd></div>
              <div><dt>Template</dt><dd>{m.template.code} v{m.template.versionNo ?? '?'} ({m.template.locale ?? '—'})</dd></div>
              <div><dt>Client reference</dt><dd>{m.clientReference ?? '—'}</dd></div>
              <div><dt>Correlation id</dt><dd className="mono">{m.correlationId}</dd></div>
              <div><dt>Idempotency key</dt><dd className="mono">{m.idempotencyKey ?? '—'}</dd></div>
              <div><dt>Attempts</dt><dd>{m.attemptCount} / {m.maxAttempts}</dd></div>
              <div><dt>Next attempt</dt><dd>{dateOrDash(m.nextAttemptAt)}</dd></div>
              <div><dt>Scheduled / expires</dt><dd>{dateOrDash(m.scheduledAt)} / {dateOrDash(m.expiresAt)}</dd></div>
              <div><dt>Created / updated</dt><dd>{fmtDate(m.createdAt)} / {fmtDate(m.updatedAt)}</dd></div>
            </dl>
            <p className="muted">Message content is never returned by the API and is not displayed here.</p>
          </section>

          <div className="cols">
            <section className="card">
              <h2>Lifecycle timeline</h2>
              <ErrorBanner error={errOf(history.error)} onRetry={() => void history.refetch()} />
              {history.isLoading ? <p role="status">Loading timeline…</p> : null}
              {history.data ? (history.data.length ? <Timeline entries={history.data} /> : <p className="muted">No history entries yet.</p>) : null}
            </section>
            <section className="card">
              <h2>Attempts</h2>
              <DataTable caption="Attempts" columns={attemptCols} rows={m.attempts} emptyTitle="No attempts yet" emptyMessage="" />
              <h2>Delivery events</h2>
              <DataTable caption="Delivery events" columns={eventCols} rows={m.deliveryEvents} emptyTitle="No delivery events" emptyMessage="" />
            </section>
          </div>
        </>
      ) : null}
    </div>
  );
}
