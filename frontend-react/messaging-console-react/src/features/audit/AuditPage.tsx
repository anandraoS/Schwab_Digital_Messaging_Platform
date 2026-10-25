import { useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { DataTable, type Column } from '../../components/DataTable';
import { TextField } from '../../components/Fields';
import { errOf, usePaging } from '../../components/hooks';
import { eventsApi } from '../../core/api/events';
import { dayBoundary } from '../../core/forms/validators';
import type { AuditEvent, AuditFilter } from '../../core/models';

/** Audit detail may be a string or an object; always render it as plain text. */
export function auditDetailText(e: Pick<AuditEvent, 'detail'>): string {
  const d = e.detail;
  if (d === null || d === undefined) {
    return '';
  }
  return typeof d === 'string' ? d : JSON.stringify(d);
}

interface Form { entityType: string; entityId: string; action: string; actor: string; from: string; to: string }
const EMPTY: Form = { entityType: '', entityId: '', action: '', actor: '', from: '', to: '' };

export function buildAuditFilter(v: Form, page: number, size: number): AuditFilter {
  return {
    entityType: v.entityType.trim(), entityId: v.entityId.trim(), action: v.action.trim(), actor: v.actor.trim(),
    from: dayBoundary(v.from, false), to: dayBoundary(v.to, true), page, size,
  };
}

const columns: Column<AuditEvent>[] = [
  { key: 'occurredAt', header: 'When', type: 'date' }, { key: 'action', header: 'Action' },
  { key: 'actor', header: 'Actor' }, { key: 'actorRole', header: 'Role' }, { key: 'entityType', header: 'Entity' },
  { key: 'entityId', header: 'Entity id', type: 'mono' }, { key: 'correlationId', header: 'Correlation', type: 'mono' },
  { key: 'detail', header: 'Detail', value: (e) => auditDetailText(e) },
];

export function AuditPage() {
  const [form, setForm] = useState<Form>(EMPTY);
  const [applied, setApplied] = useState<Form>(EMPTY);
  const paging = usePaging();
  const q = useQuery({
    queryKey: ['audit', applied, paging.page, paging.size],
    queryFn: () => eventsApi.audit(buildAuditFilter(applied, paging.page, paging.size)),
  });
  const set = (k: keyof Form) => (e: { target: { value: string } }) => setForm((f) => ({ ...f, [k]: e.target.value }));
  const apply = (e: FormEvent): void => { e.preventDefault(); paging.reset(); setApplied(form); };
  const clear = (): void => { paging.reset(); setForm(EMPTY); setApplied(EMPTY); };

  return (
    <div className="page">
      <h1 className="page-title">Audit history</h1>
      <form className="filters" onSubmit={apply} aria-label="Audit filters">
        <TextField label="Entity type" name="entityType" value={form.entityType} onChange={set('entityType')} placeholder="MESSAGE, TEMPLATE…" />
        <TextField label="Entity id" name="entityId" value={form.entityId} onChange={set('entityId')} />
        <TextField label="Action" name="action" value={form.action} onChange={set('action')} />
        <TextField label="Actor" name="actor" value={form.actor} onChange={set('actor')} />
        <TextField label="From (UTC date)" name="from" type="date" value={form.from} onChange={set('from')} />
        <TextField label="To (UTC date)" name="to" type="date" value={form.to} onChange={set('to')} />
        <div className="toolbar-row">
          <button type="submit" className="btn primary">Search</button>
          <button type="button" className="btn" onClick={clear}>Clear</button>
        </div>
      </form>
      <DataTable
        caption="Audit events" columns={columns} rows={q.data?.content ?? []} loading={q.isFetching} error={errOf(q.error)}
        total={q.data?.totalElements ?? 0} page={paging.page} size={paging.size} emptyTitle="No audit events"
        emptyMessage="No events match the filters." onPageChange={paging.onChange} onRetry={() => void q.refetch()}
      />
    </div>
  );
}
