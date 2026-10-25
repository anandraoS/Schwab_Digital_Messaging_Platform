import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { clearSession, apiPage, seedSession, testProviders } from '../../testing/test-utils';
import { MessageDetail, MessageSummary } from '../../core/models/api.models';
import { ConfirmService } from '../../shared/confirm-dialog';
import { of } from 'rxjs';
import { MessageDetailPage } from './message-detail.page';
import { MessageListPage, dayBoundary } from './message-list.page';
import { MessageSubmitPage } from './message-submit.page';
import { SubmitFormValue, buildSubmitRequest } from './submit-request';

const html = (f: ComponentFixture<unknown>): HTMLElement => f.nativeElement as HTMLElement;

const summary = (id: string, status: MessageSummary['status'] = 'DELIVERED'): MessageSummary => ({
  id, channel: 'EMAIL', category: 'TRANSACTIONAL', priority: 'NORMAL', status, createdAt: '2026-01-01T10:00:00Z', templateCode: 'TRADE_CONFIRMATION',
});

describe('buildSubmitRequest', () => {
  const base: SubmitFormValue = {
    clientReference: '', recipientId: 'rcp-001', externalRef: '', channel: 'EMAIL', category: 'TRANSACTIONAL', priority: 'NORMAL',
    templateCode: 'TRADE_CONFIRMATION', locale: 'en-US', templateVersion: null, variables: [],
    email: { replyTo: '' }, sms: { senderId: '' }, push: { collapseKey: '', ttlSeconds: null }, fallbackChannels: [],
    schedule: { sendAt: '', expiresAt: '' },
  };

  it('builds a minimal contract-shaped request', () => {
    expect(buildSubmitRequest(base)).toEqual({
      channel: 'EMAIL', category: 'TRANSACTIONAL', priority: 'NORMAL', recipientId: 'rcp-001',
      template: { code: 'TRADE_CONFIRMATION', locale: 'en-US', version: null },
    });
  });

  it('uses externalRef only when recipientId is empty', () => {
    const r = buildSubmitRequest({ ...base, recipientId: ' ', externalRef: 'CUST-001' });
    expect(r.recipient).toEqual({ externalRef: 'CUST-001' });
    expect(r.recipientId).toBeUndefined();
  });

  it('maps variables rows to a record and skips blank keys', () => {
    const r = buildSubmitRequest({ ...base, variables: [{ key: 'amount', value: '250.00' }, { key: ' ', value: 'x' }] });
    expect(r.variables).toEqual({ amount: '250.00' });
  });

  it('includes only the options of the selected channel', () => {
    const r = buildSubmitRequest({ ...base, channel: 'SMS', sms: { senderId: 'ACME' }, email: { replyTo: 'x@y.com' } });
    expect(r.channelOptions).toEqual({ sms: { senderId: 'ACME' } });
    const p = buildSubmitRequest({ ...base, channel: 'PUSH', push: { collapseKey: 'k', ttlSeconds: 60 } });
    expect(p.channelOptions).toEqual({ push: { collapseKey: 'k', ttlSeconds: 60 } });
  });

  it('adds fallbacks, pinned version and ISO schedule', () => {
    const r = buildSubmitRequest({ ...base, fallbackChannels: ['SMS'], templateVersion: 3, schedule: { sendAt: '2030-01-01T10:00', expiresAt: '' } });
    expect(r.fallbackChannels).toEqual(['SMS']);
    expect(r.template.version).toBe(3);
    expect(r.schedule?.sendAt).toBe(new Date('2030-01-01T10:00').toISOString());
    expect(r.schedule?.expiresAt).toBeUndefined();
  });
});

describe('dayBoundary', () => {
  it('builds UTC day boundaries and handles empty', () => {
    expect(dayBoundary('2026-03-04', false)).toBe('2026-03-04T00:00:00Z');
    expect(dayBoundary('2026-03-04', true)).toBe('2026-03-04T23:59:59Z');
    expect(dayBoundary('', true)).toBe('');
  });
});

describe('MessageListPage', () => {
  let ctrl: HttpTestingController;
  let f: ComponentFixture<MessageListPage>;

  beforeEach(() => {
    seedSession(['SENDER']);
    TestBed.configureTestingModule({ providers: testProviders() });
    ctrl = TestBed.inject(HttpTestingController);
    f = TestBed.createComponent(MessageListPage);
    f.detectChanges();
  });
  afterEach(() => { ctrl.verify(); clearSession(); });

  const flushList = (rows: MessageSummary[], total = rows.length) =>
    ctrl.expectOne((r) => r.url === '/api/v1/messages').flush(apiPage(rows, { totalElements: total }));

  it('shows a loading state, then rows', () => {
    expect(html(f).querySelector('mat-progress-bar')).not.toBeNull();
    flushList([summary('msg-1'), summary('msg-2', 'FAILED')]);
    f.detectChanges();
    expect(html(f).querySelector('mat-progress-bar')).toBeNull();
    expect(html(f).querySelectorAll('tbody tr').length).toBe(2);
    expect(html(f).querySelector('a[href="/messages/msg-1"]')).not.toBeNull();
  });

  it('shows the empty state when nothing matches', () => {
    flushList([]);
    f.detectChanges();
    expect(html(f).textContent).toContain('No messages found');
  });

  it('shows an actionable error with retry, and reload fetches again', () => {
    ctrl.expectOne((r) => r.url === '/api/v1/messages').flush({ code: 'SERVICE_UNAVAILABLE', message: 'down', correlationId: 'c-1' }, { status: 503, statusText: 'x' });
    f.detectChanges();
    expect(html(f).querySelector('[role=alert]')?.textContent).toContain('temporarily unavailable');
    f.componentInstance.reload();
    flushList([summary('msg-9')]);
    f.detectChanges();
    expect(html(f).querySelector('[role=alert]')).toBeNull();
  });

  it('applies filters and resets to page 0', () => {
    flushList([]);
    const c = f.componentInstance;
    c.page.set(3);
    c.form.patchValue({ status: 'FAILED', channel: 'SMS', from: '2026-01-02', q: ' ref ' });
    c.apply();
    const req = ctrl.expectOne((r) => r.url === '/api/v1/messages');
    expect(req.request.params.get('status')).toBe('FAILED');
    expect(req.request.params.get('channel')).toBe('SMS');
    expect(req.request.params.get('q')).toBe('ref');
    expect(req.request.params.get('from')).toBe('2026-01-02T00:00:00Z');
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('sort')).toBe('createdAt,desc');
    req.flush(apiPage([]));
  });

  it('requests the selected page and size when paging', () => {
    flushList([summary('a')], 100);
    f.componentInstance.onPage({ page: 2, size: 50 });
    const req = ctrl.expectOne((r) => r.url === '/api/v1/messages');
    expect(req.request.params.get('page')).toBe('2');
    expect(req.request.params.get('size')).toBe('50');
    req.flush(apiPage([], { page: 2, size: 50, totalElements: 100 }));
  });

  it('clear resets the form and reloads', () => {
    flushList([]);
    f.componentInstance.form.patchValue({ status: 'FAILED' });
    f.componentInstance.clear();
    const req = ctrl.expectOne((r) => r.url === '/api/v1/messages');
    expect(req.request.params.has('status')).toBeFalse();
    req.flush(apiPage([]));
  });

  it('offers "New message" only to roles that may submit', () => {
    flushList([]);
    f.detectChanges();
    expect(html(f).textContent).toContain('New message');
  });
});

describe('MessageListPage (read-only role)', () => {
  it('hides the New message button for auditors (UX only)', () => {
    seedSession(['AUDITOR']);
    TestBed.configureTestingModule({ providers: testProviders() });
    const ctrl = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(MessageListPage);
    f.detectChanges();
    ctrl.expectOne((r) => r.url === '/api/v1/messages').flush(apiPage([]));
    f.detectChanges();
    expect(html(f).textContent).not.toContain('New message');
    clearSession();
  });
});

describe('MessageSubmitPage', () => {
  let ctrl: HttpTestingController;
  let f: ComponentFixture<MessageSubmitPage>;
  let page: MessageSubmitPage;

  beforeEach(() => {
    seedSession(['SENDER']);
    TestBed.configureTestingModule({ providers: testProviders() });
    ctrl = TestBed.inject(HttpTestingController);
    f = TestBed.createComponent(MessageSubmitPage);
    page = f.componentInstance;
    f.detectChanges();
    ctrl.expectOne((r) => r.url === '/api/v1/templates').flush(apiPage([{ id: 't1', code: 'TRADE_CONFIRMATION', channel: 'EMAIL', category: 'TRANSACTIONAL' }]));
    f.detectChanges();
  });
  afterEach(() => { ctrl.verify(); clearSession(); });

  const fillValid = () => page.form.patchValue({ recipientId: 'rcp-001', templateCode: 'TRADE_CONFIRMATION', locale: 'en-US' });

  it('auto-generates an idempotency key and can regenerate it', () => {
    const first = page.idempotencyKey();
    expect(first.length).toBeGreaterThan(8);
    expect(html(f).querySelector('[data-testid=idem-key]')?.textContent).toContain(first);
    page.regenerateKey();
    expect(page.idempotencyKey()).not.toBe(first);
  });

  it('loads published templates for the selected channel and reloads on channel change', () => {
    expect(page.templates().length).toBe(1);
    page.form.controls.channel.setValue('SMS');
    const req = ctrl.expectOne((r) => r.url === '/api/v1/templates');
    expect(req.request.params.get('channel')).toBe('SMS');
    expect(req.request.params.get('status')).toBe('PUBLISHED');
    req.flush(apiPage([]));
    expect(page.templates().length).toBe(0);
  });

  it('does not submit an invalid form (no recipient, no template)', () => {
    page.submit();
    ctrl.expectNone('/api/v1/messages');
    expect(page.form.touched).toBeTrue();
    expect(page.form.errors?.['oneOfRequired']).toBeTruthy();
  });

  it('validates channel specific options', () => {
    page.form.patchValue({ channel: 'SMS' });
    ctrl.expectOne((r) => r.url === '/api/v1/templates').flush(apiPage([]));
    page.form.controls.sms.controls.senderId.setValue('x');
    expect(page.form.controls.sms.controls.senderId.hasError('senderId')).toBeTrue();
    page.form.controls.sms.controls.senderId.setValue('ACME');
    expect(page.form.controls.sms.controls.senderId.valid).toBeTrue();
    page.form.patchValue({ channel: 'EMAIL' });
    ctrl.expectOne((r) => r.url === '/api/v1/templates').flush(apiPage([]));
    page.form.controls.email.controls.replyTo.setValue('bad');
    expect(page.form.controls.email.controls.replyTo.hasError('email')).toBeTrue();
  });

  it('drops a fallback equal to a newly chosen primary channel', () => {
    page.form.controls.fallbackChannels.setValue(['SMS']);
    page.form.controls.channel.setValue('SMS');
    ctrl.expectOne((r) => r.url === '/api/v1/templates').flush(apiPage([]));
    expect(page.form.controls.fallbackChannels.value).toEqual([]);
  });

  it('flags duplicate variable keys', () => {
    page.addVariable('a', '1');
    page.addVariable('a', '2');
    expect(page.variables.errors?.['duplicateKeys']).toBeTrue();
    page.removeVariable(1);
    expect(page.variables.errors).toBeNull();
  });

  it('submits with the Idempotency-Key and shows the 202 result', () => {
    fillValid();
    page.addVariable('amount', '250.00');
    const key = page.idempotencyKey();
    page.submit();
    const req = ctrl.expectOne('/api/v1/messages');
    expect(req.request.headers.get('Idempotency-Key')).toBe(key);
    expect(req.request.body.variables).toEqual({ amount: '250.00' });
    req.flush({ id: 'msg-77', status: 'VALIDATED', channel: 'EMAIL', correlationId: 'corr-1' }, { status: 202, statusText: 'Accepted' });
    f.detectChanges();
    expect(html(f).textContent).toContain('Accepted (HTTP 202)');
    expect(html(f).querySelector('a[href="/messages/msg-77"]')).not.toBeNull();
  });

  it('labels an idempotent replay', () => {
    fillValid();
    page.submit();
    ctrl.expectOne('/api/v1/messages').flush({ id: 'msg-1', status: 'QUEUED', channel: 'EMAIL', correlationId: 'c' }, { status: 200, statusText: 'OK', headers: { 'Idempotent-Replay': 'true' } });
    f.detectChanges();
    expect(html(f).textContent).toContain('Idempotent replay (HTTP 200)');
  });

  it('shows standardized error details from the API', () => {
    fillValid();
    page.submit();
    ctrl.expectOne('/api/v1/messages').flush({
      status: 400, code: 'VALIDATION_FAILED', message: 'Validation failed', correlationId: 'corr-err',
      details: [{ field: 'variables.amount', message: 'must be numeric' }],
    }, { status: 400, statusText: 'Bad Request' });
    f.detectChanges();
    const alert = html(f).querySelector('[role=alert]')!;
    expect(alert.textContent).toContain('variables.amount');
    expect(alert.textContent).toContain('must be numeric');
    expect(alert.textContent).toContain('corr-err');
    expect(page.submitting()).toBeFalse();
  });

  it('shows the idempotency-reuse guidance on 422', () => {
    fillValid();
    page.submit();
    ctrl.expectOne('/api/v1/messages').flush({ code: 'IDEMPOTENCY_KEY_REUSED', message: 'x' }, { status: 422, statusText: 'Unprocessable' });
    f.detectChanges();
    expect(html(f).textContent).toContain('Generate a new key');
  });
});

describe('MessageDetailPage', () => {
  let ctrl: HttpTestingController;

  const detail = (status: MessageDetail['status']): MessageDetail => ({
    id: 'm1', tenantId: 'tenant-retail', recipient: { id: 'r1', externalRef: 'CUST-1', maskedAddress: 'r***@example.test' },
    channel: 'EMAIL', category: 'TRANSACTIONAL', priority: 'NORMAL', status, terminal: status === 'DELIVERED',
    template: { code: 'TRADE_CONFIRMATION', versionNo: 2, locale: 'en-US' }, correlationId: 'corr', attemptCount: 1, maxAttempts: 5,
    createdAt: '2026-01-01T10:00:00Z', updatedAt: '2026-01-01T10:01:00Z',
    attempts: [{ attemptNo: 1, channel: 'EMAIL', providerId: 'sim-email', outcome: 'ACCEPTED', startedAt: '2026-01-01T10:00:30Z', latencyMs: 80 }],
    deliveryEvents: [{ providerId: 'sim-email', type: 'DELIVERED', occurredAt: '2026-01-01T10:01:00Z', applied: true }],
  });

  function open(roles: Parameters<typeof seedSession>[0], status: MessageDetail['status']): ComponentFixture<MessageDetailPage> {
    seedSession(roles);
    TestBed.configureTestingModule({ providers: testProviders() });
    ctrl = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(MessageDetailPage);
    f.componentRef.setInput('id', 'm1');
    f.detectChanges();
    ctrl.expectOne('/api/v1/messages/m1').flush(detail(status));
    ctrl.expectOne('/api/v1/messages/m1/history').flush([
      { at: '2026-01-01T10:00:00Z', kind: 'AUDIT', type: 'MESSAGE_ACCEPTED', summary: 'MESSAGE_ACCEPTED',
        detail: { actor: 'sender-retail', to: 'VALIDATED' } },
      { at: '2026-01-01T10:01:00Z', kind: 'DELIVERY_EVENT', type: 'DELIVERED', summary: 'Provider receipt DELIVERED',
        detail: { providerId: 'sim-email', applied: true, ignoredReason: null } },
    ]);
    f.detectChanges();
    return f;
  }
  afterEach(() => { ctrl.verify(); clearSession(); });

  it('renders summary, masked recipient, attempts and the lifecycle timeline', () => {
    const f = open(['SENDER'], 'DELIVERED');
    const t = html(f).textContent ?? '';
    expect(t).toContain('r***@example.test');
    expect(t).toContain('TRADE_CONFIRMATION');
    expect(t).toContain('sim-email');
    expect(html(f).querySelectorAll('app-timeline li').length).toBe(2);
    expect(t).toContain('Provider receipt DELIVERED');
    expect(t).toContain('sender-retail');
    expect(t).toContain('providerId: sim-email');
    expect(t).toContain('never returned by the API');
  });

  it('shows Cancel for cancellable states when the role allows it', () => {
    const f = open(['SENDER'], 'QUEUED');
    expect(html(f).textContent).toContain('Cancel message');
    expect(html(f).textContent).not.toContain('Requeue');
  });

  it('hides Cancel for terminal states and for roles without permission', () => {
    expect(html(open(['SENDER'], 'DELIVERED')).textContent).not.toContain('Cancel message');
  });

  it('hides Cancel from auditors even for QUEUED messages', () => {
    expect(html(open(['AUDITOR'], 'QUEUED')).textContent).not.toContain('Cancel message');
  });

  it('cancel asks for confirmation, calls the API, then reloads', () => {
    const f = open(['OPERATOR'], 'QUEUED');
    spyOn(TestBed.inject(ConfirmService), 'confirm').and.returnValue(of(true));
    f.componentInstance.cancel();
    ctrl.expectOne('/api/v1/messages/m1/cancel').flush({});
    ctrl.expectOne('/api/v1/messages/m1').flush(detail('CANCELLED'));
    ctrl.expectOne('/api/v1/messages/m1/history').flush([]);
    expect(f.componentInstance.notice()).toBe('Message cancelled.');
  });

  it('does nothing when the confirmation is declined', () => {
    const f = open(['OPERATOR'], 'QUEUED');
    spyOn(TestBed.inject(ConfirmService), 'confirm').and.returnValue(of(false));
    f.componentInstance.cancel();
    expect(ctrl.match('/api/v1/messages/m1/cancel').length).toBe(0);
  });

  it('surfaces a 409 illegal transition from cancel', () => {
    const f = open(['OPERATOR'], 'QUEUED');
    spyOn(TestBed.inject(ConfirmService), 'confirm').and.returnValue(of(true));
    f.componentInstance.cancel();
    ctrl.expectOne('/api/v1/messages/m1/cancel').flush({ code: 'ILLEGAL_STATE_TRANSITION', message: 'x' }, { status: 409, statusText: 'Conflict' });
    expect(f.componentInstance.actionError()?.code).toBe('ILLEGAL_STATE_TRANSITION');
  });

  it('offers Requeue to operators for FAILED messages', () => {
    const f = open(['OPERATOR'], 'FAILED');
    expect(html(f).textContent).toContain('Requeue');
  });
});
