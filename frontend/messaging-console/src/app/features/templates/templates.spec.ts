import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { Role, TemplateDetail, TemplateVersion } from '../../core/models/api.models';
import { ConfirmService } from '../../shared/confirm-dialog';
import { apiPage, clearSession, seedSession, testProviders } from '../../testing/test-utils';
import { TemplateDetailPage, versionActions } from './template-detail.page';
import { TemplateListPage } from './template-list.page';
import { parseSampleData, parseVariableList, placeholdersIn } from './template-editor';

const html = (f: ComponentFixture<unknown>): HTMLElement => f.nativeElement as HTMLElement;
const ver = (over: Partial<TemplateVersion>): TemplateVersion => ({
  versionNo: 1, locale: 'en-US', status: 'DRAFT', body: 'Hello {{name}}', variables: ['name'], createdBy: 'author-1', ...over,
});

describe('versionActions (UX gating)', () => {
  it('lets authors edit and submit drafts only', () => {
    const a = versionActions(ver({ status: 'DRAFT' }), ['TEMPLATE_AUTHOR'], 'author-1');
    expect(a.edit && a.submit).toBeTrue();
    expect(a.approve || a.reject || a.retire).toBeFalse();
    const p = versionActions(ver({ status: 'PUBLISHED' }), ['TEMPLATE_AUTHOR'], 'author-1');
    expect(p.edit || p.submit).toBeFalse();
  });

  it('lets approvers approve/reject pending versions authored by someone else', () => {
    const a = versionActions(ver({ status: 'PENDING_APPROVAL' }), ['TEMPLATE_APPROVER'], 'approver-1');
    expect(a.approve && a.reject).toBeTrue();
    expect(a.fourEyesBlocked).toBeFalse();
  });

  it('flags four-eyes when the approver authored the version', () => {
    const a = versionActions(ver({ status: 'PENDING_APPROVAL', createdBy: 'same-person' }), ['TEMPLATE_APPROVER'], 'same-person');
    expect(a.fourEyesBlocked).toBeTrue();
  });

  it('allows retire for approvers and tenant admins on PUBLISHED only', () => {
    expect(versionActions(ver({ status: 'PUBLISHED' }), ['TEMPLATE_APPROVER'], 'x').retire).toBeTrue();
    expect(versionActions(ver({ status: 'PUBLISHED' }), ['TENANT_ADMIN'], 'x').retire).toBeTrue();
    expect(versionActions(ver({ status: 'DRAFT' }), ['TENANT_ADMIN'], 'x').retire).toBeFalse();
    expect(versionActions(ver({ status: 'PUBLISHED' }), ['SENDER'], 'x').retire).toBeFalse();
  });

  it('gives auditors no actions at all', () => {
    for (const s of ['DRAFT', 'PENDING_APPROVAL', 'PUBLISHED'] as const) {
      const a = versionActions(ver({ status: s }), ['AUDITOR'], 'x');
      expect(Object.values(a).some(Boolean)).toBeFalse();
    }
  });
});

describe('template editor helpers', () => {
  it('parseVariableList splits on commas/whitespace and de-duplicates', () => {
    expect(parseVariableList('a, b  c,a\nd')).toEqual(['a', 'b', 'c', 'd']);
    expect(parseVariableList('')).toEqual([]);
  });
  it('parseSampleData reads key=value lines', () => {
    expect(parseSampleData('name=Ann\nbad line\namount = 5=5')).toEqual({ name: 'Ann', amount: '5=5' });
  });
  it('placeholdersIn finds unique {{vars}}', () => {
    expect(placeholdersIn('Hi {{ name }}, {{name}} owes {{amount}}')).toEqual(['name', 'amount']);
  });
});

describe('TemplateListPage', () => {
  it('lists templates and offers creation to authors only', () => {
    seedSession(['TEMPLATE_AUTHOR']);
    TestBed.configureTestingModule({ providers: testProviders() });
    const ctrl = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(TemplateListPage);
    f.detectChanges();
    ctrl.expectOne((r) => r.url === '/api/v1/templates').flush(apiPage([{ id: 't1', code: 'OTP_CODE', channel: 'SMS', category: 'SECURITY_ALERT', name: 'One-time code', latestVersionNo: 2, publishedLocales: ['en-US'], versionCounts: { PUBLISHED: 1, DRAFT: 1 } }]));
    f.detectChanges();
    expect(html(f).textContent).toContain('OTP_CODE');
    expect(html(f).textContent).toContain('One-time code');
    expect(html(f).textContent).toContain('PUBLISHED 1, DRAFT 1');
    expect(html(f).textContent).toContain('en-US');
    expect(html(f).textContent).toContain('New template');
    ctrl.verify();
    clearSession();
  });

  it('create sends the template name (required by the backend) with the other fields', () => {
    seedSession(['TEMPLATE_AUTHOR']);
    TestBed.configureTestingModule({ providers: testProviders() });
    const ctrl = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(TemplateListPage);
    f.detectChanges();
    ctrl.expectOne((r) => r.url === '/api/v1/templates').flush(apiPage([]));
    f.componentInstance.create({ code: 'NEW_ONE', name: ' New one ', channel: 'EMAIL', category: 'SERVICE', locale: 'en', subject: 's', body: 'b {{x}}', variables: ['x'] });
    const req = ctrl.expectOne((r) => r.url === '/api/v1/templates' && r.method === 'POST');
    expect(req.request.body).toEqual({ code: 'NEW_ONE', name: 'New one', channel: 'EMAIL', category: 'SERVICE', locale: 'en', subject: 's', body: 'b {{x}}', variables: ['x'] });
    req.flush({ id: 't9' });
    ctrl.verify();
    clearSession();
  });

  it('hides creation from auditors', () => {
    seedSession(['AUDITOR']);
    TestBed.configureTestingModule({ providers: testProviders() });
    const ctrl = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(TemplateListPage);
    f.detectChanges();
    ctrl.expectOne((r) => r.url === '/api/v1/templates').flush(apiPage([]));
    f.detectChanges();
    expect(html(f).textContent).not.toContain('New template');
    ctrl.verify();
    clearSession();
  });
});

describe('TemplateDetailPage', () => {
  let ctrl: HttpTestingController;
  const detail: TemplateDetail = {
    id: 't1', code: 'TRADE_CONFIRMATION', channel: 'EMAIL', category: 'TRANSACTIONAL',
    versions: [
      ver({ versionNo: 1, status: 'PUBLISHED', approvedBy: 'approver-1' }),
      ver({ versionNo: 2, status: 'PENDING_APPROVAL', createdBy: 'author-1' }),
      ver({ versionNo: 3, status: 'DRAFT' }),
    ],
  };

  function open(roles: Role[], subject = 'tester'): ComponentFixture<TemplateDetailPage> {
    seedSession(roles, subject);
    TestBed.configureTestingModule({ providers: testProviders() });
    ctrl = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(TemplateDetailPage);
    f.componentRef.setInput('id', 't1');
    f.detectChanges();
    ctrl.expectOne('/api/v1/templates/t1').flush(detail);
    ctrl.expectOne('/api/v1/templates/t1/history').flush([]);
    f.detectChanges();
    return f;
  }
  const buttons = (f: ComponentFixture<unknown>): string[] => Array.from(html(f).querySelectorAll('button')).map((b) => (b.textContent ?? '').trim());
  afterEach(() => { ctrl.verify(); clearSession(); });

  it('shows versions grouped under the locale tab with statuses', () => {
    const f = open(['AUDITOR']);
    expect(html(f).textContent).toContain('TRADE_CONFIRMATION');
    expect(html(f).querySelectorAll('.ver').length).toBe(3);
    expect(html(f).textContent).toContain('PENDING APPROVAL');
  });

  it('shows author actions to template authors only', () => {
    const b = buttons(open(['TEMPLATE_AUTHOR']));
    expect(b).toContain('Submit for approval');
    expect(b).toContain('Edit');
    expect(b).not.toContain('Approve');
  });

  it('shows approve/reject/retire to approvers', () => {
    const b = buttons(open(['TEMPLATE_APPROVER'], 'approver-9'));
    expect(b).toContain('Approve');
    expect(b).toContain('Reject');
    expect(b).toContain('Retire');
    expect(b).not.toContain('Submit for approval');
  });

  it('disables Approve and explains four-eyes when the approver authored the version', () => {
    const f = open(['TEMPLATE_APPROVER'], 'author-1');
    const approve = Array.from(html(f).querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Approve')!;
    expect(approve.disabled).toBeTrue();
    expect(html(f).textContent).toContain('another approver must approve');
  });

  it('requires a comment before Reject is enabled', () => {
    const f = open(['TEMPLATE_APPROVER'], 'approver-9');
    const reject = () => Array.from(html(f).querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Reject')!;
    expect(reject().disabled).toBeTrue();
    f.componentInstance.setComment(detail.versions[1], 'typo in subject');
    f.detectChanges();
    expect(reject().disabled).toBeFalse();
  });

  it('approve confirms, then POSTs with the locale query', () => {
    const f = open(['TEMPLATE_APPROVER'], 'approver-9');
    spyOn(TestBed.inject(ConfirmService), 'confirm').and.returnValue(of(true));
    f.componentInstance.act(detail.versions[1], 'approve');
    const req = ctrl.expectOne((r) => r.url === '/api/v1/templates/t1/versions/2/approve');
    expect(req.request.params.get('locale')).toBe('en-US');
    req.flush({});
    ctrl.expectOne('/api/v1/templates/t1').flush(detail);
    ctrl.expectOne('/api/v1/templates/t1/history').flush([]);
  });

  it('shows the server error when the four-eyes rule is enforced by the API', () => {
    const f = open(['TEMPLATE_APPROVER'], 'approver-9');
    f.componentInstance.act(detail.versions[2], 'submit');
    ctrl.expectOne((r) => r.url === '/api/v1/templates/t1/versions/3/submit')
      .flush({ code: 'FORBIDDEN', message: 'four-eyes' }, { status: 403, statusText: 'Forbidden' });
    expect(f.componentInstance.actionError()?.code).toBe('FORBIDDEN');
  });

  it('renders template source as text, never as HTML', () => {
    seedSession(['AUDITOR']);
    TestBed.configureTestingModule({ providers: testProviders() });
    ctrl = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(TemplateDetailPage);
    f.componentRef.setInput('id', 't1');
    f.detectChanges();
    ctrl.expectOne('/api/v1/templates/t1').flush({ ...detail, versions: [ver({ body: '<script>alert(1)</script>' })] });
    ctrl.expectOne('/api/v1/templates/t1/history').flush([]);
    f.detectChanges();
    expect(html(f).querySelector('.src script')).toBeNull();
    expect(html(f).querySelector('.src')?.textContent).toContain('<script>');
  });
});
