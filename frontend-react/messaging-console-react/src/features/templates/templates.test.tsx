import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { describe, expect, it } from 'vitest';
import type { TemplateVersion } from '../../core/models';
import { server } from '../../test/server';
import { json, page, renderWithProviders, seedSession } from '../../test/utils';
import { validateTemplateForm } from './TemplateEditor';
import { TemplateDetailPage, versionActions } from './TemplateDetailPage';
import { TemplateListPage } from './TemplateListPage';

const ver = (over: Partial<TemplateVersion> = {}): TemplateVersion => ({
  versionNo: 1, locale: 'en-US', status: 'DRAFT', subject: 'Hello', body: 'Hi {{who}}', variables: ['who'], createdBy: 'author-retail', version: 0, ...over,
});

describe('versionActions (role/state gating, UX only)', () => {
  it('author may edit and submit drafts only', () => {
    expect(versionActions(ver(), ['TEMPLATE_AUTHOR'], 'author-retail')).toMatchObject({ edit: true, submit: true, approve: false, retire: false });
    expect(versionActions(ver({ status: 'PUBLISHED' }), ['TEMPLATE_AUTHOR'], 'a')).toMatchObject({ edit: false, submit: false });
  });

  it('approver may approve/reject pending versions; four-eyes blocks the author', () => {
    const pending = ver({ status: 'PENDING_APPROVAL' });
    expect(versionActions(pending, ['TEMPLATE_APPROVER'], 'approver-retail')).toMatchObject({ approve: true, reject: true, fourEyesBlocked: false });
    expect(versionActions(pending, ['TEMPLATE_APPROVER'], 'author-retail')).toMatchObject({ approve: true, fourEyesBlocked: true });
  });

  it('approver and tenant admin may retire published versions; others may not', () => {
    const pub = ver({ status: 'PUBLISHED' });
    expect(versionActions(pub, ['TEMPLATE_APPROVER'], 'x').retire).toBe(true);
    expect(versionActions(pub, ['TENANT_ADMIN'], 'x').retire).toBe(true);
    expect(versionActions(pub, ['OPERATOR'], 'x').retire).toBe(false);
  });

  it('roles without template rights get nothing', () => {
    expect(versionActions(ver(), ['AUDITOR'], 'x')).toEqual({ edit: false, submit: false, approve: false, reject: false, retire: false, fourEyesBlocked: false });
  });
});

describe('template form validation', () => {
  it('create requires a valid code, a name, locale and body', () => {
    expect(validateTemplateForm({ code: '', name: '', locale: 'en-US', body: '' }, 'create')).toMatchObject({ code: expect.any(String), name: 'Name is required', body: 'Body is required' });
    expect(validateTemplateForm({ code: 'lower', name: 'n', locale: 'en-US', body: 'b' }, 'create').code).toBeDefined();
    expect(validateTemplateForm({ code: 'TRADE_CONFIRMATION', name: 'n', locale: 'xx_YY', body: 'b' }, 'create').locale).toBeDefined();
    expect(validateTemplateForm({ code: 'TRADE_CONFIRMATION', name: 'n', locale: 'en-US', body: 'b' }, 'create')).toEqual({});
  });

  it('new versions do not need a code or name', () => {
    expect(validateTemplateForm({ code: '', name: '', locale: 'de', body: 'x' }, 'version')).toEqual({});
  });
});

const detailBody = (versions: TemplateVersion[]) => ({ id: 't1', code: 'TRADE_CONFIRMATION', name: 'Trade', channel: 'EMAIL', category: 'TRANSACTIONAL', versions });
function mountDetail(versions: TemplateVersion[]) {
  server.use(
    http.get('*/api/v1/templates/t1', () => json(detailBody(versions))),
    http.get('*/api/v1/templates/t1/history', () => json([{ id: 'a1', actor: 'author-retail', action: 'TEMPLATE_CREATED', entityType: 'TEMPLATE', entityId: 't1', detail: { locale: 'en-US' }, occurredAt: '2030-01-01T00:00:00Z' }])),
  );
  return renderWithProviders(<TemplateDetailPage />, { route: '/templates/t1', path: '/templates/:id' });
}

describe('template detail page workflow', () => {
  it('shows locale tabs, versions with status, the template source as text and the four-eyes explanation', async () => {
    seedSession(['TEMPLATE_AUTHOR'], 'author-retail');
    mountDetail([ver(), ver({ versionNo: 1, locale: 'de', status: 'PUBLISHED', body: '<script>x</script>' })]);
    expect(await screen.findByRole('tab', { name: 'en-US' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: 'de' })).toBeInTheDocument();
    expect(screen.getByText(/Four-eyes rule/)).toBeInTheDocument();
    expect(screen.getByLabelText('Template source')).toHaveTextContent('Hi {{who}}');
    await userEvent.click(screen.getByRole('tab', { name: 'de' }));
    expect(screen.getByLabelText('Template source')).toHaveTextContent('<script>x</script>');
    expect(document.querySelector('script:not([type])')).toBeNull();
    expect(await screen.findByRole('table', { name: 'Template change history' })).toHaveTextContent('TEMPLATE_CREATED');
  });

  it('author submits a draft for approval (no confirm needed) and the page reloads', async () => {
    seedSession(['TEMPLATE_AUTHOR'], 'author-retail');
    let status: TemplateVersion['status'] = 'DRAFT';
    const calls: string[] = [];
    server.use(http.post('*/api/v1/templates/t1/versions/1/submit', ({ request }) => { calls.push(new URL(request.url).search); status = 'PENDING_APPROVAL'; return json({}); }));
    server.use(
      http.get('*/api/v1/templates/t1', () => json(detailBody([ver({ status })]))),
      http.get('*/api/v1/templates/t1/history', () => json([])),
    );
    renderWithProviders(<TemplateDetailPage />, { route: '/templates/t1', path: '/templates/:id' });
    await userEvent.click(await screen.findByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findByText('Version 1 (en-US): submit succeeded.')).toBeInTheDocument();
    expect(calls).toEqual(['?locale=en-US']);
    await waitFor(() => expect(screen.getByLabelText('Version 1')).toHaveTextContent('PENDING APPROVAL'));
    expect(screen.queryByRole('button', { name: 'Submit for approval' })).not.toBeInTheDocument();
  });

  it('the author cannot approve their own version (four-eyes) but another approver can, after confirming', async () => {
    seedSession(['TEMPLATE_APPROVER'], 'author-retail');
    mountDetail([ver({ status: 'PENDING_APPROVAL' })]);
    const approve = await screen.findByRole('button', { name: 'Approve' });
    expect(approve).toBeDisabled();
    expect(screen.getByText(/another approver must approve it/)).toBeInTheDocument();
  });

  it('approve asks for confirmation, then POSTs approve with the locale', async () => {
    seedSession(['TEMPLATE_APPROVER'], 'approver-retail');
    const seen: string[] = [];
    server.use(http.post('*/api/v1/templates/t1/versions/1/approve', ({ request }) => { seen.push(new URL(request.url).search); return json({}); }));
    mountDetail([ver({ status: 'PENDING_APPROVAL' })]);
    await userEvent.click(await screen.findByRole('button', { name: 'Approve' }));
    const dlg = await screen.findByRole('alertdialog', { name: 'Approve version 1?' });
    expect(dlg).toHaveTextContent('retires the previously published version');
    expect(seen).toEqual([]);
    await userEvent.click(within(dlg).getByRole('button', { name: 'Approve' }));
    expect(await screen.findByText('Version 1 (en-US): approve succeeded.')).toBeInTheDocument();
    expect(seen).toEqual(['?locale=en-US']);
  });

  it('reject needs a comment and sends it', async () => {
    seedSession(['TEMPLATE_APPROVER'], 'approver-retail');
    let body: unknown;
    server.use(http.post('*/api/v1/templates/t1/versions/1/reject', async ({ request }) => { body = await request.json(); return json({}); }));
    mountDetail([ver({ status: 'PENDING_APPROVAL' })]);
    const reject = await screen.findByRole('button', { name: 'Reject' });
    expect(reject).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Rejection comment'), 'typo in subject');
    expect(reject).toBeEnabled();
    await userEvent.click(reject);
    await screen.findByText('Version 1 (en-US): reject succeeded.');
    expect(body).toEqual({ comment: 'typo in subject' });
  });

  it('shows the reviewer comment of a rejected draft (rejectionComment)', async () => {
    seedSession(['TEMPLATE_AUTHOR'], 'author-retail');
    mountDetail([ver({ rejectionComment: 'please fix the greeting' })]);
    expect(await screen.findByText('Review comment: please fix the greeting')).toBeInTheDocument();
  });

  it('retire is offered on published versions and needs a confirmation', async () => {
    seedSession(['TENANT_ADMIN'], 'tenant-admin-retail');
    const calls: string[] = [];
    server.use(http.post('*/api/v1/templates/t1/versions/1/retire', () => { calls.push('retire'); return json({}); }));
    mountDetail([ver({ status: 'PUBLISHED' })]);
    await userEvent.click(await screen.findByRole('button', { name: 'Retire' }));
    await userEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Retire' }));
    await waitFor(() => expect(calls).toEqual(['retire']));
  });

  it('read-only roles see no workflow buttons', async () => {
    seedSession(['OPERATOR']);
    mountDetail([ver({ status: 'PENDING_APPROVAL' })]);
    await screen.findByLabelText('Version 1');
    for (const name of ['Approve', 'Reject', 'Retire', 'Submit for approval', 'Edit', 'New version / locale']) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument();
    }
  });

  it('the author edits a draft; the save carries the optimistic-lock version', async () => {
    seedSession(['TEMPLATE_AUTHOR'], 'author-retail');
    let sent: unknown;
    let url = '';
    server.use(http.put('*/api/v1/templates/t1/versions/1', async ({ request }) => { sent = await request.json(); url = request.url; return json({}); }));
    mountDetail([ver({ version: 4 })]);
    await userEvent.click(await screen.findByRole('button', { name: 'Edit' }));
    const body = screen.getByLabelText(/Body template/);
    await userEvent.clear(body);
    await userEvent.type(body, 'Updated {{{{who}}');
    await userEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    expect(await screen.findByText('Saved.')).toBeInTheDocument();
    expect(sent).toMatchObject({ locale: 'en-US', body: 'Updated {{who}}', variables: ['who'], version: 4 });
    expect(new URL(url).searchParams.get('locale')).toBe('en-US');
  });

  it('shows the mapped server error when a workflow action is refused', async () => {
    seedSession(['TEMPLATE_APPROVER'], 'approver-retail');
    server.use(http.post('*/api/v1/templates/t1/versions/1/reject', () => json({ code: 'FORBIDDEN', message: 'four-eyes' }, { status: 403 })));
    mountDetail([ver({ status: 'PENDING_APPROVAL' })]);
    await userEvent.type(await screen.findByLabelText('Rejection comment'), 'no');
    await userEvent.click(screen.getByRole('button', { name: 'Reject' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Your role is not allowed');
  });
});

describe('template list page', () => {
  const summary = { id: 't1', code: 'TRADE_CONFIRMATION', name: 'Trade confirmation', channel: 'EMAIL', category: 'TRANSACTIONAL', latestVersionNo: 2, publishedLocales: ['en-US', 'de'], versionCounts: { PUBLISHED: 1, DRAFT: 1 }, updatedAt: '2030-01-01T00:00:00Z' };

  it('lists templates using versionCounts and publishedLocales', async () => {
    seedSession(['OPERATOR']);
    server.use(http.get('*/api/v1/templates', () => json(page([summary]))));
    renderWithProviders(<TemplateListPage />, { route: '/templates' });
    const table = await screen.findByRole('table', { name: 'Templates' });
    expect(within(table).getByRole('link', { name: 'TRADE_CONFIRMATION' })).toHaveAttribute('href', '/templates/t1');
    expect(table).toHaveTextContent('PUBLISHED 1, DRAFT 1');
    expect(table).toHaveTextContent('en-US, de');
    expect(screen.queryByRole('button', { name: 'New template' })).not.toBeInTheDocument();
  });

  it('an author creates a template including the required name and is taken to its page', async () => {
    seedSession(['TEMPLATE_AUTHOR'], 'author-retail');
    let sent: Record<string, unknown> = {};
    server.use(
      http.get('*/api/v1/templates', () => json(page([]))),
      http.post('*/api/v1/templates', async ({ request }) => { sent = (await request.json()) as Record<string, unknown>; return json({ ...summary, versions: [] }, { status: 201 }); }),
    );
    renderWithProviders(<TemplateListPage />, { route: '/templates', path: '/templates' });
    await userEvent.click(await screen.findByRole('button', { name: 'New template' }));
    await userEvent.click(screen.getByRole('button', { name: 'Create template' }));
    expect(screen.getByText('Name is required')).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Code'), 'UI_TEST');
    await userEvent.type(screen.getByLabelText('Name'), 'UI test');
    await userEvent.type(screen.getByLabelText('Subject'), 'Hello {{{{who}}');
    await userEvent.type(screen.getByLabelText(/Body template/), '<p>Hi {{{{who}}</p>');
    await userEvent.type(screen.getByLabelText(/Allowed variables/), 'who');
    await userEvent.click(screen.getByRole('button', { name: 'Create template' }));
    await screen.findByTestId('elsewhere');
    expect(sent).toEqual({ code: 'UI_TEST', name: 'UI test', channel: 'EMAIL', category: 'TRANSACTIONAL', locale: 'en-US', subject: 'Hello {{who}}', body: '<p>Hi {{who}}</p>', variables: ['who'] });
  });

  it('creation errors (duplicate code) are shown in the editor', async () => {
    seedSession(['TEMPLATE_AUTHOR']);
    server.use(
      http.get('*/api/v1/templates', () => json(page([]))),
      http.post('*/api/v1/templates', () => json({ code: 'VALIDATION_FAILED', message: 'code exists', details: [{ field: 'code', message: 'already exists' }] }, { status: 400 })),
    );
    renderWithProviders(<TemplateListPage />, { route: '/templates' });
    await userEvent.click(await screen.findByRole('button', { name: 'New template' }));
    await userEvent.type(screen.getByLabelText('Code'), 'UI_TEST');
    await userEvent.type(screen.getByLabelText('Name'), 'n');
    await userEvent.type(screen.getByLabelText(/Body template/), 'b');
    await userEvent.click(screen.getByRole('button', { name: 'Create template' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('already exists');
  });

  it('the editor previews with sample data', async () => {
    seedSession(['TEMPLATE_AUTHOR']);
    let sent: Record<string, unknown> = {};
    server.use(
      http.get('*/api/v1/templates', () => json(page([]))),
      http.post('*/api/v1/templates/preview', async ({ request }) => { sent = (await request.json()) as Record<string, unknown>; return json({ subject: 'Hello Ada', body: 'Hi Ada' }); }),
    );
    renderWithProviders(<TemplateListPage />, { route: '/templates' });
    await userEvent.click(await screen.findByRole('button', { name: 'New template' }));
    await userEvent.type(screen.getByLabelText(/Body template/), 'Hi {{{{who}}');
    await userEvent.type(screen.getByLabelText(/Allowed variables/), 'who');
    await userEvent.click(screen.getByText('Preview with sample data'));
    await userEvent.type(screen.getByLabelText(/Sample data/), 'who=Ada');
    await userEvent.click(screen.getByRole('button', { name: 'Render preview' }));
    expect(await screen.findByText('Hi Ada')).toBeInTheDocument();
    expect(sent).toMatchObject({ channel: 'EMAIL', variables: ['who'], sampleData: { who: 'Ada' } });
  });

  it('warns about undeclared placeholders', async () => {
    seedSession(['TEMPLATE_AUTHOR']);
    server.use(http.get('*/api/v1/templates', () => json(page([]))));
    renderWithProviders(<TemplateListPage />, { route: '/templates' });
    await userEvent.click(await screen.findByRole('button', { name: 'New template' }));
    await userEvent.type(screen.getByLabelText(/Body template/), 'Hi {{{{who}} {{{{amount}}');
    expect(screen.getByText('Undeclared placeholders in body: who, amount')).toBeInTheDocument();
  });
});
