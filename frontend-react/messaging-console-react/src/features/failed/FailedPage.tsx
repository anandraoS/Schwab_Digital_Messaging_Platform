import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useConfirm } from '../../components/ConfirmDialog';
import { DataTable, type Column } from '../../components/DataTable';
import { ErrorBanner } from '../../components/ErrorBanner';
import { errOf, useAction, usePaging } from '../../components/hooks';
import { eventsApi } from '../../core/api/events';
import { messagesApi } from '../../core/api/messages';
import { ACCESS } from '../../core/auth/access';
import { useAuth } from '../../core/auth/session';
import type { FailedMessage } from '../../core/models';

const columns: Column<FailedMessage>[] = [
  { key: 'id', header: 'Message id', type: 'mono', link: (r) => `/messages/${encodeURIComponent(r.id)}` },
  { key: 'status', header: 'Status', type: 'status' }, { key: 'channel', header: 'Channel' },
  { key: 'statusReason', header: 'Reason' }, { key: 'attemptCount', header: 'Attempts' },
  { key: 'nextAttemptAt', header: 'Next attempt', type: 'date' }, { key: 'updatedAt', header: 'Updated', type: 'date' },
];

export function FailedPage() {
  const { hasAnyRole } = useAuth();
  const confirm = useConfirm();
  const qc = useQueryClient();
  const action = useAction();
  const paging = usePaging();
  const q = useQuery({
    queryKey: ['failed', paging.page, paging.size],
    queryFn: () => eventsApi.failed({ page: paging.page, size: paging.size }),
  });
  const canRequeue = hasAnyRole(ACCESS.requeue);

  const requeue = async (row: FailedMessage): Promise<void> => {
    action.clear();
    if (!(await confirm({ title: 'Requeue message?', message: `Message ${row.id} will be queued for another delivery attempt.`, confirmLabel: 'Requeue' }))) {
      return;
    }
    if (await action.run(() => messagesApi.requeue(row.id), `Message ${row.id} requeued.`)) {
      void qc.invalidateQueries({ queryKey: ['failed'] });
    }
  };

  return (
    <div className="page">
      <div className="toolbar-row">
        <h1 className="page-title grow">Failed messages</h1>
        <button type="button" className="btn" onClick={() => void q.refetch()}>Refresh</button>
      </div>
      <p className="muted">
        Investigation list: FAILED, DELIVERY_UNKNOWN and RETRY_PENDING messages. DELIVERY_UNKNOWN is never retried blindly; wait for a
        callback or reconciliation.
      </p>
      <ErrorBanner error={action.error} retryable={false} />
      {action.notice ? <p role="status" className="ok">{action.notice}</p> : null}
      <DataTable
        caption="Failed messages" columns={columns} rows={q.data?.content ?? []} loading={q.isFetching} error={errOf(q.error)}
        total={q.data?.totalElements ?? 0} page={paging.page} size={paging.size} emptyTitle="No failed messages"
        emptyMessage="Everything is flowing." onPageChange={paging.onChange} onRetry={() => void q.refetch()}
        rowActions={(row) => (canRequeue && row.status === 'FAILED'
          ? <button type="button" className="btn" onClick={() => void requeue(row)}>Requeue</button> : null)}
      />
    </div>
  );
}
