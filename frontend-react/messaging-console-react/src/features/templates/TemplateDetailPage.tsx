import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useConfirm } from '../../components/ConfirmDialog';
import { DataTable, type Column } from '../../components/DataTable';
import { ErrorBanner } from '../../components/ErrorBanner';
import { dateOrDash } from '../../components/format';
import { errOf, useAction } from '../../components/hooks';
import { StatusChip } from '../../components/StatusChip';
import { templatesApi, type TemplateAction } from '../../core/api/templates';
import { ACCESS } from '../../core/auth/access';
import { useAuth } from '../../core/auth/session';
import type { AuditEvent, TemplateVersion } from '../../core/models';
import { TemplateEditor, type TemplateEditorValue } from './TemplateEditor';

/**
 * Which workflow buttons to show for a version. UX only: the server enforces roles,
 * the four-eyes rule (approver != author) and state transitions.
 */
export function versionActions(v: TemplateVersion, roles: readonly string[], subject: string): {
  edit: boolean; submit: boolean; approve: boolean; reject: boolean; retire: boolean; fourEyesBlocked: boolean;
} {
  const has = (allowed: readonly string[]): boolean => allowed.some((r) => roles.includes(r));
  const author = has(ACCESS.templateEdit);
  const approver = has(ACCESS.templateApprove);
  const blocked = !!v.createdBy && v.createdBy === subject;
  return {
    edit: author && v.status === 'DRAFT',
    submit: author && v.status === 'DRAFT',
    approve: approver && v.status === 'PENDING_APPROVAL',
    reject: approver && v.status === 'PENDING_APPROVAL',
    retire: has(ACCESS.templateRetire) && v.status === 'PUBLISHED',
    fourEyesBlocked: approver && v.status === 'PENDING_APPROVAL' && blocked,
  };
}

type EditorState = { kind: 'new-version' } | { kind: 'edit'; version: TemplateVersion } | null;

const historyCols: Column<AuditEvent>[] = [
  { key: 'occurredAt', header: 'When', type: 'date' }, { key: 'action', header: 'Action' }, { key: 'actor', header: 'Actor' },
  { key: 'detail', header: 'Detail', value: (e) => (typeof e.detail === 'string' ? e.detail : e.detail ? JSON.stringify(e.detail) : '') },
];

const keyOf = (v: TemplateVersion): string => `${v.locale}#${v.versionNo}`;

export function TemplateDetailPage() {
  const { id = '' } = useParams();
  const { roles, subject, hasAnyRole } = useAuth();
  const confirm = useConfirm();
  const qc = useQueryClient();
  const state = useQuery({ queryKey: ['template', id], queryFn: () => templatesApi.get(id) });
  const history = useQuery({ queryKey: ['template-history', id], queryFn: () => templatesApi.history(id) });
  const save = useAction();
  const act = useAction();
  const [editor, setEditor] = useState<EditorState>(null);
  const [comments, setComments] = useState<Record<string, string>>({});
  const [tab, setTab] = useState<string | null>(null);
  const [saved, setSaved] = useState('');

  const t = state.data;
  const locales = Array.from(new Set((t?.versions ?? []).map((v) => v.locale)));
  const activeLocale = tab && locales.includes(tab) ? tab : locales[0];
  const versions = (t?.versions ?? []).filter((v) => v.locale === activeLocale).sort((a, b) => b.versionNo - a.versionNo);

  const reload = (): void => {
    void qc.invalidateQueries({ queryKey: ['template', id] });
    void qc.invalidateQueries({ queryKey: ['template-history', id] });
  };

  const doSave = async (v: TemplateEditorValue, ed: NonNullable<EditorState>): Promise<void> => {
    const body = { locale: v.locale, subject: v.subject || undefined, body: v.body, variables: v.variables, version: v.version };
    const ok = await save.run(() => (ed.kind === 'edit'
      ? templatesApi.updateVersion(id, ed.version.versionNo, ed.version.locale, body)
      : templatesApi.addVersion(id, body)));
    if (ok) {
      setEditor(null);
      setSaved('Saved.');
      reload();
    }
  };

  const doAct = async (v: TemplateVersion, action: TemplateAction): Promise<void> => {
    if (action === 'approve' || action === 'retire') {
      const text = action === 'approve'
        ? 'Publishing makes this version live and retires the previously published version of this locale.'
        : 'Retired versions can no longer be used for new messages.';
      const ok = await confirm({
        title: `${action === 'approve' ? 'Approve' : 'Retire'} version ${v.versionNo}?`, message: text,
        confirmLabel: action === 'approve' ? 'Approve' : 'Retire', danger: action === 'retire',
      });
      if (!ok) return;
    }
    setSaved('');
    const comment = action === 'reject' ? (comments[keyOf(v)] ?? '').trim() : undefined;
    if (await act.run(() => templatesApi.act(id, v.versionNo, v.locale, action, comment), `Version ${v.versionNo} (${v.locale}): ${action} succeeded.`)) {
      reload();
    }
  };

  return (
    <div className="page">
      <div className="toolbar-row">
        <h1 className="page-title grow">Template <span className="mono">{t?.code ?? id}</span></h1>
        <Link className="btn" to="/templates">Back to list</Link>
        {hasAnyRole(ACCESS.templateAuthor) && !editor ? (
          <button type="button" className="btn" onClick={() => { save.clear(); setEditor({ kind: 'new-version' }); }}>New version / locale</button>
        ) : null}
      </div>
      <ErrorBanner error={errOf(state.error)} onRetry={() => void state.refetch()} />
      <ErrorBanner error={act.error} retryable={false} />
      {act.notice ? <p role="status" className="notice">{act.notice}</p> : null}
      {saved ? <p role="status" className="notice">{saved}</p> : null}
      {state.isLoading ? <p role="status">Loading template…</p> : null}

      {t ? (
        <>
          <p>{t.channel} · {t.category}</p>
          <p className="muted">
            Four-eyes rule: a version is published only after a different user with the approver role approves it. The author of a
            version cannot approve it. The server enforces this; the buttons below only reflect it.
          </p>
          {editor ? (
            <section className="card" aria-label="Version editor">
              <h2>{editor.kind === 'edit' ? `Edit draft v${editor.version.versionNo} (${editor.version.locale})` : 'New draft version'}</h2>
              <TemplateEditor
                key={editor.kind === 'edit' ? keyOf(editor.version) : 'new'}
                mode={editor.kind === 'edit' ? 'edit' : 'version'} fixedChannel={t.channel}
                initial={editor.kind === 'edit' ? editor.version : null} busy={save.busy} error={save.error}
                onSave={(v) => void doSave(v, editor)} onCancel={() => setEditor(null)}
              />
            </section>
          ) : null}
          {locales.length === 0 ? <p className="muted">No versions yet.</p> : null}
          <div className="tabs" role="tablist" aria-label="Locales">
            {locales.map((loc) => (
              <button key={loc} type="button" role="tab" aria-selected={loc === activeLocale} onClick={() => setTab(loc)}>{loc}</button>
            ))}
          </div>
          <div role="tabpanel" aria-label={`Versions (${activeLocale ?? ''})`}>
            {versions.map((v) => {
              const a = versionActions(v, roles, subject);
              const comment = comments[keyOf(v)] ?? '';
              return (
                <section key={v.versionNo} className="card ver" aria-label={`Version ${v.versionNo}`}>
                  <div className="toolbar-row">
                    <strong>Version {v.versionNo}</strong> <StatusChip status={v.status} />
                    <span className="muted">author: {v.createdBy ?? '—'} · approver: {v.approvedBy ?? '—'} · published: {dateOrDash(v.publishedAt)}</span>
                  </div>
                  {v.subject ? <div><strong>Subject:</strong> {v.subject}</div> : null}
                  <pre className="src" aria-label="Template source">{v.body}</pre>
                  <div className="muted">Variables: {v.variables.length ? v.variables.join(', ') : 'none'}</div>
                  {v.rejectionComment ? <div className="muted">Review comment: {v.rejectionComment}</div> : null}
                  <div className="toolbar-row actions">
                    {a.edit ? <button type="button" className="btn" onClick={() => { save.clear(); setEditor({ kind: 'edit', version: v }); }}>Edit</button> : null}
                    {a.submit ? <button type="button" className="btn primary" onClick={() => void doAct(v, 'submit')}>Submit for approval</button> : null}
                    {a.approve ? (
                      <button type="button" className="btn primary" disabled={a.fourEyesBlocked} title={a.fourEyesBlocked ? 'Four-eyes: you authored this version' : undefined} onClick={() => void doAct(v, 'approve')}>Approve</button>
                    ) : null}
                    {a.reject ? (
                      <>
                        <div className="field">
                          <label htmlFor={`rc-${keyOf(v)}`}>Rejection comment</label>
                          <input id={`rc-${keyOf(v)}`} value={comment} onChange={(e) => setComments((c) => ({ ...c, [keyOf(v)]: e.target.value }))} />
                        </div>
                        <button type="button" className="btn" disabled={!comment.trim()} onClick={() => void doAct(v, 'reject')}>Reject</button>
                      </>
                    ) : null}
                    {a.retire ? <button type="button" className="btn" onClick={() => void doAct(v, 'retire')}>Retire</button> : null}
                    {a.fourEyesBlocked ? <span className="muted">You authored this version; another approver must approve it.</span> : null}
                  </div>
                </section>
              );
            })}
          </div>

          <section className="card" aria-label="Change history">
            <h2>Change history</h2>
            <DataTable caption="Template change history" columns={historyCols} rows={history.data ?? []} loading={history.isFetching}
              error={errOf(history.error)} emptyTitle="No history" emptyMessage="" onRetry={() => void history.refetch()} />
          </section>
        </>
      ) : null}
    </div>
  );
}
