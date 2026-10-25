import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { describe, expect, it } from 'vitest';
import { AdminPage } from '../admin/AdminPage';
import { AuditPage, auditDetailText } from '../audit/AuditPage';
import { BulkJobDetailPage, isActiveJob, progressPercent } from '../bulk-jobs/BulkJobDetailPage';
import { BulkJobListPage } from '../bulk-jobs/BulkJobListPage';
import { DeadLettersPage } from '../dead-letters/DeadLettersPage';
import { DeliveryEventsPage } from '../delivery-events/DeliveryEventsPage';
import { FailedPage } from '../failed/FailedPage';
import { ProvidersPage } from '../providers/ProvidersPage';
import { QueuesPage, depthData } from '../queues/QueuesPage';
import { server } from '../../test/server';
import { json, page, renderWithProviders, seedSession } from '../../test/utils';

const queueStats = (over: Record<string, unknown> = {}) => ({
  depthByPriority: { CRITICAL: 1, HIGH: 2, NORMAL: 30, LOW: 4 }, inFlight: 3, consumers: 4, dlqDepth: 2, retryPending: 5, oldestMessageAgeSeconds: 3725, ...over,
});

describe('queues page (polling)', () => {
  it('shows the stats and the priority chart', async () => {
    seedSession(['OPERATOR']);
    server.use(http.get('*/api/v1/ops/queues', () => json(queueStats())));
    renderWithProviders(<QueuesPage />, { route: '/queues' });
    expect(await screen.findByText('1h 2m 5s')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'Queue depth by priority' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'View dead letters' })).toHaveAttribute('href', '/dead-letters');
  });

  it('polls on the interval without the global loading bar, and stops when auto-refresh is switched off', async () => {
    seedSession(['OPERATOR']);
    let calls = 0;
    server.use(http.get('*/api/v1/ops/queues', () => { calls += 1; return json(queueStats({ inFlight: calls })); }));
    renderWithProviders(<QueuesPage refreshMs={120} />, { route: '/queues' });
    await screen.findByText('Last updated', { exact: false });
    await waitFor(() => expect(calls).toBeGreaterThanOrEqual(3), { timeout: 3000 });
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
    await userEvent.click(screen.getByLabelText(/Auto-refresh/));
    await act(() => new Promise((r) => setTimeout(r, 150)));
    const after = calls;
    await act(() => new Promise((r) => setTimeout(r, 450)));
    expect(calls).toBe(after);
  });

  it('stops polling when the page unmounts', async () => {
    seedSession(['OPERATOR']);
    let calls = 0;
    server.use(http.get('*/api/v1/ops/queues', () => { calls += 1; return json(queueStats()); }));
    const { unmount } = renderWithProviders(<QueuesPage refreshMs={100} />, { route: '/queues' });
    await waitFor(() => expect(calls).toBeGreaterThanOrEqual(2), { timeout: 3000 });
    unmount();
    await act(() => new Promise((r) => setTimeout(r, 150)));
    const after = calls;
    await act(() => new Promise((r) => setTimeout(r, 400)));
    expect(calls).toBe(after);
  });

  it('flags paused consumers and shows errors with retry', async () => {
    seedSession(['OPERATOR']);
    let fail = true;
    server.use(http.get('*/api/v1/ops/queues', () => (fail ? json({ code: 'INTERNAL_ERROR' }, { status: 500 }) : json(queueStats({ consumersPaused: true })))));
    renderWithProviders(<QueuesPage />, { route: '/queues' });
    expect(await screen.findByRole('alert')).toHaveTextContent('unexpected error');
    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('Consumers are paused.')).toBeInTheDocument();
  });

  it('depthData lists the four priorities in order plus unknown ones', () => {
    expect(depthData({ ...queueStats(), depthByPriority: { NORMAL: 2, ODD: 1 } }).map((d) => d.label)).toEqual(['CRITICAL', 'HIGH', 'NORMAL', 'LOW', 'ODD']);
    expect(depthData(undefined).every((d) => d.value === 0)).toBe(true);
  });
});

const job = (over: Record<string, unknown> = {}) => ({ id: 'job-1', name: 'Synthetic', status: 'RUNNING', total: 100, processed: 40, succeeded: 38, failed: 1, duplicate: 1, startedAt: '2030-01-01T00:00:00Z', ...over });

describe('bulk jobs', () => {
  it('helpers: active statuses and progress percent', () => {
    expect(isActiveJob({ status: 'RUNNING' })).toBe(true);
    expect(isActiveJob({ status: 'CREATED' })).toBe(true);
    expect(isActiveJob({ status: 'COMPLETED' })).toBe(false);
    expect(isActiveJob(undefined)).toBe(false);
    expect(progressPercent({ processed: 40, total: 100 })).toBe(40);
    expect(progressPercent({ processed: 5, total: 0 })).toBe(0);
    expect(progressPercent({ processed: 150, total: 100 })).toBe(100);
  });

  it('list: shows jobs and links to the detail page', async () => {
    seedSession(['SENDER']);
    server.use(http.get('*/api/v1/bulk-jobs', () => json(page([job({ status: 'COMPLETED_WITH_ERRORS' })]))));
    renderWithProviders(<BulkJobListPage />, { route: '/bulk-jobs' });
    const table = await screen.findByRole('table', { name: 'Bulk jobs' });
    expect(within(table).getByRole('link', { name: 'Synthetic' })).toHaveAttribute('href', '/bulk-jobs/job-1');
    expect(table).toHaveTextContent('40 / 100');
  });

  it('generate synthetic job posts the rates and navigates to the job', async () => {
    seedSession(['SENDER']);
    let sent: unknown;
    server.use(
      http.get('*/api/v1/bulk-jobs', () => json(page([]))),
      http.post('*/api/v1/bulk-jobs/generate-synthetic', async ({ request }) => { sent = await request.json(); return json(job(), { status: 202 }); }),
    );
    renderWithProviders(<BulkJobListPage />, { route: '/bulk-jobs', path: '/bulk-jobs' });
    const rows = await screen.findByLabelText('Rows');
    await userEvent.clear(rows);
    await userEvent.type(rows, '60');
    await userEvent.click(screen.getByRole('button', { name: 'Generate' }));
    await screen.findByTestId('elsewhere');
    expect(sent).toEqual({ rows: 60, duplicateRate: 0.02, invalidRate: 0.02 });
  });

  it('generate validates the ranges before calling the API', async () => {
    seedSession(['SENDER']);
    let calls = 0;
    server.use(http.get('*/api/v1/bulk-jobs', () => json(page([]))), http.post('*/api/v1/bulk-jobs/generate-synthetic', () => { calls += 1; return json(job()); }));
    renderWithProviders(<BulkJobListPage />, { route: '/bulk-jobs' });
    const rows = await screen.findByLabelText('Rows');
    await userEvent.clear(rows);
    await userEvent.type(rows, '0');
    const dup = screen.getByLabelText('Duplicate rate (0-1)');
    await userEvent.clear(dup);
    await userEvent.type(dup, '2');
    await userEvent.click(screen.getByRole('button', { name: 'Generate' }));
    expect(screen.getByText('1 to 1,000,000')).toBeInTheDocument();
    expect(screen.getByText('0 to 1')).toBeInTheDocument();
    expect(calls).toBe(0);
  });

  it('upload checks the file extension and requires the form fields', async () => {
    seedSession(['SENDER']);
    server.use(http.get('*/api/v1/bulk-jobs', () => json(page([]))));
    renderWithProviders(<BulkJobListPage />, { route: '/bulk-jobs' });
    const input = await screen.findByLabelText('CSV file');
    await userEvent.upload(input, new File(['x'], 'rows.txt', { type: 'text/plain' }), { applyAccept: false });
    expect(screen.getByText('File must have a .csv extension.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Upload and start' }));
    expect(screen.getByText('Job name is required')).toBeInTheDocument();
    expect(screen.getByText('Upper-case code, e.g. TRADE_CONFIRMATION')).toBeInTheDocument();
  });

  it('shows server errors (missing messages:bulk scope -> FORBIDDEN) from a launch', async () => {
    seedSession(['SENDER']);
    server.use(
      http.get('*/api/v1/bulk-jobs', () => json(page([]))),
      http.post('*/api/v1/bulk-jobs/generate-synthetic', () => json({ code: 'FORBIDDEN', message: 'scope' }, { status: 403 })),
    );
    renderWithProviders(<BulkJobListPage />, { route: '/bulk-jobs' });
    await userEvent.click(await screen.findByRole('button', { name: 'Generate' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Your role is not allowed');
  });

  function mountDetail(statuses: Record<string, unknown>[], pollMs = 80) {
    let n = 0;
    server.use(
      http.get('*/api/v1/bulk-jobs/job-1', () => json(statuses[Math.min(n++, statuses.length - 1)])),
      http.get('*/api/v1/bulk-jobs/job-1/failures', () => json(page([{ lineNo: 7, reason: 'INVALID_ADDRESS', rawLineHash: 'abc123' }]))),
    );
    return renderWithProviders(<BulkJobDetailPage pollMs={pollMs} />, { route: '/bulk-jobs/job-1', path: '/bulk-jobs/:id' });
  }

  it('detail: polls progress until the job completes, then stops polling', async () => {
    seedSession(['SENDER']);
    let calls = 0;
    const seq = [job({ processed: 10 }), job({ processed: 60 }), job({ processed: 100, status: 'COMPLETED_WITH_ERRORS', failed: 2, finishedAt: '2030-01-01T00:01:00Z' })];
    server.use(
      http.get('*/api/v1/bulk-jobs/job-1', () => json(seq[Math.min(calls++, 2)])),
      http.get('*/api/v1/bulk-jobs/job-1/failures', () => json(page([]))),
    );
    renderWithProviders(<BulkJobDetailPage pollMs={80} />, { route: '/bulk-jobs/job-1', path: '/bulk-jobs/:id' });
    expect(await screen.findByText(/auto-refreshing every/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText('Status COMPLETED WITH ERRORS')).toBeInTheDocument(), { timeout: 4000 });
    expect(screen.getByRole('progressbar', { name: 'Progress 100 percent' })).toHaveValue(100);
    expect(screen.queryByText(/auto-refreshing every/)).not.toBeInTheDocument();
    const settled = calls;
    await act(() => new Promise((r) => setTimeout(r, 400)));
    expect(calls).toBe(settled);
  });

  it('detail: lists the failed rows and offers Restart for a finished job with errors', async () => {
    seedSession(['SENDER']);
    mountDetail([job({ status: 'COMPLETED_WITH_ERRORS', processed: 100 })]);
    const table = await screen.findByRole('table', { name: 'Bulk job failures' });
    expect(table).toHaveTextContent('INVALID_ADDRESS');
    expect(table).toHaveTextContent('abc123');
    expect(screen.getByRole('button', { name: 'Restart' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Stop' })).not.toBeInTheDocument();
  });

  it('detail: Stop asks for confirmation then posts stop', async () => {
    seedSession(['SENDER']);
    const calls: string[] = [];
    server.use(http.post('*/api/v1/bulk-jobs/job-1/stop', () => { calls.push('stop'); return json({}); }));
    mountDetail([job()], 5000);
    await userEvent.click(await screen.findByRole('button', { name: 'Stop' }));
    const dlg = await screen.findByRole('alertdialog', { name: 'Stop bulk job?' });
    expect(calls).toEqual([]);
    await userEvent.click(within(dlg).getByRole('button', { name: 'Stop' }));
    await waitFor(() => expect(calls).toEqual(['stop']));
  });

  it('detail: Restart posts restart and resumes polling', async () => {
    seedSession(['SENDER']);
    const calls: string[] = [];
    server.use(http.post('*/api/v1/bulk-jobs/job-1/restart', () => { calls.push('restart'); return json({}); }));
    mountDetail([job({ status: 'STOPPED' }), job({ status: 'RUNNING', processed: 50 })]);
    await userEvent.click(await screen.findByRole('button', { name: 'Restart' }));
    await waitFor(() => expect(calls).toEqual(['restart']));
    expect(await screen.findByText(/auto-refreshing every/)).toBeInTheDocument();
  });

  it('detail: a poll error is shown with retry', async () => {
    seedSession(['SENDER']);
    server.use(http.get('*/api/v1/bulk-jobs/job-1', () => json({ code: 'NOT_FOUND' }, { status: 404 })), http.get('*/api/v1/bulk-jobs/job-1/failures', () => json({ code: 'NOT_FOUND' }, { status: 404 })));
    renderWithProviders(<BulkJobDetailPage pollMs={5000} />, { route: '/bulk-jobs/job-1', path: '/bulk-jobs/:id' });
    expect((await screen.findAllByRole('alert'))[0]).toHaveTextContent('not found');
  });
});

const dead = (id: string, over: Record<string, unknown> = {}) => ({ messageId: id, channel: 'EMAIL', reason: 'RETRIES_EXHAUSTED', deadLetteredAt: '2030-01-01T00:00:00Z', attemptCount: 5, discarded: false, ...over });

describe('dead letters', () => {
  it('lists dead letters with a link to the message', async () => {
    seedSession(['OPERATOR']);
    server.use(http.get('*/api/v1/dead-letters', () => json(page([dead('m-1'), dead('m-2', { discarded: true })]))));
    renderWithProviders(<DeadLettersPage />, { route: '/dead-letters' });
    const table = await screen.findByRole('table', { name: 'Dead-lettered messages' });
    expect(within(table).getByRole('link', { name: 'm-1' })).toHaveAttribute('href', '/messages/m-1');
    expect(within(table).getByText('discarded')).toBeInTheDocument();
    expect(within(table).getAllByRole('button', { name: 'Replay' })).toHaveLength(1);
  });

  it('replay asks for confirmation and then POSTs', async () => {
    seedSession(['OPERATOR']);
    const calls: string[] = [];
    server.use(
      http.get('*/api/v1/dead-letters', () => json(page([dead('m-1')]))),
      http.post('*/api/v1/dead-letters/m-1/replay', () => { calls.push('replay'); return json({}); }),
    );
    renderWithProviders(<DeadLettersPage />, { route: '/dead-letters' });
    await userEvent.click(await screen.findByRole('button', { name: 'Replay' }));
    const dlg = await screen.findByRole('alertdialog', { name: 'Replay message?' });
    expect(calls).toEqual([]);
    await userEvent.click(within(dlg).getByRole('button', { name: 'Replay' }));
    expect(await screen.findByText('Message m-1 replayed.')).toBeInTheDocument();
    expect(calls).toEqual(['replay']);
  });

  it('discard is a danger confirmation; cancelling sends nothing; confirming POSTs discard', async () => {
    seedSession(['PLATFORM_ADMIN']);
    const calls: string[] = [];
    server.use(
      http.get('*/api/v1/dead-letters', () => json(page([dead('m-1')]))),
      http.post('*/api/v1/dead-letters/m-1/discard', () => { calls.push('discard'); return json({}); }),
    );
    renderWithProviders(<DeadLettersPage />, { route: '/dead-letters' });
    await userEvent.click(await screen.findByRole('button', { name: 'Discard' }));
    await userEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Cancel' }));
    expect(calls).toEqual([]);
    await userEvent.click(screen.getByRole('button', { name: 'Discard' }));
    const dlg = await screen.findByRole('alertdialog', { name: 'Discard message?' });
    expect(dlg).toHaveTextContent('cannot be undone');
    await userEvent.click(within(dlg).getByRole('button', { name: 'Discard' }));
    expect(await screen.findByText('Message m-1 discarded.')).toBeInTheDocument();
  });

  it('tenant admins can view dead letters but get no replay/discard buttons', async () => {
    seedSession(['TENANT_ADMIN']);
    server.use(http.get('*/api/v1/dead-letters', () => json(page([dead('m-1')]))));
    renderWithProviders(<DeadLettersPage />, { route: '/dead-letters' });
    await screen.findByRole('table');
    expect(screen.queryByRole('button', { name: 'Replay' })).not.toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: 'Actions' })).not.toBeInTheDocument();
  });

  it('an empty queue shows the empty state; a refused replay shows the error', async () => {
    seedSession(['OPERATOR']);
    server.use(http.get('*/api/v1/dead-letters', () => json(page([]))));
    const { unmount } = renderWithProviders(<DeadLettersPage />, { route: '/dead-letters' });
    expect(await screen.findByText('Dead-letter queue is empty')).toBeInTheDocument();
    unmount();
    server.use(
      http.get('*/api/v1/dead-letters', () => json(page([dead('m-1')]))),
      http.post('*/api/v1/dead-letters/m-1/replay', () => json({ code: 'ILLEGAL_STATE_TRANSITION' }, { status: 409 })),
    );
    renderWithProviders(<DeadLettersPage />, { route: '/dead-letters' });
    await userEvent.click(await screen.findByRole('button', { name: 'Replay' }));
    await userEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Replay' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('no longer in a state that allows this action');
  });
});

describe('failed messages', () => {
  const failed = (id: string, status: string) => ({ id, status, channel: 'SMS', statusReason: 'PROVIDER_ERROR', attemptCount: 3, updatedAt: '2030-01-01T00:00:00Z' });

  it('offers Requeue only for FAILED rows (never for DELIVERY_UNKNOWN) and only to roles that may', async () => {
    seedSession(['OPERATOR']);
    server.use(http.get('*/api/v1/failed-messages', () => json(page([failed('f-1', 'FAILED'), failed('f-2', 'DELIVERY_UNKNOWN')]))));
    renderWithProviders(<FailedPage />, { route: '/failed' });
    await screen.findByRole('table');
    expect(screen.getAllByRole('button', { name: 'Requeue' })).toHaveLength(1);
    expect(screen.getByText(/DELIVERY_UNKNOWN is never retried blindly/)).toBeInTheDocument();
  });

  it('requeue confirms then POSTs', async () => {
    seedSession(['OPERATOR']);
    const calls: string[] = [];
    server.use(
      http.get('*/api/v1/failed-messages', () => json(page([failed('f-1', 'FAILED')]))),
      http.post('*/api/v1/messages/f-1/requeue', () => { calls.push('requeue'); return json({}); }),
    );
    renderWithProviders(<FailedPage />, { route: '/failed' });
    await userEvent.click(await screen.findByRole('button', { name: 'Requeue' }));
    await userEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Requeue' }));
    expect(await screen.findByText('Message f-1 requeued.')).toBeInTheDocument();
    expect(calls).toEqual(['requeue']);
  });

  it('tenant admins see the list without requeue', async () => {
    seedSession(['TENANT_ADMIN']);
    server.use(http.get('*/api/v1/failed-messages', () => json(page([failed('f-1', 'FAILED')]))));
    renderWithProviders(<FailedPage />, { route: '/failed' });
    await screen.findByRole('table');
    expect(screen.queryByRole('button', { name: 'Requeue' })).not.toBeInTheDocument();
  });
});

describe('providers and admin', () => {
  const provider = (id: string, over: Record<string, unknown> = {}) => ({ providerId: id, channel: 'EMAIL', enabled: true, circuitState: 'CLOSED', successRate: 0.987, p95LatencyMs: 0, rateLimitPerSec: 50, timeoutMs: 2000, faultMode: 'NONE', faultProbability: 0, ...over });

  it('shows health per provider: circuit state, success rate, a dash for an idle p95', async () => {
    seedSession(['OPERATOR']);
    server.use(http.get('*/api/v1/providers', () => json([provider('acme-email'), provider('acme-sms', { circuitState: 'OPEN', p95LatencyMs: 120, lastError: 'SIM_REJECTED' })])));
    renderWithProviders(<ProvidersPage />, { route: '/providers' });
    const a = await screen.findByLabelText('Provider acme-email');
    expect(a).toHaveTextContent('98.7%');
    expect(a).toHaveTextContent('— (no calls in the last minutes)');
    const b = screen.getByLabelText('Provider acme-sms');
    expect(b).toHaveTextContent('120 ms');
    expect(within(b).getByLabelText('Status OPEN')).toBeInTheDocument();
    expect(b).toHaveTextContent('SIM_REJECTED');
    expect(screen.queryByText('Fault injection (simulator only)')).not.toBeInTheDocument();
  });

  it('platform admin can inject a fault: PUT fault-mode with clamped probability', async () => {
    seedSession(['PLATFORM_ADMIN']);
    let sent: unknown;
    server.use(
      http.get('*/api/v1/providers', () => json([provider('acme-email')])),
      http.put('*/api/v1/providers/acme-email/fault-mode', async ({ request }) => { sent = await request.json(); return json({}); }),
    );
    renderWithProviders(<ProvidersPage />, { route: '/providers' });
    await userEvent.selectOptions(await screen.findByLabelText('Mode'), 'TRANSIENT');
    const prob = screen.getByLabelText('Probability (0-1)');
    await userEvent.clear(prob);
    await userEvent.type(prob, '7');
    await userEvent.click(screen.getByRole('button', { name: 'Apply' }));
    expect(await screen.findByText('Fault mode for acme-email set to TRANSIENT (p=1).')).toBeInTheDocument();
    expect(sent).toEqual({ mode: 'TRANSIENT', probability: 1 });
  });

  it('admin: pause consumers is confirmed, then POSTed', async () => {
    seedSession(['PLATFORM_ADMIN']);
    const calls: string[] = [];
    server.use(
      http.get('*/api/v1/providers', () => json([])),
      http.post('*/api/v1/ops/consumers/pause', () => { calls.push('pause'); return json({}); }),
    );
    renderWithProviders(<AdminPage />, { route: '/admin' });
    await userEvent.click(screen.getByRole('button', { name: 'Pause consumers' }));
    const dlg = await screen.findByRole('alertdialog', { name: 'Pause consumers?' });
    expect(calls).toEqual([]);
    await userEvent.click(within(dlg).getByRole('button', { name: 'Pause' }));
    expect(await screen.findByText('Consumers paused.')).toBeInTheDocument();
  });

  it('admin: saves provider configuration with numeric fields and validates ranges', async () => {
    seedSession(['PLATFORM_ADMIN']);
    let sent: unknown;
    server.use(
      http.get('*/api/v1/providers', () => json([provider('acme-email')])),
      http.put('*/api/v1/providers/acme-email/config', async ({ request }) => { sent = await request.json(); return json({}); }),
    );
    renderWithProviders(<AdminPage />, { route: '/admin' });
    const rate = await screen.findByLabelText('Rate limit /s');
    await userEvent.clear(rate);
    await userEvent.type(rate, '0');
    expect(screen.getByText('1 to 10000')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    await userEvent.clear(rate);
    await userEvent.type(rate, '75');
    await userEvent.click(screen.getByLabelText('Enabled'));
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Provider acme-email configuration saved.')).toBeInTheDocument();
    expect(sent).toEqual({ enabled: false, rateLimitPerSec: 75, timeoutMs: 2000 });
  });
});

describe('delivery events and audit', () => {
  it('delivery events: filters become query parameters and rows link to the message', async () => {
    seedSession(['AUDITOR']);
    const urls: URL[] = [];
    server.use(http.get('*/api/v1/delivery-events', ({ request }) => {
      urls.push(new URL(request.url));
      return json(page([{ messageId: 'm-1', providerId: 'acme-email', providerEventId: 'e1', type: 'BOUNCED', occurredAt: '2030-01-01T00:00:00Z', applied: false, ignoredReason: 'OUT_OF_ORDER' }]));
    }));
    renderWithProviders(<DeliveryEventsPage />, { route: '/delivery-events' });
    const table = await screen.findByRole('table', { name: 'Delivery events' });
    expect(within(table).getByRole('link', { name: 'm-1' })).toHaveAttribute('href', '/messages/m-1');
    expect(table).toHaveTextContent('OUT_OF_ORDER');
    await userEvent.selectOptions(screen.getByLabelText('Type'), 'BOUNCED');
    await userEvent.selectOptions(screen.getByLabelText('Applied'), 'false');
    await userEvent.type(screen.getByLabelText('Provider id'), ' acme-email ');
    await userEvent.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(urls.at(-1)?.searchParams.get('type')).toBe('BOUNCED'));
    expect(urls.at(-1)?.searchParams.get('applied')).toBe('false');
    expect(urls.at(-1)?.searchParams.get('providerId')).toBe('acme-email');
  });

  it('audit: renders object and string details as plain text and filters by actor', async () => {
    seedSession(['AUDITOR']);
    const urls: URL[] = [];
    server.use(http.get('*/api/v1/audit-events', ({ request }) => {
      urls.push(new URL(request.url));
      return json(page([
        { id: 'a1', actor: 'author-retail', actorRole: 'TEMPLATE_AUTHOR', action: 'TEMPLATE_CREATED', entityType: 'TEMPLATE', entityId: 't1', detail: { locale: 'en-US' }, occurredAt: '2030-01-01T00:00:00Z' },
        { id: 'a2', actor: 'x', action: 'NOTE', entityType: 'MESSAGE', entityId: 'm1', detail: '<img src=x onerror=alert(1)>', occurredAt: '2030-01-01T00:00:01Z' },
      ]));
    }));
    const { container } = renderWithProviders(<AuditPage />, { route: '/audit' });
    const table = await screen.findByRole('table', { name: 'Audit events' });
    expect(table).toHaveTextContent('{"locale":"en-US"}');
    expect(table).toHaveTextContent('<img src=x onerror=alert(1)>');
    expect(container.querySelector('img')).toBeNull();
    await userEvent.type(screen.getByLabelText('Actor'), 'author-retail');
    await userEvent.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(urls.at(-1)?.searchParams.get('actor')).toBe('author-retail'));
  });

  it('auditDetailText handles null, strings and objects', () => {
    expect(auditDetailText({ detail: null })).toBe('');
    expect(auditDetailText({ detail: 'x' })).toBe('x');
    expect(auditDetailText({ detail: { a: 1 } })).toBe('{"a":1}');
  });

  it('never surfaces message bodies: no body field is rendered even if the API returned one', async () => {
    seedSession(['OPERATOR']);
    server.use(http.get('*/api/v1/failed-messages', () => json(page([{ id: 'f-1', status: 'FAILED', channel: 'SMS', body: 'SECRET BODY', attemptCount: 1, updatedAt: '2030-01-01T00:00:00Z' }]))));
    renderWithProviders(<FailedPage />, { route: '/failed' });
    await screen.findByRole('table');
    expect(screen.queryByText(/SECRET BODY/)).not.toBeInTheDocument();
  });
});
