import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { describe, expect, it } from 'vitest';
import { server } from '../../test/server';
import { json, page, renderWithProviders, seedSession } from '../../test/utils';
import { MessageDetailPage } from './MessageDetailPage';
import { MessageListPage, buildMessageFilter } from './MessageListPage';
import { MessageSubmitPage } from './MessageSubmitPage';
import { EMPTY_SUBMIT_FORM, buildSubmitRequest, validateSubmit } from './submitRequest';

const summary = (n: number, over: Record<string, unknown> = {}) => ({
  id: `msg-${n}`, channel: 'EMAIL', channelUsed: n === 2 ? 'SMS' : 'EMAIL', category: 'TRANSACTIONAL', priority: 'NORMAL',
  status: n === 2 ? 'FAILED' : 'DELIVERED', templateCode: 'TRADE_CONFIRMATION', clientReference: `ref-${n}`,
  nextAttemptAt: null, createdAt: '2030-01-02T03:04:05Z', ...over,
});

describe('message list page', () => {
  it('lists messages with status chips, sent-via and a link to the detail page', async () => {
    seedSession(['SENDER']);
    server.use(http.get('*/api/v1/messages', () => json(page([summary(1), summary(2)]))));
    renderWithProviders(<MessageListPage />, { route: '/messages' });
    const table = await screen.findByRole('table', { name: 'Messages' });
    expect(within(table).getByRole('link', { name: 'msg-1' })).toHaveAttribute('href', '/messages/msg-1');
    expect(within(table).getByLabelText('Status FAILED')).toBeInTheDocument();
    expect(within(table).getAllByText('SMS').length).toBeGreaterThan(0);
    expect(screen.getByRole('link', { name: 'New message' })).toBeInTheDocument();
  });

  it('hides the New message link for roles that cannot submit', async () => {
    seedSession(['AUDITOR']);
    server.use(http.get('*/api/v1/messages', () => json(page([]))));
    renderWithProviders(<MessageListPage />, { route: '/messages' });
    await screen.findByText('No messages found');
    expect(screen.queryByRole('link', { name: 'New message' })).not.toBeInTheDocument();
  });

  it('applies filters as query parameters (UTC day boundaries, sorted newest first) and resets to page 0', async () => {
    seedSession(['SENDER']);
    const urls: URL[] = [];
    server.use(http.get('*/api/v1/messages', ({ request }) => { urls.push(new URL(request.url)); return json(page([summary(1)], 20, 0, 45)); }));
    renderWithProviders(<MessageListPage />, { route: '/messages' });
    await screen.findByRole('table');
    await userEvent.click(screen.getByRole('button', { name: 'Next page' }));
    await waitFor(() => expect(urls.at(-1)?.searchParams.get('page')).toBe('1'));
    await userEvent.selectOptions(screen.getByLabelText('Status'), 'FAILED');
    await userEvent.type(screen.getByLabelText('Recipient id'), '  rcp-9 ');
    await userEvent.type(screen.getByLabelText('From (UTC date)'), '2030-03-01');
    await userEvent.click(screen.getByRole('button', { name: 'Apply filters' }));
    await waitFor(() => expect(urls.at(-1)?.searchParams.get('status')).toBe('FAILED'));
    const p = urls.at(-1)!.searchParams;
    expect(p.get('recipientId')).toBe('rcp-9');
    expect(p.get('from')).toBe('2030-03-01T00:00:00Z');
    expect(p.get('page')).toBe('0');
    expect(p.get('sort')).toBe('createdAt,desc');
    expect(p.has('channel')).toBe(false);
  });

  it('paginates and shows the total', async () => {
    seedSession(['SENDER']);
    server.use(http.get('*/api/v1/messages', ({ request }) => {
      const pg = Number(new URL(request.url).searchParams.get('page'));
      return json(page([summary(pg + 1)], 20, pg, 41));
    }));
    renderWithProviders(<MessageListPage />, { route: '/messages' });
    await screen.findByRole('link', { name: 'msg-1' });
    expect(screen.getByTestId('pager-range')).toHaveTextContent('1–20 of 41');
    await userEvent.click(screen.getByRole('button', { name: 'Next page' }));
    expect(await screen.findByRole('link', { name: 'msg-2' })).toBeInTheDocument();
    expect(screen.getByTestId('pager-range')).toHaveTextContent('21–40 of 41');
  });

  it('shows an error banner when the request fails and Clear reloads', async () => {
    seedSession(['SENDER']);
    server.use(http.get('*/api/v1/messages', () => json({ code: 'FORBIDDEN', message: 'no' }, { status: 403 })));
    renderWithProviders(<MessageListPage />, { route: '/messages' });
    expect(await screen.findByRole('alert')).toHaveTextContent('Your role is not allowed');
  });

  it('buildMessageFilter trims and converts dates', () => {
    const f = buildMessageFilter({ status: '', channel: 'SMS', category: '', priority: '', recipientId: ' r ', correlationId: '', q: ' x ', from: '2030-01-01', to: '2030-01-02' }, 3, 50);
    expect(f).toMatchObject({ channel: 'SMS', recipientId: 'r', q: 'x', from: '2030-01-01T00:00:00Z', to: '2030-01-02T23:59:59Z', page: 3, size: 50 });
  });
});

describe('submit request builder and validation', () => {
  const base = { ...EMPTY_SUBMIT_FORM, recipientId: 'rcp-1', templateCode: 'T_CODE' };

  it('maps the minimal form to the contract body', () => {
    expect(buildSubmitRequest(base)).toEqual({
      channel: 'EMAIL', category: 'TRANSACTIONAL', priority: 'NORMAL', recipientId: 'rcp-1',
      template: { code: 'T_CODE', locale: 'en-US', version: null },
    });
  });

  it('prefers recipientId over externalRef and sends the external reference otherwise', () => {
    expect(buildSubmitRequest({ ...base, externalRef: 'CUST-1' }).recipient).toBeUndefined();
    const r = buildSubmitRequest({ ...base, recipientId: '', externalRef: ' CUST-1 ' });
    expect(r.recipientId).toBeUndefined();
    expect(r.recipient).toEqual({ externalRef: 'CUST-1' });
  });

  it('sends only the channel options that belong to the channel', () => {
    expect(buildSubmitRequest({ ...base, replyTo: 'a@b.co', senderId: 'ACME' }).channelOptions).toEqual({ email: { replyTo: 'a@b.co' } });
    expect(buildSubmitRequest({ ...base, channel: 'SMS', replyTo: 'a@b.co', senderId: 'ACME' }).channelOptions).toEqual({ sms: { senderId: 'ACME' } });
    expect(buildSubmitRequest({ ...base, channel: 'PUSH', collapseKey: 'k', ttlSeconds: '60' }).channelOptions).toEqual({ push: { collapseKey: 'k', ttlSeconds: 60 } });
    expect(buildSubmitRequest(base).channelOptions).toBeUndefined();
  });

  it('collects variables, fallbacks, pinned version and schedule', () => {
    const r = buildSubmitRequest({
      ...base, templateVersion: '3', variables: [{ key: 'a', value: '1' }, { key: ' ', value: 'x' }],
      fallbackChannels: ['SMS'], sendAt: '2031-01-01T10:00', expiresAt: '2031-01-02T10:00',
    });
    expect(r.variables).toEqual({ a: '1' });
    expect(r.fallbackChannels).toEqual(['SMS']);
    expect(r.template.version).toBe(3);
    expect(r.schedule?.sendAt).toMatch(/^2031-01-01T/);
    expect(r.schedule?.expiresAt).toMatch(/^2031-01-02T/);
  });

  it('never contains a message body', () => {
    expect(JSON.stringify(buildSubmitRequest({ ...base, variables: [{ key: 'a', value: 'b' }] }))).not.toMatch(/"body"/);
  });

  it('validates the same rules as the Angular form', () => {
    expect(validateSubmit(base)).toEqual({});
    expect(validateSubmit({ ...base, recipientId: '' }).recipient).toBeDefined();
    expect(validateSubmit({ ...base, templateCode: ' ' }).templateCode).toBeDefined();
    expect(validateSubmit({ ...base, locale: 'en_US' }).locale).toBeDefined();
    expect(validateSubmit({ ...base, replyTo: 'nope' }).replyTo).toBeDefined();
    expect(validateSubmit({ ...base, channel: 'SMS', senderId: 'x' }).senderId).toBeDefined();
    expect(validateSubmit({ ...base, channel: 'PUSH', ttlSeconds: '0' }).ttlSeconds).toBeDefined();
    expect(validateSubmit({ ...base, fallbackChannels: ['EMAIL'] }).fallback).toBeDefined();
    expect(validateSubmit({ ...base, templateVersion: '1.5' }).templateVersion).toBeDefined();
    expect(validateSubmit({ ...base, variables: [{ key: '1x', value: '' }] }).variables).toBeDefined();
    expect(validateSubmit({ ...base, variables: [{ key: 'a', value: '' }, { key: 'a', value: '' }] }).variableKeys).toBeDefined();
    expect(validateSubmit({ ...base, sendAt: '2000-01-01T00:00' }).sendAt).toBeDefined();
    expect(validateSubmit({ ...base, sendAt: '2031-01-02T00:00', expiresAt: '2031-01-01T00:00' }).expiry).toBeDefined();
  });
});

describe('submit message page', () => {
  const templates = () => server.use(
    http.get('*/api/v1/templates', () => json(page([{ id: 't1', code: 'TRADE_CONFIRMATION', channel: 'EMAIL', category: 'TRANSACTIONAL' }]))),
    http.get('*/api/v1/templates/t1', () => json({
      id: 't1', code: 'TRADE_CONFIRMATION', channel: 'EMAIL', category: 'TRANSACTIONAL',
      versions: [{ versionNo: 1, locale: 'en', status: 'PUBLISHED', body: 'x', variables: ['symbol', 'quantity'] }],
    })),
  );

  it('blocks submission and shows field errors for an empty form', async () => {
    seedSession(['SENDER']);
    templates();
    renderWithProviders(<MessageSubmitPage />, { route: '/messages/new' });
    await userEvent.click(screen.getByRole('button', { name: 'Submit message' }));
    expect(screen.getByText('Provide a recipient id or an external reference.')).toBeInTheDocument();
    expect(screen.getByText('Template code is required')).toBeInTheDocument();
  });

  it('submits with an Idempotency-Key, shows the 202 result, then the idempotent replay with the same key', async () => {
    seedSession(['SENDER']);
    templates();
    const seen: { key: string | null; body: Record<string, unknown> }[] = [];
    server.use(http.post('*/api/v1/messages', async ({ request }) => {
      seen.push({ key: request.headers.get('idempotency-key'), body: (await request.json()) as Record<string, unknown> });
      const replay = seen.length > 1;
      return json({ id: 'msg-new', status: 'QUEUED', channel: 'EMAIL', correlationId: 'corr-9' },
        { status: replay ? 200 : 202, headers: replay ? { 'Idempotent-Replay': 'true' } : {} });
    }));
    renderWithProviders(<MessageSubmitPage />, { route: '/messages/new' });
    await userEvent.type(screen.getByLabelText('Recipient id'), 'rcp-031');
    await userEvent.selectOptions(screen.getByLabelText('Published template'), 'TRADE_CONFIRMATION');
    // picking a published template pre-fills locale and its variable keys
    await waitFor(() => expect(screen.getByLabelText('Locale')).toHaveValue('en'));
    expect(screen.getByRole('textbox', { name: 'Variable key 1' })).toHaveValue('symbol');
    await userEvent.type(screen.getByRole('textbox', { name: 'Variable value 1' }), 'ACME');
    await userEvent.click(screen.getByRole('button', { name: 'Submit message' }));
    expect(await screen.findByText('Accepted (HTTP 202)')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'msg-new' })).toHaveAttribute('href', '/messages/msg-new');
    expect(seen[0].body).toMatchObject({ recipientId: 'rcp-031', channel: 'EMAIL', template: { code: 'TRADE_CONFIRMATION', locale: 'en', version: null }, variables: { symbol: 'ACME' } });
    expect(seen[0].key).toBe(screen.getByTestId('idem-key').textContent);

    await userEvent.click(screen.getByRole('button', { name: 'Submit message' }));
    expect(await screen.findByText('Idempotent replay (HTTP 200)')).toBeInTheDocument();
    expect(seen[1].key).toBe(seen[0].key);

    await userEvent.click(screen.getByRole('button', { name: 'New key' }));
    expect(screen.getByTestId('idem-key').textContent).not.toBe(seen[0].key);
    expect(screen.queryByText(/Idempotent replay/)).not.toBeInTheDocument();
  });

  it('shows the mapped server error (idempotency key reuse) with the correlation id', async () => {
    seedSession(['SENDER']);
    templates();
    server.use(http.post('*/api/v1/messages', () => json({ code: 'IDEMPOTENCY_KEY_REUSED', message: 'x', correlationId: 'c-77' }, { status: 422 })));
    renderWithProviders(<MessageSubmitPage />, { route: '/messages/new' });
    await userEvent.type(screen.getByLabelText('Recipient id'), 'rcp-1');
    await userEvent.type(screen.getByLabelText('Template code'), 'TRADE_CONFIRMATION');
    await userEvent.click(screen.getByRole('button', { name: 'Submit message' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('already used with a different request');
    expect(alert).toHaveTextContent('correlationId=c-77');
  });

  it('switching the channel swaps the channel options and drops a fallback equal to the new primary', async () => {
    seedSession(['SENDER']);
    templates();
    renderWithProviders(<MessageSubmitPage />, { route: '/messages/new' });
    expect(screen.getByLabelText('Reply-to')).toBeInTheDocument();
    await userEvent.click(screen.getByLabelText('SMS'));
    await userEvent.selectOptions(screen.getByLabelText('Channel'), 'SMS');
    expect(screen.getByLabelText('Sender id')).toBeInTheDocument();
    expect(screen.queryByLabelText('Reply-to')).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox', { name: 'SMS' })).not.toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText('Channel'), 'PUSH');
    expect(screen.getByLabelText('TTL (seconds)')).toBeInTheDocument();
  });

  it('variable rows can be added and removed', async () => {
    seedSession(['SENDER']);
    templates();
    renderWithProviders(<MessageSubmitPage />, { route: '/messages/new' });
    await userEvent.click(screen.getByRole('button', { name: 'Add variable' }));
    await userEvent.click(screen.getByRole('button', { name: 'Add variable' }));
    expect(screen.getAllByRole('button', { name: /Remove variable/ })).toHaveLength(2);
    await userEvent.click(screen.getByRole('button', { name: 'Remove variable 1' }));
    expect(screen.getAllByRole('button', { name: /Remove variable/ })).toHaveLength(1);
  });
});

describe('message detail page', () => {
  const detail = (status: string, over: Record<string, unknown> = {}) => ({
    id: 'msg-1', tenantId: 'tenant-retail', recipient: { id: 'rcp-1', externalRef: 'CUST-1', maskedAddress: 'a***@example.test' },
    channel: 'EMAIL', channelUsed: 'SMS', category: 'TRANSACTIONAL', priority: 'NORMAL', status, terminal: status === 'DELIVERED',
    template: { code: 'TRADE_CONFIRMATION', versionNo: 2, locale: 'en' }, correlationId: 'corr-1', idempotencyKey: 'idem-1',
    attemptCount: 1, maxAttempts: 5, createdAt: '2030-01-01T00:00:00Z', updatedAt: '2030-01-01T00:00:05Z',
    attempts: [{ attemptNo: 1, channel: 'EMAIL', providerId: 'acme-email', outcome: 'ACCEPTED', startedAt: '2030-01-01T00:00:01Z' }],
    deliveryEvents: [{ providerId: 'acme-email', type: 'DELIVERED', occurredAt: '2030-01-01T00:00:03Z', applied: true }], ...over,
  });
  const history = [
    { at: '2030-01-01T00:00:00Z', kind: 'AUDIT', type: 'MESSAGE_ACCEPTED', summary: 'Message accepted', detail: { actor: 'sender-retail', to: 'ACCEPTED' } },
    { at: '2030-01-01T00:00:02Z', kind: 'DELIVERY_EVENT', type: 'DELIVERED', summary: 'Provider receipt', detail: {} },
  ];
  const route = (status: string) => {
    server.use(
      http.get('*/api/v1/messages/msg-1', () => json(detail(status))),
      http.get('*/api/v1/messages/msg-1/history', () => json(history)),
    );
    renderWithProviders(<MessageDetailPage />, { route: '/messages/msg-1', path: '/messages/:id' });
  };

  it('shows the summary, masked recipient, fallback channel, attempts and the lifecycle timeline', async () => {
    seedSession(['SENDER']);
    route('DELIVERED');
    expect(await screen.findByLabelText('Summary')).toHaveTextContent('EMAIL → SMS (fallback)');
    const summary = screen.getByLabelText('Summary');
    expect(summary).toHaveTextContent('a***@example.test');
    expect(summary).toHaveTextContent('TRADE_CONFIRMATION v2 (en)');
    expect(summary).toHaveTextContent('1 / 5');
    expect(summary).toHaveTextContent('never returned by the API');
    const timeline = await screen.findByRole('list', { name: 'Message lifecycle timeline' });
    expect(within(timeline).getAllByRole('listitem')).toHaveLength(2);
    expect(timeline).toHaveTextContent('sender-retail');
    expect(screen.getByRole('table', { name: 'Attempts' })).toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'Delivery events' })).toBeInTheDocument();
  });

  it('offers Cancel only for cancellable statuses and the right roles; confirm then POST', async () => {
    seedSession(['SENDER']);
    let cancelled = 0;
    server.use(http.post('*/api/v1/messages/msg-1/cancel', () => { cancelled += 1; return json({}); }));
    route('QUEUED');
    const btn = await screen.findByRole('button', { name: 'Cancel message' });
    expect(screen.queryByRole('button', { name: 'Requeue' })).not.toBeInTheDocument();
    await userEvent.click(btn);
    const dlg = await screen.findByRole('alertdialog');
    expect(cancelled).toBe(0);
    await userEvent.click(within(dlg).getByRole('button', { name: 'Cancel message' }));
    expect(await screen.findByText('Message cancelled.')).toBeInTheDocument();
    expect(cancelled).toBe(1);
  });

  it('declining the confirmation sends nothing', async () => {
    seedSession(['OPERATOR']);
    let calls = 0;
    server.use(http.post('*/api/v1/messages/msg-1/requeue', () => { calls += 1; return json({}); }));
    route('FAILED');
    await userEvent.click(await screen.findByRole('button', { name: 'Requeue' }));
    await userEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Cancel' }));
    expect(calls).toBe(0);
  });

  it('a delivered message offers no actions', async () => {
    seedSession(['PLATFORM_ADMIN']);
    route('DELIVERED');
    await screen.findByLabelText('Summary');
    expect(screen.queryByRole('button', { name: 'Cancel message' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Requeue' })).not.toBeInTheDocument();
  });

  it('shows a mapped error for an unknown message', async () => {
    seedSession(['SENDER']);
    server.use(
      http.get('*/api/v1/messages/msg-1', () => json({ code: 'NOT_FOUND', message: 'nope' }, { status: 404 })),
      http.get('*/api/v1/messages/msg-1/history', () => json({ code: 'NOT_FOUND' }, { status: 404 })),
    );
    renderWithProviders(<MessageDetailPage />, { route: '/messages/msg-1', path: '/messages/:id' });
    expect((await screen.findAllByRole('alert'))[0]).toHaveTextContent('not found, or it belongs to another tenant');
  });

  it('surfaces a failed action (illegal state transition) without losing the page', async () => {
    seedSession(['SENDER']);
    server.use(http.post('*/api/v1/messages/msg-1/cancel', () => json({ code: 'ILLEGAL_STATE_TRANSITION', message: 'x' }, { status: 409 })));
    route('QUEUED');
    await userEvent.click(await screen.findByRole('button', { name: 'Cancel message' }));
    await userEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Cancel message' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('no longer in a state that allows this action');
    expect(screen.getByLabelText('Summary')).toBeInTheDocument();
  });
});
