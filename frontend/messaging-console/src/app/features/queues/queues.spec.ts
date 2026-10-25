import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed, discardPeriodicTasks, fakeAsync, tick } from '@angular/core/testing';
import { QueueStats } from '../../core/models/api.models';
import { clearSession, seedSession, testProviders } from '../../testing/test-utils';
import { QUEUE_REFRESH_MS, QueuesPage, formatAge } from './queues.page';

const stats = (over: Partial<QueueStats> = {}): QueueStats => ({
  depthByPriority: { CRITICAL: 1, HIGH: 2, NORMAL: 30, LOW: 4 }, inFlight: 5, consumers: 8, dlqDepth: 3, retryPending: 7, oldestMessageAgeSeconds: 3725, ...over,
});
const html = (f: ComponentFixture<unknown>): HTMLElement => f.nativeElement as HTMLElement;

describe('formatAge', () => {
  it('formats seconds, minutes and hours', () => {
    expect(formatAge(5)).toBe('5s');
    expect(formatAge(65)).toBe('1m 5s');
    expect(formatAge(3725)).toBe('1h 2m 5s');
    expect(formatAge(3600)).toBe('1h 0m 0s');
  });
  it('handles missing and negative values', () => {
    expect(formatAge(undefined)).toBe('—');
    expect(formatAge(-1)).toBe('—');
  });
});

describe('QueuesPage', () => {
  let ctrl: HttpTestingController;
  beforeEach(() => {
    seedSession(['OPERATOR']);
    TestBed.configureTestingModule({ providers: testProviders() });
    ctrl = TestBed.inject(HttpTestingController);
  });
  afterEach(() => clearSession());

  it('loads immediately and shows depth, in-flight, DLQ, retry-pending and oldest age', fakeAsync(() => {
    const f = TestBed.createComponent(QueuesPage);
    f.detectChanges();
    tick(0);
    ctrl.expectOne('/api/v1/ops/queues').flush(stats());
    f.detectChanges();
    const t = html(f).textContent ?? '';
    expect(t).toContain('In flight');
    expect(t).toContain('Dead-letter depth');
    expect(t).toContain('Retry pending');
    expect(t).toContain('1h 2m 5s');
    expect(html(f).querySelectorAll('svg rect').length).toBe(4);
    discardPeriodicTasks();
  }));

  it('auto-refreshes every interval while enabled', fakeAsync(() => {
    const f = TestBed.createComponent(QueuesPage);
    f.detectChanges();
    tick(0);
    ctrl.expectOne('/api/v1/ops/queues').flush(stats());
    tick(QUEUE_REFRESH_MS);
    ctrl.expectOne('/api/v1/ops/queues').flush(stats({ inFlight: 9 }));
    expect(f.componentInstance.stats()?.inFlight).toBe(9);
    discardPeriodicTasks();
  }));

  it('stops polling when auto-refresh is switched off', fakeAsync(() => {
    const f = TestBed.createComponent(QueuesPage);
    f.detectChanges();
    tick(0);
    ctrl.expectOne('/api/v1/ops/queues').flush(stats());
    f.componentInstance.auto.set(false);
    tick(QUEUE_REFRESH_MS * 3);
    expect(ctrl.match('/api/v1/ops/queues').length).toBe(0);
    discardPeriodicTasks();
  }));

  it('stops polling when the component is destroyed', fakeAsync(() => {
    const f = TestBed.createComponent(QueuesPage);
    f.detectChanges();
    tick(0);
    ctrl.expectOne('/api/v1/ops/queues').flush(stats());
    f.destroy();
    tick(QUEUE_REFRESH_MS * 2);
    expect(ctrl.match('/api/v1/ops/queues').length).toBe(0);
  }));

  it('shows an error banner and keeps polling after a failure', fakeAsync(() => {
    const f = TestBed.createComponent(QueuesPage);
    f.detectChanges();
    tick(0);
    ctrl.expectOne('/api/v1/ops/queues').flush({ code: 'SERVICE_UNAVAILABLE' }, { status: 503, statusText: 'x' });
    f.detectChanges();
    expect(html(f).querySelector('[role=alert]')).not.toBeNull();
    tick(QUEUE_REFRESH_MS);
    ctrl.expectOne('/api/v1/ops/queues').flush(stats());
    f.detectChanges();
    expect(html(f).querySelector('[role=alert]')).toBeNull();
    discardPeriodicTasks();
  }));

  it('announces paused consumers', fakeAsync(() => {
    const f = TestBed.createComponent(QueuesPage);
    f.detectChanges();
    tick(0);
    ctrl.expectOne('/api/v1/ops/queues').flush(stats({ consumersPaused: true }));
    f.detectChanges();
    expect(html(f).textContent).toContain('Consumers are paused');
    discardPeriodicTasks();
  }));
});
