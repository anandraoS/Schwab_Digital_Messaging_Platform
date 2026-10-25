import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { Loadable } from '../core/util/loadable';
import { NAV_ITEMS } from '../core/auth/access';
import { AuthService } from '../core/auth/auth.service';
import { toNormalizedError } from '../core/http/error-mapper';
import { ConfirmService } from '../shared/confirm-dialog';
import { ShellComponent } from '../layout/shell';
import { apiPage, clearSession, fakeJwt, seedSession, testProviders } from '../testing/test-utils';
import { AuditPage, auditDetailText } from './audit/audit.page';
import { DashboardPage } from './dashboard/dashboard.page';
import { DeadLettersPage } from './dead-letters/dead-letters.page';
import { LoginPage } from './login/login.page';
import { ProvidersPage, pct } from './providers/providers.page';
import { ProviderHealth } from '../core/models/api.models';

const html = (f: ComponentFixture<unknown>): HTMLElement => f.nativeElement as HTMLElement;

describe('Loadable', () => {
  it('moves through loading to data', () => {
    const l = new Loadable<number>();
    l.load(of(5));
    expect(l.data()).toBe(5);
    expect(l.loading()).toBeFalse();
    expect(l.error()).toBeNull();
  });
  it('captures errors as normalized errors', () => {
    const l = new Loadable<number>();
    l.load(throwError(() => new Error('nope')));
    expect(l.error()?.message).toBe('nope');
    expect(l.loading()).toBeFalse();
  });
  it('clears a previous error on the next successful load', () => {
    const l = new Loadable<number>();
    l.load(throwError(() => new Error('x')));
    l.load(of(1));
    expect(l.error()).toBeNull();
  });
  it('toNormalizedError is idempotent', () => {
    const n = toNormalizedError(new Error('a'));
    expect(toNormalizedError(n)).toBe(n);
  });
});

describe('LoginPage', () => {
  let ctrl: HttpTestingController;
  let f: ComponentFixture<LoginPage>;
  beforeEach(() => {
    clearSession();
    TestBed.configureTestingModule({ providers: testProviders() });
    ctrl = TestBed.inject(HttpTestingController);
    f = TestBed.createComponent(LoginPage);
    f.detectChanges();
  });
  afterEach(() => { ctrl.verify(); clearSession(); });

  it('renders all seven personas', () => {
    expect(html(f).querySelectorAll('button[data-persona]').length).toBe(7);
  });

  it('requests a dev token for the chosen persona and navigates to the dashboard', () => {
    const nav = spyOn(TestBed.inject(Router), 'navigateByUrl').and.resolveTo(true);
    html(f).querySelector<HTMLButtonElement>('button[data-persona="operator"]')!.click();
    const req = ctrl.expectOne('/dev/token');
    expect(req.request.body).toEqual({ subject: 'operator-retail', roles: ['OPERATOR'], tenant: 'tenant-retail' });
    req.flush({ access_token: fakeJwt({ sub: 'operator-retail', roles: ['OPERATOR'], tenant: 'tenant-retail' }), expires_in: 3600 });
    expect(nav).toHaveBeenCalledWith('/dashboard');
    expect(TestBed.inject(AuthService).roles()).toEqual(['OPERATOR']);
  });

  it('shows an actionable error when the token endpoint fails', () => {
    html(f).querySelector<HTMLButtonElement>('button[data-persona="sender"]')!.click();
    ctrl.expectOne('/dev/token').flush(null, { status: 404, statusText: 'Not Found' });
    f.detectChanges();
    expect(html(f).querySelector('[role=alert]')).not.toBeNull();
  });
});

describe('NAV_ITEMS role awareness', () => {
  const visible = (roles: Parameters<typeof seedSession>[0]): string[] => {
    seedSession(roles);
    TestBed.configureTestingModule({ providers: testProviders() });
    const auth = TestBed.inject(AuthService);
    return NAV_ITEMS.filter((i) => auth.hasAnyRole(i.roles)).map((i) => i.label);
  };
  afterEach(() => clearSession());

  it('platform admins see Admin and Queues', () => {
    const v = visible(['PLATFORM_ADMIN']);
    expect(v).toContain('Admin');
    expect(v).toContain('Queues');
  });
  it('auditors see audit and delivery events but not admin or new message', () => {
    const v = visible(['AUDITOR']);
    expect(v).toContain('Audit history');
    expect(v).toContain('Delivery events');
    expect(v).not.toContain('Admin');
    expect(v).not.toContain('New message');
  });
  it('senders can submit and run bulk jobs but not see queues', () => {
    const v = visible(['SENDER']);
    expect(v).toContain('New message');
    expect(v).toContain('Bulk jobs');
    expect(v).not.toContain('Queues');
  });
  it('template authors get templates but not dead letters', () => {
    const v = visible(['TEMPLATE_AUTHOR']);
    expect(v).toContain('Templates');
    expect(v).not.toContain('Dead letters');
  });
});

describe('ShellComponent', () => {
  it('shows the signed-in user and only permitted nav links', () => {
    seedSession(['OPERATOR'], 'operator-retail');
    TestBed.configureTestingModule({ providers: testProviders() });
    const f = TestBed.createComponent(ShellComponent);
    f.detectChanges();
    const t = html(f).textContent ?? '';
    expect(t).toContain('operator-retail');
    expect(t).toContain('Dead letters');
    expect(t).not.toContain('Admin');
    expect(html(f).querySelector('nav')).not.toBeNull();
    expect(html(f).querySelector('a.skip')).not.toBeNull();
    clearSession();
  });

  it('shows the tenant scope field for platform admins only', () => {
    seedSession(['PLATFORM_ADMIN'], 'admin', '*');
    TestBed.configureTestingModule({ providers: testProviders() });
    const f = TestBed.createComponent(ShellComponent);
    f.detectChanges();
    expect(html(f).textContent).toContain('Tenant scope');
    clearSession();
  });
});

describe('DashboardPage', () => {
  it('renders KPIs and charts from the summary', () => {
    seedSession(['SENDER']);
    TestBed.configureTestingModule({ providers: testProviders() });
    const ctrl = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(DashboardPage);
    f.detectChanges();
    ctrl.expectOne('/api/v1/ops/summary').flush({ total: 100, byStatus: { DELIVERED: 80, FAILED: 5, QUEUED: 15 }, byChannel: { EMAIL: 60, SMS: 40 }, successRate: 0.941, p95DispatchDelayMs: 820 });
    f.detectChanges();
    const t = html(f).textContent ?? '';
    expect(t).toContain('94.1%');
    expect(t).toContain('820');
    expect(html(f).querySelectorAll('app-bar-chart rect').length).toBe(3);
    expect(html(f).querySelector('[role=meter]')?.getAttribute('aria-valuenow')).toBe('94.1');
    ctrl.verify();
    clearSession();
  });

  it('shows an empty state with no messages', () => {
    seedSession(['SENDER']);
    TestBed.configureTestingModule({ providers: testProviders() });
    const ctrl = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(DashboardPage);
    f.detectChanges();
    ctrl.expectOne('/api/v1/ops/summary').flush({ total: 0, byStatus: {}, byChannel: {}, successRate: 0 });
    f.detectChanges();
    expect(html(f).textContent).toContain('No messages in the last 24 hours');
    clearSession();
  });
});

describe('DeadLettersPage', () => {
  let ctrl: HttpTestingController;
  function open(roles: Parameters<typeof seedSession>[0]): ComponentFixture<DeadLettersPage> {
    seedSession(roles);
    TestBed.configureTestingModule({ providers: testProviders() });
    ctrl = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(DeadLettersPage);
    f.detectChanges();
    ctrl.expectOne((r) => r.url === '/api/v1/dead-letters').flush(apiPage([
      { messageId: 'm1', channel: 'EMAIL', reason: 'RETRIES_EXHAUSTED', deadLetteredAt: '2026-01-01T00:00:00Z', attemptCount: 5 },
    ]));
    f.detectChanges();
    return f;
  }
  afterEach(() => { ctrl.verify(); clearSession(); });

  it('shows replay/discard to operators', () => {
    const f = open(['OPERATOR']);
    const labels = Array.from(html(f).querySelectorAll('button')).map((b) => b.textContent?.trim());
    expect(labels).toContain('Replay');
    expect(labels).toContain('Discard');
  });

  it('hides replay/discard from tenant admins (view-only)', () => {
    const f = open(['TENANT_ADMIN']);
    const labels = Array.from(html(f).querySelectorAll('button')).map((b) => b.textContent?.trim());
    expect(labels).not.toContain('Replay');
  });

  it('replay needs confirmation and POSTs /replay', () => {
    const f = open(['OPERATOR']);
    const confirm = spyOn(TestBed.inject(ConfirmService), 'confirm').and.returnValue(of(true));
    f.componentInstance.replay({ messageId: 'm1', channel: 'EMAIL', reason: 'X', deadLetteredAt: '' });
    expect(confirm).toHaveBeenCalled();
    ctrl.expectOne('/api/v1/dead-letters/m1/replay').flush({});
    ctrl.expectOne((r) => r.url === '/api/v1/dead-letters').flush(apiPage([]));
  });

  it('does not discard when the confirmation is cancelled', () => {
    const f = open(['OPERATOR']);
    spyOn(TestBed.inject(ConfirmService), 'confirm').and.returnValue(of(false));
    f.componentInstance.discard({ messageId: 'm1', channel: 'EMAIL', reason: 'X', deadLetteredAt: '' });
    expect(ctrl.match('/api/v1/dead-letters/m1/discard').length).toBe(0);
  });

  it('discard is a danger confirmation', () => {
    const f = open(['OPERATOR']);
    const confirm = spyOn(TestBed.inject(ConfirmService), 'confirm').and.returnValue(of(false));
    f.componentInstance.discard({ messageId: 'm1', channel: 'EMAIL', reason: 'X', deadLetteredAt: '' });
    expect(confirm.calls.mostRecent().args[0].danger).toBeTrue();
  });
});

describe('ProvidersPage', () => {
  const providers: ProviderHealth[] = [
    { providerId: 'sim-email', channel: 'EMAIL', enabled: true, circuitState: 'CLOSED', successRate: 0.987, p95LatencyMs: 140, rateLimitPerSec: 50, faultMode: 'NONE' },
    { providerId: 'acme-sms', channel: 'SMS', enabled: true, circuitState: 'OPEN', successRate: 0.5, p95LatencyMs: 900, rateLimitPerSec: 10, lastError: 'TIMEOUT' },
  ];
  function open(roles: Parameters<typeof seedSession>[0]): { f: ComponentFixture<ProvidersPage>; ctrl: HttpTestingController } {
    seedSession(roles);
    TestBed.configureTestingModule({ providers: testProviders() });
    const ctrl = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(ProvidersPage);
    f.detectChanges();
    ctrl.expectOne('/api/v1/providers').flush(providers);
    f.detectChanges();
    return { f, ctrl };
  }
  afterEach(() => clearSession());

  it('renders a health card per provider with circuit state and success rate', () => {
    const { f } = open(['OPERATOR']);
    expect(html(f).querySelectorAll('section.card').length).toBe(2);
    expect(html(f).textContent).toContain('98.7%');
    expect(html(f).textContent).toContain('OPEN');
    expect(html(f).textContent).toContain('TIMEOUT');
  });

  it('shows the fault-injection panel only to platform admins', () => {
    expect(html(open(['OPERATOR']).f).textContent).not.toContain('Fault injection');
    TestBed.resetTestingModule();
    expect(html(open(['PLATFORM_ADMIN']).f).textContent).toContain('Fault injection');
  });

  it('applies a fault mode with clamped probability', () => {
    const { f, ctrl } = open(['PLATFORM_ADMIN']);
    f.componentInstance.setMode('sim-email', 'TRANSIENT');
    f.componentInstance.setProb('sim-email', 7);
    f.componentInstance.applyFault(providers[0]);
    const req = ctrl.expectOne('/api/v1/providers/sim-email/fault-mode');
    expect(req.request.body).toEqual({ mode: 'TRANSIENT', probability: 1 });
    req.flush({});
    ctrl.expectOne('/api/v1/providers').flush(providers);
  });

  it('pct formats ratios', () => {
    expect(pct(0.5)).toBe('50%');
    expect(pct(undefined)).toBe('—');
  });
});

describe('AuditPage', () => {
  it('renders object details as plain text', () => {
    expect(auditDetailText({ detail: { a: 1 } })).toBe('{"a":1}');
    expect(auditDetailText({ detail: 'plain' })).toBe('plain');
    expect(auditDetailText({ detail: null })).toBe('');
  });

  it('passes entity filters to the API', () => {
    seedSession(['AUDITOR']);
    TestBed.configureTestingModule({ providers: testProviders() });
    const ctrl = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(AuditPage);
    f.detectChanges();
    ctrl.expectOne((r) => r.url === '/api/v1/audit-events').flush(apiPage([]));
    f.componentInstance.form.patchValue({ entityType: 'MESSAGE', entityId: 'm1' });
    f.componentInstance.apply();
    const req = ctrl.expectOne((r) => r.url === '/api/v1/audit-events');
    expect(req.request.params.get('entityType')).toBe('MESSAGE');
    expect(req.request.params.get('entityId')).toBe('m1');
    req.flush(apiPage([]));
    ctrl.verify();
    clearSession();
  });
});
