import { FormControl, FormGroup } from '@angular/forms';
import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { RecipientPreferences, RecipientSummary } from '../../core/models/api.models';
import { apiPage, clearSession, seedSession, testProviders } from '../../testing/test-utils';
import { PreferencesPage, fallbackOrderValidator, parseChannels, quietPairValidator, timeValidator } from './preferences.page';

const html = (f: ComponentFixture<unknown>): HTMLElement => f.nativeElement as HTMLElement;
const rcp: RecipientSummary = { id: 'rcp-001', externalRef: 'CUST-001', displayName: 'Recipient 001', maskedEmail: 'r***@example.test', maskedPhone: '+1555***0001' };
const prefs = (version: number, over: Partial<RecipientPreferences> = {}): RecipientPreferences => ({
  recipientId: 'rcp-001', marketingOptIn: false, emailEnabled: true, smsEnabled: true, pushEnabled: true,
  quietStart: '21:00', quietEnd: '08:00', timeZone: 'America/New_York', fallbackOrder: ['EMAIL', 'SMS'], version, ...over,
});

describe('preference validators', () => {
  it('timeValidator accepts HH:mm 24h only', () => {
    expect(timeValidator(new FormControl('08:30'))).toBeNull();
    expect(timeValidator(new FormControl('23:59'))).toBeNull();
    expect(timeValidator(new FormControl('24:00'))).toEqual({ time: true });
    expect(timeValidator(new FormControl('8:30'))).toEqual({ time: true });
  });
  it('timeValidator treats blank as "use the default window"', () => {
    expect(timeValidator(new FormControl(''))).toBeNull();
    expect(timeValidator(new FormControl(null))).toBeNull();
  });
  it('quietPairValidator needs both ends or neither', () => {
    const g = (s: string, e: string): FormGroup => new FormGroup({ quietStart: new FormControl(s), quietEnd: new FormControl(e) });
    expect(quietPairValidator(g('', ''))).toBeNull();
    expect(quietPairValidator(g('21:00', '08:00'))).toBeNull();
    expect(quietPairValidator(g('21:00', ''))).toEqual({ quietPair: true });
  });
  it('parseChannels splits a comma list', () => {
    expect(parseChannels('EMAIL, SMS')).toEqual(['EMAIL', 'SMS']);
    expect(parseChannels('')).toEqual([]);
  });
  it('fallbackOrderValidator needs unique known channels', () => {
    expect(fallbackOrderValidator(new FormControl('EMAIL,SMS,PUSH'))).toBeNull();
    expect(fallbackOrderValidator(new FormControl(''))).toBeNull();
    expect(fallbackOrderValidator(new FormControl('EMAIL,EMAIL'))).toEqual({ fallbackOrder: true });
    expect(fallbackOrderValidator(new FormControl('FAX'))).toEqual({ fallbackOrder: true });
  });
});

describe('PreferencesPage', () => {
  let ctrl: HttpTestingController;
  let f: ComponentFixture<PreferencesPage>;
  let page: PreferencesPage;

  function open(roles: Parameters<typeof seedSession>[0]): void {
    seedSession(roles);
    TestBed.configureTestingModule({ providers: testProviders() });
    ctrl = TestBed.inject(HttpTestingController);
    f = TestBed.createComponent(PreferencesPage);
    page = f.componentInstance;
    f.detectChanges();
    page.searchForm.setValue({ q: 'rec' });
    page.search();
    ctrl.expectOne((r) => r.url === '/api/v1/recipients').flush(apiPage([rcp]));
    page.select(rcp);
    ctrl.expectOne('/api/v1/recipients/rcp-001/preferences').flush(prefs(3));
    f.detectChanges();
  }
  afterEach(() => { ctrl.verify(); clearSession(); });

  it('loads preferences into the form', () => {
    open(['OPERATOR']);
    expect(page.form.value.fallbackOrder).toBe('EMAIL,SMS');
    expect(html(f).textContent).toContain('Loaded version: 3');
    expect(html(f).textContent).toContain('r***@example.test'); // masked address column from maskedEmail
  });

  it('sends the backend UpdatePreferencesRequest shape (channel array, null for blank quiet hours)', () => {
    open(['OPERATOR']);
    page.form.patchValue({ quietStart: '', quietEnd: '', fallbackOrder: 'SMS, EMAIL' });
    page.save();
    const req = ctrl.expectOne('/api/v1/recipients/rcp-001/preferences');
    expect(req.request.body.fallbackOrder).toEqual(['SMS', 'EMAIL']);
    expect(req.request.body.quietStart).toBeNull();
    expect(req.request.body.quietEnd).toBeNull();
    expect(req.request.body.timeZone).toBe('America/New_York');
    expect('recipientId' in req.request.body).toBeFalse();
    req.flush(prefs(4, { quietStart: null, quietEnd: null }));
  });

  it('a recipient with default quiet hours (null) and no stored row (version null) can be loaded and saved', () => {
    open(['OPERATOR']);
    page.prefs.data.set(prefs(0, { quietStart: null, quietEnd: null, fallbackOrder: [], version: null }));
    page.select(rcp);
    ctrl.expectOne('/api/v1/recipients/rcp-001/preferences').flush(prefs(0, { quietStart: null, quietEnd: null, fallbackOrder: [], version: null }));
    expect(page.form.valid).toBeTrue();
    page.save();
    const req = ctrl.expectOne('/api/v1/recipients/rcp-001/preferences');
    expect(req.request.body.version).toBeNull();
    req.flush(prefs(1));
  });

  it('saves with the loaded version for optimistic locking', () => {
    open(['OPERATOR']);
    page.form.patchValue({ marketingOptIn: true });
    page.save();
    const req = ctrl.expectOne('/api/v1/recipients/rcp-001/preferences');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.version).toBe(3);
    expect(req.request.body.marketingOptIn).toBeTrue();
    req.flush(prefs(4, { marketingOptIn: true }));
    expect(page.prefs.data()?.version).toBe(4);
    expect(page.saved()).toBeTrue();
  });

  it('does not save invalid quiet hours', () => {
    open(['OPERATOR']);
    page.form.patchValue({ quietStart: '25:99', quietEnd: '08:00' });
    page.save();
    ctrl.expectNone('/api/v1/recipients/rcp-001/preferences');
    expect(page.saving()).toBeFalse();
    expect(page.form.controls.quietStart.hasError('time')).toBeTrue();
  });

  it('shows conflict UX on 409 and "reload and re-apply" keeps my edits over fresh data', () => {
    open(['OPERATOR']);
    page.form.patchValue({ marketingOptIn: true, quietStart: '22:00' });
    page.save();
    ctrl.expectOne('/api/v1/recipients/rcp-001/preferences').flush(
      { code: 'CONFLICT_OPTIMISTIC_LOCK', message: 'stale' }, { status: 409, statusText: 'Conflict' });
    f.detectChanges();
    expect(html(f).querySelector('.conflict')).not.toBeNull();
    expect(html(f).textContent).toContain('Reload latest');

    page.reload(true);
    ctrl.expectOne('/api/v1/recipients/rcp-001/preferences').flush(prefs(5, { quietEnd: '07:00', smsEnabled: false }));
    f.detectChanges();
    expect(page.conflict()).toBeNull();
    expect(page.prefs.data()?.version).toBe(5);
    expect(page.form.value.marketingOptIn).toBeTrue();
    expect(page.form.value.quietStart).toBe('22:00');
    // saving again now uses the fresh version
    page.save();
    expect(ctrl.expectOne('/api/v1/recipients/rcp-001/preferences').request.body.version).toBe(5);
  });

  it('reload without re-apply discards my edits', () => {
    open(['OPERATOR']);
    page.form.patchValue({ marketingOptIn: true });
    page.save();
    ctrl.expectOne('/api/v1/recipients/rcp-001/preferences').flush({ code: 'CONFLICT_OPTIMISTIC_LOCK' }, { status: 409, statusText: 'x' });
    page.reload(false);
    ctrl.expectOne('/api/v1/recipients/rcp-001/preferences').flush(prefs(6));
    expect(page.form.value.marketingOptIn).toBeFalse();
  });

  it('shows non-conflict save errors in the error banner instead', () => {
    open(['OPERATOR']);
    page.save();
    ctrl.expectOne('/api/v1/recipients/rcp-001/preferences').flush({ code: 'VALIDATION_FAILED', message: 'bad' }, { status: 400, statusText: 'x' });
    expect(page.conflict()).toBeNull();
    expect(page.saveError()?.code).toBe('VALIDATION_FAILED');
  });

  it('is read-only for senders (no save button)', () => {
    open(['SENDER']);
    expect(html(f).textContent).toContain('can view preferences but not edit');
    expect(Array.from(html(f).querySelectorAll('button')).some((b) => b.textContent?.includes('Save preferences'))).toBeFalse();
  });
});
