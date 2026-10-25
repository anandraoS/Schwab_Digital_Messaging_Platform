import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useConfirm } from '../../components/ConfirmDialog';
import { DataTable, type Column } from '../../components/DataTable';
import { ErrorBanner } from '../../components/ErrorBanner';
import { errOf, useAction, usePaging } from '../../components/hooks';
import { eventsApi } from '../../core/api/events';
import { ACCESS } from '../../core/auth/access';
import { useAuth } from '../../core/auth/session';
import type { DeadLetter } from '../../core/models';

const columns: Column<DeadLetter>[] = [
  { key: 'messageId', header: 'Message id', type: 'mono', link: (r) => `/messages/${encodeURIComponent(r.messageId)}` },
  { key: 'channel', header: 'Channel' }, { key: 'reason', header: 'Reason', type: 'status' },
  { key: 'attemptCount', header: 'Attempts' }, { key: 'deadLetteredAt', header: 'Dead-lettered', type: 'date' },
  { key: 'discarded', header: 'Discarded', type: 'bool' },
];

export function DeadLettersPage() {
  const { hasAnyRole } = useAuth();
  const confirm = useConfirm();
  const qc = useQueryClient();
  const action = useAction();
  const paging = usePaging();
  const q = useQuery({
    queryKey: ['dead-letters', paging.page, paging.size],
    queryFn: () => eventsApi.deadLetters({ page: paging.page, size: paging.size }),
  });
  const canAct = hasAnyRole(ACCESS.deadLetterAct);

  const act = async (row: DeadLetter, title: string, message: string, label: string, danger: boolean, call: () => Promise<unknown>, verb: string): Promise<void> => {
    action.clear();
    if (!(await confirm({ title, message, confirmLabel: label, danger }))) {
      return;
    }
    if (await action.run(call, `Message ${row.messageId} ${verb}.`)) {
      void qc.invalidateQueries({ queryKey: ['dead-letters'] });
    }
  };

  const replay = (row: DeadLetter): Promise<void> => act(
    row, 'Replay message?', `Message ${row.messageId} returns to the queue and will be delivered again.`, 'Replay', false,
    () => eventsApi.replay(row.messageId), 'replayed');
  const discard = (row: DeadLetter): Promise<void> => act(
    row, 'Discard message?', `Message ${row.messageId} stays dead-lettered but is marked discarded (audited). This cannot be undone from the UI.`,
    'Discard', true, () => eventsApi.discard(row.messageId), 'discarded');

  return (
    <div className="page">
      <div className="toolbar-row">
        <h1 className="page-title grow">Dead letters</h1>
        <button type="button" className="btn" onClick={() => void q.refetch()}>Refresh</button>
      </div>
      <ErrorBanner error={action.error} retryable={false} />
      {action.notice ? <p role="status" className="ok">{action.notice}</p> : null}
      <DataTable
        caption="Dead-lettered messages" columns={columns} rows={q.data?.content ?? []} loading={q.isFetching} error={errOf(q.error)}
        total={q.data?.totalElements ?? 0} page={paging.page} size={paging.size} emptyTitle="Dead-letter queue is empty"
        emptyMessage="No messages exhausted their retries." onPageChange={paging.onChange} onRetry={() => void q.refetch()}
        rowActions={canAct ? (row) => (row.discarded ? <span className="muted">discarded</span> : (
          <>
            <button type="button" className="btn" onClick={() => void replay(row)}>Replay</button>
            <button type="button" className="btn" onClick={() => void discard(row)}>Discard</button>
          </>
        )) : undefined}
      />
    </div>
  );
}
