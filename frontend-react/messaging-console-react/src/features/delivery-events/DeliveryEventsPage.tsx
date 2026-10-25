import { useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { DataTable, type Column } from '../../components/DataTable';
import { SelectField, TextField } from '../../components/Fields';
import { errOf, usePaging } from '../../components/hooks';
import { eventsApi } from '../../core/api/events';
import { dayBoundary } from '../../core/forms/validators';
import { DELIVERY_EVENT_TYPES, type DeliveryEvent, type DeliveryEventFilter } from '../../core/models';

interface Form { messageId: string; providerId: string; type: string; applied: string; from: string; to: string }
const EMPTY: Form = { messageId: '', providerId: '', type: '', applied: '', from: '', to: '' };

export function buildDeliveryEventFilter(v: Form, page: number, size: number): DeliveryEventFilter {
  return {
    ...v, messageId: v.messageId.trim(), providerId: v.providerId.trim(),
    from: dayBoundary(v.from, false), to: dayBoundary(v.to, true), page, size,
  };
}

const columns: Column<DeliveryEvent>[] = [
  { key: 'occurredAt', header: 'Occurred', type: 'date' },
  { key: 'type', header: 'Type', type: 'status' },
  { key: 'messageId', header: 'Message', type: 'mono', link: (r) => (r.messageId ? `/messages/${encodeURIComponent(r.messageId)}` : null) },
  { key: 'providerId', header: 'Provider' },
  { key: 'providerEventId', header: 'Provider event id', type: 'mono' },
  { key: 'applied', header: 'Applied', type: 'bool' },
  { key: 'ignoredReason', header: 'Ignored reason' },
  { key: 'receivedAt', header: 'Received', type: 'date' },
];

export function DeliveryEventsPage() {
  const [form, setForm] = useState<Form>(EMPTY);
  const [applied, setApplied] = useState<Form>(EMPTY);
  const paging = usePaging();
  const q = useQuery({
    queryKey: ['delivery-events', applied, paging.page, paging.size],
    queryFn: () => eventsApi.deliveryEvents(buildDeliveryEventFilter(applied, paging.page, paging.size)),
  });
  const set = (k: keyof Form) => (e: { target: { value: string } }) => setForm((f) => ({ ...f, [k]: e.target.value }));
  const apply = (e: FormEvent): void => { e.preventDefault(); paging.reset(); setApplied(form); };
  const clear = (): void => { paging.reset(); setForm(EMPTY); setApplied(EMPTY); };

  return (
    <div className="page">
      <h1 className="page-title">Delivery events</h1>
      <form className="filters" onSubmit={apply} aria-label="Delivery event filters">
        <TextField label="Message id" name="messageId" value={form.messageId} onChange={set('messageId')} />
        <TextField label="Provider id" name="providerId" value={form.providerId} onChange={set('providerId')} />
        <SelectField label="Type" name="type" value={form.type} onChange={set('type')} options={DELIVERY_EVENT_TYPES} anyLabel="Any" />
        <div className="field">
          <label htmlFor="applied">Applied</label>
          <select id="applied" name="applied" value={form.applied} onChange={set('applied')}>
            <option value="">Any</option><option value="true">Applied</option><option value="false">Ignored</option>
          </select>
        </div>
        <TextField label="From (UTC date)" name="from" type="date" value={form.from} onChange={set('from')} />
        <TextField label="To (UTC date)" name="to" type="date" value={form.to} onChange={set('to')} />
        <div className="toolbar-row">
          <button type="submit" className="btn primary">Search</button>
          <button type="button" className="btn" onClick={clear}>Clear</button>
        </div>
      </form>
      <DataTable
        caption="Delivery events" columns={columns} rows={q.data?.content ?? []} loading={q.isFetching} error={errOf(q.error)}
        total={q.data?.totalElements ?? 0} page={paging.page} size={paging.size} emptyTitle="No delivery events"
        emptyMessage="No events match the filters." onPageChange={paging.onChange} onRetry={() => void q.refetch()}
      />
    </div>
  );
}
