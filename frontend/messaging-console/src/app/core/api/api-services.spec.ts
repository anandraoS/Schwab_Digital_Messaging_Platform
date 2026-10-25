import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { BulkJobsService } from './bulk-jobs.service';
import { EventsService } from './events.service';
import { MessagesService } from './messages.service';
import { OpsService } from './ops.service';
import { RecipientsService } from './recipients.service';
import { TemplatesService } from './templates.service';
import { toParams } from './api-utils';

describe('API services', () => {
  let ctrl: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    ctrl = TestBed.inject(HttpTestingController);
  });
  afterEach(() => ctrl.verify());

  it('toParams drops empty values', () => {
    const p = toParams({ a: 'x', b: '', c: null, d: undefined, e: 0 });
    expect(p.keys().sort()).toEqual(['a', 'e']);
    expect(p.get('e')).toBe('0');
  });

  describe('MessagesService', () => {
    it('list sends only non-empty filters', () => {
      TestBed.inject(MessagesService).list({ status: 'FAILED', channel: '', page: 2, size: 50 }).subscribe();
      const req = ctrl.expectOne((r) => r.url === '/api/v1/messages');
      expect(req.request.params.get('status')).toBe('FAILED');
      expect(req.request.params.has('channel')).toBeFalse();
      expect(req.request.params.get('page')).toBe('2');
      req.flush({ content: [], page: 2, size: 50, totalElements: 0, totalPages: 0 });
    });

    it('get and history hit the id-specific endpoints (id is URL-encoded)', () => {
      const s = TestBed.inject(MessagesService);
      s.get('a/b').subscribe();
      const get = ctrl.expectOne('/api/v1/messages/a%2Fb');
      expect(get.request.method).toBe('GET');
      get.flush({});
      s.history('m1').subscribe();
      const hist = ctrl.expectOne('/api/v1/messages/m1/history');
      expect(hist.request.method).toBe('GET');
      hist.flush([]);
    });

    it('submit sends Idempotency-Key and reports a 202 as a fresh accept', () => {
      let result: { httpStatus: number; replay: boolean } | undefined;
      TestBed.inject(MessagesService).submit({ channel: 'EMAIL', category: 'TRANSACTIONAL', priority: 'NORMAL', template: { code: 'X', locale: 'en' } }, 'key-1')
        .subscribe((r) => (result = r));
      const req = ctrl.expectOne('/api/v1/messages');
      expect(req.request.method).toBe('POST');
      expect(req.request.headers.get('Idempotency-Key')).toBe('key-1');
      req.flush({ id: 'm1', status: 'VALIDATED', channel: 'EMAIL', correlationId: 'c' }, { status: 202, statusText: 'Accepted' });
      expect(result).toEqual(jasmine.objectContaining({ httpStatus: 202, replay: false }));
    });

    it('submit detects an idempotent replay from the response header', () => {
      let replay = false;
      TestBed.inject(MessagesService).submit({ channel: 'SMS', category: 'SERVICE', priority: 'LOW', template: { code: 'X', locale: 'en' } }, 'k')
        .subscribe((r) => (replay = r.replay));
      ctrl.expectOne('/api/v1/messages').flush({ id: 'm1' }, { status: 200, statusText: 'OK', headers: { 'Idempotent-Replay': 'true' } });
      expect(replay).toBeTrue();
    });

    it('cancel and requeue POST to action endpoints', () => {
      const s = TestBed.inject(MessagesService);
      s.cancel('m1').subscribe();
      expect(ctrl.expectOne('/api/v1/messages/m1/cancel').request.method).toBe('POST');
      s.requeue('m1').subscribe();
      expect(ctrl.expectOne('/api/v1/messages/m1/requeue').request.method).toBe('POST');
    });
  });

  describe('TemplatesService', () => {
    it('list passes filters', () => {
      TestBed.inject(TemplatesService).list({ channel: 'EMAIL', status: 'PUBLISHED', size: 200 }).subscribe();
      const req = ctrl.expectOne((r) => r.url === '/api/v1/templates');
      expect(req.request.params.get('status')).toBe('PUBLISHED');
      req.flush({ content: [] });
    });

    it('act posts to the version action with the locale query param and optional comment', () => {
      const s = TestBed.inject(TemplatesService);
      s.act('t1', 3, 'en-US', 'reject', 'needs work').subscribe();
      const req = ctrl.expectOne((r) => r.url === '/api/v1/templates/t1/versions/3/reject');
      expect(req.request.params.get('locale')).toBe('en-US');
      expect(req.request.body).toEqual({ comment: 'needs work' });
      req.flush({});
      s.act('t1', 3, 'en-US', 'approve').subscribe();
      const req2 = ctrl.expectOne((r) => r.url === '/api/v1/templates/t1/versions/3/approve');
      expect(req2.request.body).toEqual({});
      req2.flush({});
    });

    it('updateVersion PUTs the draft with locale', () => {
      TestBed.inject(TemplatesService).updateVersion('t1', 2, 'es', { locale: 'es', body: 'b', variables: [], version: 4 }).subscribe();
      const req = ctrl.expectOne((r) => r.url === '/api/v1/templates/t1/versions/2');
      expect(req.request.method).toBe('PUT');
      expect(req.request.params.get('locale')).toBe('es');
      req.flush({});
    });

    it('preview posts to /templates/preview', () => {
      TestBed.inject(TemplatesService).preview({ channel: 'EMAIL', body: 'x', variables: [], sampleData: {}, locale: 'en' }).subscribe();
      expect(ctrl.expectOne('/api/v1/templates/preview').request.method).toBe('POST');
    });
  });

  it('RecipientsService.updatePreferences sends the version for optimistic locking', () => {
    TestBed.inject(RecipientsService).updatePreferences('r1', {
      marketingOptIn: true, emailEnabled: true, smsEnabled: false, pushEnabled: true, quietStart: '21:00', quietEnd: '08:00', fallbackOrder: [], version: 7,
    }).subscribe();
    const req = ctrl.expectOne('/api/v1/recipients/r1/preferences');
    expect(req.request.method).toBe('PUT');
    expect((req.request.body as { version: number }).version).toBe(7);
    req.flush({});
  });

  it('RecipientsService.search passes q/page/size', () => {
    TestBed.inject(RecipientsService).search('rec', 1, 5).subscribe();
    const req = ctrl.expectOne((r) => r.url === '/api/v1/recipients');
    expect(req.request.params.get('q')).toBe('rec');
    expect(req.request.params.get('size')).toBe('5');
    req.flush({ content: [] });
  });

  describe('BulkJobsService', () => {
    it('upload sends multipart form data with the file and parameters', () => {
      const file = new File(['externalRef\nA'], 'x.csv', { type: 'text/csv' });
      TestBed.inject(BulkJobsService).upload(file, { name: 'n', templateCode: 'T_CODE', channel: 'EMAIL', category: 'MARKETING', ratePerSecond: 10 }).subscribe();
      const req = ctrl.expectOne('/api/v1/bulk-jobs');
      const body = req.request.body as FormData;
      expect(body instanceof FormData).toBeTrue();
      expect((body.get('file') as File).name).toBe('x.csv');
      expect(body.get('templateCode')).toBe('T_CODE');
      expect(body.get('ratePerSecond')).toBe('10');
      req.flush({});
    });

    it('generateSynthetic, stop and restart use the contract paths', () => {
      const s = TestBed.inject(BulkJobsService);
      s.generateSynthetic({ rows: 10, duplicateRate: 0.1, invalidRate: 0 }).subscribe();
      expect(ctrl.expectOne('/api/v1/bulk-jobs/generate-synthetic').request.body).toEqual({ rows: 10, duplicateRate: 0.1, invalidRate: 0 });
      s.stop('j1').subscribe();
      ctrl.expectOne('/api/v1/bulk-jobs/j1/stop').flush({});
      s.restart('j1').subscribe();
      ctrl.expectOne('/api/v1/bulk-jobs/j1/restart').flush({});
    });
  });

  it('OpsService hits queues, summary, providers and consumer controls', () => {
    const s = TestBed.inject(OpsService);
    s.queues().subscribe();
    ctrl.expectOne('/api/v1/ops/queues').flush({});
    s.summary().subscribe();
    ctrl.expectOne('/api/v1/ops/summary').flush({});
    s.providers().subscribe();
    ctrl.expectOne('/api/v1/providers').flush([]);
    s.pauseConsumers().subscribe();
    expect(ctrl.expectOne('/api/v1/ops/consumers/pause').request.method).toBe('POST');
    s.resumeConsumers().subscribe();
    ctrl.expectOne('/api/v1/ops/consumers/resume').flush({});
    s.setFaultMode('sim-email', { mode: 'TRANSIENT', probability: 0.5 }).subscribe();
    const req = ctrl.expectOne('/api/v1/providers/sim-email/fault-mode');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ mode: 'TRANSIENT', probability: 0.5 });
    req.flush({});
  });

  it('EventsService dead-letter, delivery-event and audit endpoints', () => {
    const s = TestBed.inject(EventsService);
    s.replay('m1').subscribe();
    ctrl.expectOne('/api/v1/dead-letters/m1/replay').flush({});
    s.discard('m1').subscribe();
    ctrl.expectOne('/api/v1/dead-letters/m1/discard').flush({});
    s.deliveryEvents({ type: 'BOUNCED', applied: 'false' }).subscribe();
    const r = ctrl.expectOne((q) => q.url === '/api/v1/delivery-events');
    expect(r.request.params.get('applied')).toBe('false');
    r.flush({ content: [] });
    s.audit({ entityType: 'MESSAGE', actor: '' }).subscribe();
    const a = ctrl.expectOne((q) => q.url === '/api/v1/audit-events');
    expect(a.request.params.has('actor')).toBeFalse();
    a.flush({ content: [] });
  });
});
