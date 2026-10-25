import { HttpTestingController, TestRequest } from '@angular/common/http/testing';
import { ComponentFixture, TestBed, discardPeriodicTasks, fakeAsync, tick } from '@angular/core/testing';
import { of } from 'rxjs';
import { BulkJob } from '../../core/models/api.models';
import { ConfirmService } from '../../shared/confirm-dialog';
import { apiPage, clearSession, seedSession, testProviders } from '../../testing/test-utils';
import { BulkJobDetailPage, POLL_INTERVAL_MS, isActiveJob, progressPercent } from './bulk-job-detail.page';
import { BulkJobListPage, MAX_UPLOAD_BYTES, validateCsvFile } from './bulk-job-list.page';

const html = (f: ComponentFixture<unknown>): HTMLElement => f.nativeElement as HTMLElement;
const job = (over: Partial<BulkJob> = {}): BulkJob => ({
  id: 'j1', name: 'Run 1', status: 'RUNNING', total: 100, processed: 25, succeeded: 24, failed: 1, duplicate: 0, ...over,
});

describe('bulk job helpers', () => {
  it('validateCsvFile checks presence, extension, emptiness and size', () => {
    expect(validateCsvFile(null)).toContain('Choose');
    expect(validateCsvFile(new File(['a'], 'x.txt'))).toContain('.csv');
    expect(validateCsvFile(new File([], 'x.csv'))).toContain('empty');
    expect(validateCsvFile(new File(['a'], 'data.CSV'))).toBeNull();
    const big = { name: 'big.csv', size: MAX_UPLOAD_BYTES + 1 } as File;
    expect(validateCsvFile(big)).toContain('10 MB');
  });

  it('progressPercent is bounded and safe for zero totals', () => {
    expect(progressPercent({ processed: 25, total: 100 })).toBe(25);
    expect(progressPercent({ processed: 5, total: 0 })).toBe(0);
    expect(progressPercent({ processed: 150, total: 100 })).toBe(100);
  });

  it('isActiveJob is true only for CREATED/RUNNING', () => {
    expect(isActiveJob({ status: 'RUNNING' })).toBeTrue();
    expect(isActiveJob({ status: 'CREATED' })).toBeTrue();
    expect(isActiveJob({ status: 'COMPLETED' })).toBeFalse();
    expect(isActiveJob({ status: 'STOPPED' })).toBeFalse();
  });
});

describe('BulkJobListPage', () => {
  let ctrl: HttpTestingController;
  let f: ComponentFixture<BulkJobListPage>;
  beforeEach(() => {
    seedSession(['SENDER']);
    TestBed.configureTestingModule({ providers: testProviders() });
    ctrl = TestBed.inject(HttpTestingController);
    f = TestBed.createComponent(BulkJobListPage);
    f.detectChanges();
    ctrl.expectOne((r) => r.url === '/api/v1/bulk-jobs').flush(apiPage([job()]));
    f.detectChanges();
  });
  afterEach(() => { ctrl.verify(); clearSession(); });

  it('lists jobs with a link to the detail page', () => {
    expect(html(f).querySelector('a[href="/bulk-jobs/j1"]')).not.toBeNull();
  });

  it('does not submit an upload without a file', () => {
    f.componentInstance.uploadForm.patchValue({ name: 'n', templateCode: 'PROMO_MAIL' });
    f.componentInstance.upload();
    ctrl.expectNone('/api/v1/bulk-jobs');
    expect(f.componentInstance.fileError()).toContain('Choose');
  });

  it('rejects an invalid template code on the upload form', () => {
    f.componentInstance.uploadForm.patchValue({ name: 'n', templateCode: 'lowercase' });
    expect(f.componentInstance.uploadForm.controls.templateCode.invalid).toBeTrue();
  });

  it('validates synthetic job rates between 0 and 1', () => {
    f.componentInstance.synthForm.patchValue({ duplicateRate: 1.5 });
    expect(f.componentInstance.synthForm.controls.duplicateRate.invalid).toBeTrue();
    f.componentInstance.synthForm.patchValue({ duplicateRate: 0.5, rows: 0 });
    expect(f.componentInstance.synthForm.controls.rows.invalid).toBeTrue();
  });

  it('generates a synthetic job with the form values', () => {
    f.componentInstance.synthForm.setValue({ rows: 250, duplicateRate: 0.1, invalidRate: 0.05 });
    f.componentInstance.generate();
    const req = ctrl.expectOne('/api/v1/bulk-jobs/generate-synthetic');
    expect(req.request.body).toEqual({ rows: 250, duplicateRate: 0.1, invalidRate: 0.05 });
    req.flush(job({ id: 'j9' }), { status: 202, statusText: 'Accepted' });
  });

  it('shows the API error when creation fails', () => {
    f.componentInstance.generate();
    ctrl.expectOne('/api/v1/bulk-jobs/generate-synthetic').flush({ code: 'FORBIDDEN', message: 'no' }, { status: 403, statusText: 'x' });
    expect(f.componentInstance.createError()?.code).toBe('FORBIDDEN');
    expect(f.componentInstance.busy()).toBeFalse();
  });
});

describe('BulkJobDetailPage progress polling', () => {
  let ctrl: HttpTestingController;
  beforeEach(() => {
    seedSession(['SENDER']);
    TestBed.configureTestingModule({ providers: testProviders() });
    ctrl = TestBed.inject(HttpTestingController);
  });
  afterEach(() => clearSession());

  const jobReq = (): TestRequest => ctrl.expectOne('/api/v1/bulk-jobs/j1');
  const failuresReq = (): TestRequest => ctrl.expectOne((r) => r.url === '/api/v1/bulk-jobs/j1/failures');

  function open(): ComponentFixture<BulkJobDetailPage> {
    const f = TestBed.createComponent(BulkJobDetailPage);
    f.componentRef.setInput('id', 'j1');
    f.detectChanges();
    return f;
  }

  it('polls until the job reaches a terminal status, then stops and refreshes failures', fakeAsync(() => {
    const f = open();
    tick(0);
    jobReq().flush(job({ processed: 10 }));
    failuresReq().flush(apiPage([]));
    f.detectChanges();
    expect(html(f).textContent).toContain('10 / 100');
    expect(html(f).textContent).toContain('auto-refreshing');

    tick(POLL_INTERVAL_MS);
    jobReq().flush(job({ processed: 60 }));
    expect(f.componentInstance.percent()).toBe(60);

    tick(POLL_INTERVAL_MS);
    jobReq().flush(job({ processed: 100, status: 'COMPLETED_WITH_ERRORS', failed: 3 }));
    failuresReq().flush(apiPage([{ lineNo: 4, reason: 'INVALID_EMAIL' }]));
    f.detectChanges();
    expect(html(f).textContent).toContain('INVALID_EMAIL');
    expect(html(f).textContent).not.toContain('auto-refreshing');

    tick(POLL_INTERVAL_MS * 5);
    ctrl.expectNone('/api/v1/bulk-jobs/j1');
    discardPeriodicTasks();
  }));

  it('stops polling when the component is destroyed', fakeAsync(() => {
    const f = open();
    tick(0);
    jobReq().flush(job());
    failuresReq().flush(apiPage([]));
    f.destroy();
    tick(POLL_INTERVAL_MS * 3);
    expect(ctrl.match('/api/v1/bulk-jobs/j1').length).toBe(0);
  }));

  it('shows a polling error and allows retry', fakeAsync(() => {
    const f = open();
    tick(0);
    jobReq().flush({ code: 'SERVICE_UNAVAILABLE' }, { status: 503, statusText: 'x' });
    failuresReq().flush(apiPage([]));
    f.detectChanges();
    expect(f.componentInstance.pollError()?.code).toBe('SERVICE_UNAVAILABLE');
    f.componentInstance.startPolling();
    tick(0);
    jobReq().flush(job({ status: 'COMPLETED', processed: 100 }));
    expect(f.componentInstance.pollError()).toBeNull();
    discardPeriodicTasks();
  }));

  it('stop asks for confirmation and POSTs /stop', fakeAsync(() => {
    const f = open();
    tick(0);
    jobReq().flush(job());
    failuresReq().flush(apiPage([]));
    spyOn(TestBed.inject(ConfirmService), 'confirm').and.returnValue(of(true));
    f.componentInstance.stop();
    ctrl.expectOne('/api/v1/bulk-jobs/j1/stop').flush({});
    tick(0);
    jobReq().flush(job({ status: 'STOPPED' }));
    f.detectChanges();
    expect(html(f).textContent).toContain('Restart');
    discardPeriodicTasks();
  }));

  it('restart POSTs /restart and resumes polling', fakeAsync(() => {
    const f = open();
    tick(0);
    jobReq().flush(job({ status: 'STOPPED' }));
    failuresReq().flush(apiPage([]));
    f.componentInstance.restart();
    ctrl.expectOne('/api/v1/bulk-jobs/j1/restart').flush({});
    tick(0);
    jobReq().flush(job({ status: 'RUNNING' }));
    expect(f.componentInstance.active()).toBeTrue();
    discardPeriodicTasks();
  }));
});
