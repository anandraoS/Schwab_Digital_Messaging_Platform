import { FormArray, FormControl, FormGroup } from '@angular/forms';
import {
  MAX_PUSH_TTL_SECONDS, e164Validator, emailValidator, expiryAfterSend, fallbackNotPrimary, futureDate, integerRange,
  localeValidator, oneOf, smsSenderIdValidator, templateCodeValidator, uniqueKeys, variableKeyValidator,
} from './validators';

const c = (v: unknown) => new FormControl(v);

describe('form validators', () => {
  it('emailValidator accepts valid and empty, rejects malformed', () => {
    expect(emailValidator(c('a@b.com'))).toBeNull();
    expect(emailValidator(c(''))).toBeNull();
    expect(emailValidator(c('not-an-email'))).toEqual({ email: true });
    expect(emailValidator(c('a@b'))).toEqual({ email: true });
  });

  it('e164Validator enforces +country digits', () => {
    expect(e164Validator(c('+15550100001'))).toBeNull();
    expect(e164Validator(c('5550100001'))).toEqual({ e164: true });
    expect(e164Validator(c('+0123456789'))).toEqual({ e164: true });
    expect(e164Validator(c('+1'))).toEqual({ e164: true });
  });

  it('smsSenderIdValidator allows 3-11 alphanumerics', () => {
    expect(smsSenderIdValidator(c('ACME'))).toBeNull();
    expect(smsSenderIdValidator(c('AB'))).toEqual({ senderId: true });
    expect(smsSenderIdValidator(c('TOO-LONG-ID-123'))).toEqual({ senderId: true });
    expect(smsSenderIdValidator(c('bad id'))).toEqual({ senderId: true });
  });

  it('localeValidator accepts en and en-US only', () => {
    expect(localeValidator(c('en'))).toBeNull();
    expect(localeValidator(c('en-US'))).toBeNull();
    expect(localeValidator(c('EN_us'))).toEqual({ locale: true });
  });

  it('templateCodeValidator wants upper snake case', () => {
    expect(templateCodeValidator(c('TRADE_CONFIRMATION'))).toBeNull();
    expect(templateCodeValidator(c('trade'))).toEqual({ templateCode: true });
    expect(templateCodeValidator(c('A'))).toEqual({ templateCode: true });
  });

  it('variableKeyValidator restricts key characters', () => {
    expect(variableKeyValidator(c('first_name1'))).toBeNull();
    expect(variableKeyValidator(c('1abc'))).toEqual({ variableKey: true });
    expect(variableKeyValidator(c('a b'))).toEqual({ variableKey: true });
  });

  it('integerRange enforces integer bounds (push TTL)', () => {
    const v = integerRange(1, MAX_PUSH_TTL_SECONDS);
    expect(v(c(3600))).toBeNull();
    expect(v(c(null))).toBeNull();
    expect(v(c(0))).not.toBeNull();
    expect(v(c(1.5))).not.toBeNull();
    expect(v(c(MAX_PUSH_TTL_SECONDS + 1))).not.toBeNull();
  });

  it('futureDate rejects past and invalid dates', () => {
    const now = Date.parse('2026-01-01T00:00:00Z');
    const v = futureDate(() => now);
    expect(v(c('2026-01-02T00:00'))).toBeNull();
    expect(v(c('2025-12-31T00:00'))).toEqual({ pastDate: true });
    expect(v(c('garbage'))).toEqual({ date: true });
    expect(v(c(''))).toBeNull();
  });

  it('expiryAfterSend requires expiry after send', () => {
    const g = (s: string, e: string) => new FormGroup({ sendAt: c(s), expiresAt: c(e) });
    expect(expiryAfterSend(g('2026-01-01T10:00', '2026-01-01T11:00'))).toBeNull();
    expect(expiryAfterSend(g('2026-01-01T10:00', '2026-01-01T09:00'))).toEqual({ expiryBeforeSend: true });
    expect(expiryAfterSend(g('', '2026-01-01T09:00'))).toBeNull();
  });

  it('oneOf needs at least one non-empty control', () => {
    const v = oneOf('a', 'b');
    expect(v(new FormGroup({ a: c(''), b: c('x') }))).toBeNull();
    expect(v(new FormGroup({ a: c(''), b: c('') }))).toEqual({ oneOfRequired: ['a', 'b'] });
  });

  it('uniqueKeys detects duplicate variable keys', () => {
    const row = (k: string) => new FormGroup({ key: c(k), value: c('') });
    expect(uniqueKeys(new FormArray([row('a'), row('b')]))).toBeNull();
    expect(uniqueKeys(new FormArray([row('a'), row('a')]))).toEqual({ duplicateKeys: true });
    expect(uniqueKeys(new FormArray([row(''), row('')]))).toBeNull();
  });

  it('fallbackNotPrimary rejects a fallback equal to the primary channel', () => {
    const v = fallbackNotPrimary('channel', 'fb');
    expect(v(new FormGroup({ channel: c('EMAIL'), fb: c(['SMS']) }))).toBeNull();
    expect(v(new FormGroup({ channel: c('EMAIL'), fb: c(['EMAIL', 'SMS']) }))).toEqual({ fallbackIsPrimary: true });
  });
});
