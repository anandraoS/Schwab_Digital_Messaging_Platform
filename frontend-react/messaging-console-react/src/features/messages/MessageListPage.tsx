import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { DataTable, type Column } from '../../components/DataTable';
import { SelectField, TextField } from '../../components/Fields';
import { errOf, usePaging } from '../../components/hooks';
import { messagesApi } from '../../core/api/messages';
import { ACCESS } from '../../core/auth/access';
import { useAuth } from '../../core/auth/session';
import { dayBoundary } from '../../core/forms/validators';
import { CATEGORIES, CHANNELS, MESSAGE_STATUSES, PRIORITIES, type MessageFilter, type MessageSummary } from '../../core/models';

export interface MessageFilterForm {
  status: string; channel: string; category: string; priority: string;
  recipientId: string; correlationId: string; q: string; from: string; to: string;
}

const EMPTY: MessageFilterForm = {
  status: '', channel: '', category: '', priority: '', recipientId: '', correlationId: '', q: '', from: '', to: '',
};

export function buildMessageFilter(v: MessageFilterForm, page: number, size: number): MessageFilter {
  return {
    status: v.status, channel: v.channel, category: v.category, priority: v.priority,
    recipientId: v.recipientId.trim(), correlationId: v.correlationId.trim(), q: v.q.trim(),
    from: dayBoundary(v.from, false), to: dayBoundary(v.to, true), page, size, sort: 'createdAt,desc',
  };
}

const columns: Column<MessageSummary>[] = [
  { key: 'id', header: 'Message id', type: 'mono', link: (r) => `/messages/${encodeURIComponent(r.id)}` },
  { key: 'status', header: 'Status', type: 'status' },
  { key: 'channel', header: 'Channel' },
  { key: 'channelUsed', header: 'Sent via', value: (r) => r.channelUsed ?? '' },
  { key: 'category', header: 'Category' },
  { key: 'priority', header: 'Priority' },
  { key: 'templateCode', header: 'Template' },
  { key: 'clientReference', header: 'Client ref' },
  { key: 'nextAttemptAt', header: 'Next attempt', type: 'date' },
  { key: 'createdAt', header: 'Created', type: 'date' },
];

export function MessageListPage() {
  const { hasAnyRole } = useAuth();
  const [form, setForm] = useState<MessageFilterForm>(EMPTY);
  const [applied, setApplied] = useState<MessageFilterForm>(EMPTY);
  const paging = usePaging();
  const q = useQuery({
    queryKey: ['messages', applied, paging.page, paging.size],
    queryFn: () => messagesApi.list(buildMessageFilter(applied, paging.page, paging.size)),
  });
  const set = (k: keyof MessageFilterForm) => (e: { target: { value: string } }) => setForm((f) => ({ ...f, [k]: e.target.value }));

  const apply = (e?: FormEvent): void => {
    e?.preventDefault();
    paging.reset();
    setApplied(form);
  };
  const clear = (): void => {
    paging.reset();
    setForm(EMPTY);
    setApplied(EMPTY);
  };

  return (
    <div className="page">
      <div className="toolbar-row">
        <h1 className="page-title grow">Messages</h1>
        {hasAnyRole(ACCESS.submit) ? <Link className="btn primary" to="/messages/new">New message</Link> : null}
      </div>
      <form className="filters" onSubmit={apply} aria-label="Message filters">
        <SelectField label="Status" name="status" value={form.status} onChange={set('status')} options={MESSAGE_STATUSES} anyLabel="Any" />
        <SelectField label="Channel" name="channel" value={form.channel} onChange={set('channel')} options={CHANNELS} anyLabel="Any" />
        <SelectField label="Category" name="category" value={form.category} onChange={set('category')} options={CATEGORIES} anyLabel="Any" />
        <SelectField label="Priority" name="priority" value={form.priority} onChange={set('priority')} options={PRIORITIES} anyLabel="Any" />
        <TextField label="Recipient id" name="recipientId" value={form.recipientId} onChange={set('recipientId')} />
        <TextField label="Correlation id" name="correlationId" value={form.correlationId} onChange={set('correlationId')} />
        <TextField label="Client reference" name="q" value={form.q} onChange={set('q')} />
        <TextField label="From (UTC date)" name="from" type="date" value={form.from} onChange={set('from')} />
        <TextField label="To (UTC date)" name="to" type="date" value={form.to} onChange={set('to')} />
        <div className="toolbar-row">
          <button type="submit" className="btn primary">Apply filters</button>
          <button type="button" className="btn" onClick={clear}>Clear</button>
        </div>
      </form>
      <DataTable
        caption="Messages"
        columns={columns}
        rows={q.data?.content ?? []}
        loading={q.isFetching}
        error={errOf(q.error)}
        total={q.data?.totalElements ?? 0}
        page={paging.page}
        size={paging.size}
        emptyTitle="No messages found"
        emptyMessage="Try clearing filters or submit a new message."
        onPageChange={paging.onChange}
        onRetry={() => void q.refetch()}
      />
    </div>
  );
}
