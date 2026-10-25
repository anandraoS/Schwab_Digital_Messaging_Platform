import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { DataTable, type Column } from '../../components/DataTable';
import { SelectField, TextField } from '../../components/Fields';
import { errOf, useAction, usePaging } from '../../components/hooks';
import { templatesApi } from '../../core/api/templates';
import { ACCESS } from '../../core/auth/access';
import { useAuth } from '../../core/auth/session';
import { CHANNELS, type TemplateSummary } from '../../core/models';
import { TemplateEditor, type TemplateEditorValue } from './TemplateEditor';

const STATUSES = ['DRAFT', 'PENDING_APPROVAL', 'PUBLISHED', 'RETIRED'];

const columns: Column<TemplateSummary>[] = [
  { key: 'code', header: 'Code', link: (r) => `/templates/${encodeURIComponent(r.id)}` },
  { key: 'name', header: 'Name' },
  { key: 'channel', header: 'Channel' },
  { key: 'category', header: 'Category' },
  { key: 'versionCounts', header: 'Versions', value: (r) => Object.entries(r.versionCounts ?? {}).map(([s, n]) => `${s} ${n}`).join(', ') },
  { key: 'latestVersionNo', header: 'Latest version' },
  { key: 'publishedLocales', header: 'Published locales', value: (r) => (r.publishedLocales ?? []).join(', ') },
  { key: 'updatedAt', header: 'Updated', type: 'date' },
];

export function TemplateListPage() {
  const { hasAnyRole } = useAuth();
  const navigate = useNavigate();
  const [form, setForm] = useState({ channel: '', status: '', code: '' });
  const [applied, setApplied] = useState(form);
  const [creating, setCreating] = useState(false);
  const create = useAction();
  const paging = usePaging();
  const q = useQuery({
    queryKey: ['templates', applied, paging.page, paging.size],
    queryFn: () => templatesApi.list({ ...applied, page: paging.page, size: paging.size }),
  });
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm((f) => ({ ...f, [k]: e.target.value }));

  const apply = (e: FormEvent): void => {
    e.preventDefault();
    paging.reset();
    setApplied(form);
  };

  const save = async (v: TemplateEditorValue): Promise<void> => {
    let id = '';
    const ok = await create.run(async () => {
      const d = await templatesApi.create({
        code: v.code, name: v.name.trim(), channel: v.channel, category: v.category, locale: v.locale,
        subject: v.subject || undefined, body: v.body, variables: v.variables,
      });
      id = d.id;
    });
    if (ok) {
      setCreating(false);
      navigate(`/templates/${encodeURIComponent(id)}`);
    }
  };

  return (
    <div className="page">
      <div className="toolbar-row">
        <h1 className="page-title grow">Templates</h1>
        {hasAnyRole(ACCESS.templateAuthor) && !creating ? (
          <button type="button" className="btn primary" onClick={() => setCreating(true)}>New template</button>
        ) : null}
      </div>
      {creating ? (
        <section className="card" aria-label="New template">
          <h2>New template</h2>
          <TemplateEditor mode="create" busy={create.busy} error={create.error} onSave={(v) => void save(v)} onCancel={() => setCreating(false)} />
        </section>
      ) : null}
      <form className="filters" onSubmit={apply} aria-label="Template filters">
        <SelectField label="Channel" name="channel" value={form.channel} onChange={set('channel')} options={CHANNELS} anyLabel="Any" />
        <SelectField label="Status" name="status" value={form.status} onChange={set('status')} options={STATUSES} anyLabel="Any" />
        <TextField label="Code contains" name="code" value={form.code} onChange={set('code')} />
        <button type="submit" className="btn primary">Apply</button>
      </form>
      <DataTable
        caption="Templates" columns={columns} rows={q.data?.content ?? []} loading={q.isFetching} error={errOf(q.error)}
        total={q.data?.totalElements ?? 0} page={paging.page} size={paging.size} emptyTitle="No templates"
        emptyMessage="No template matches the filters." onPageChange={paging.onChange} onRetry={() => void q.refetch()}
      />
    </div>
  );
}
