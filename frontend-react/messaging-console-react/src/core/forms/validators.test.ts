import { describe, expect, it } from 'vitest';
import {
  MAX_PUSH_TTL_SECONDS, dayBoundary, expiryAfterSend, fallbackExcludesPrimary, futureDateError, hasUniqueKeys, isE164,
  isEmail, isFallbackOrder, isIntegerInRange, isLocale, isNumberInRange, isQuietTime, isSmsSenderId, isTemplateCode,
  isVariableKey, oneOf, parseChannels, parseSampleData, parseVariableList, placeholdersIn, quietPairValid, validateCsvFile,
} from './validators';

describe('validators', () => {
  it('e-mail: empty is valid, malformed is not', () => {
    expect(isEmail('')).toBe(true);
    expect(isEmail('a@b.co')).toBe(true);
    expect(isEmail('a@b')).toBe(false);
    expect(isEmail('a b@c.de')).toBe(false);
  });

  it('E.164 phone numbers', () => {
    expect(isE164('+14155550100')).toBe(true);
    expect(isE164('14155550100')).toBe(false);
    expect(isE164('+0123456')).toBe(false);
  });

  it('SMS sender id is 3-11 alphanumerics', () => {
    expect(isSmsSenderId('ACME')).toBe(true);
    expect(isSmsSenderId('AB')).toBe(false);
    expect(isSmsSenderId('TOO-LONG-ID-X')).toBe(false);
    expect(isSmsSenderId('')).toBe(true);
  });

  it('locale accepts en and en-US only', () => {
    expect(isLocale('en')).toBe(true);
    expect(isLocale('en-US')).toBe(true);
    expect(isLocale('en_US')).toBe(false);
    expect(isLocale('EN')).toBe(false);
  });

  it('template code is upper snake case, 3-64 chars', () => {
    expect(isTemplateCode('TRADE_CONFIRMATION')).toBe(true);
    expect(isTemplateCode('ab')).toBe(false);
    expect(isTemplateCode('lower_case')).toBe(false);
    expect(isTemplateCode('1ABC')).toBe(false);
  });

  it('variable keys start with a letter', () => {
    expect(isVariableKey('firstName')).toBe(true);
    expect(isVariableKey('first_name2')).toBe(true);
    expect(isVariableKey('2fast')).toBe(false);
    expect(isVariableKey('has space')).toBe(false);
  });

  it('integer range validates bounds and integrality', () => {
    expect(isIntegerInRange('5', 1, 10)).toBe(true);
    expect(isIntegerInRange('0', 1, 10)).toBe(false);
    expect(isIntegerInRange('11', 1, 10)).toBe(false);
    expect(isIntegerInRange('2.5', 1, 10)).toBe(false);
    expect(isIntegerInRange('', 1, 10)).toBe(true);
    expect(isIntegerInRange(String(MAX_PUSH_TTL_SECONDS + 1), 1, MAX_PUSH_TTL_SECONDS)).toBe(false);
  });

  it('number range allows fractions', () => {
    expect(isNumberInRange('0.02', 0, 1)).toBe(true);
    expect(isNumberInRange('1.5', 0, 1)).toBe(false);
    expect(isNumberInRange('abc', 0, 1)).toBe(false);
  });

  it('future date: past dates and garbage are rejected', () => {
    const now = () => new Date('2030-01-01T00:00:00Z').getTime();
    expect(futureDateError('2031-01-01T00:00', now)).toBeNull();
    expect(futureDateError('2029-01-01T00:00', now)).toBe('pastDate');
    expect(futureDateError('nonsense', now)).toBe('date');
    expect(futureDateError('', now)).toBeNull();
  });

  it('expiry must be after send time when both are given', () => {
    expect(expiryAfterSend('2030-01-01T10:00', '2030-01-01T11:00')).toBe(true);
    expect(expiryAfterSend('2030-01-01T10:00', '2030-01-01T09:00')).toBe(false);
    expect(expiryAfterSend('', '2030-01-01T09:00')).toBe(true);
  });

  it('oneOf needs at least one non-blank value', () => {
    expect(oneOf('', '  ')).toBe(false);
    expect(oneOf('', 'CUST-1')).toBe(true);
  });

  it('variable keys must be unique, blanks ignored', () => {
    expect(hasUniqueKeys([{ key: 'a' }, { key: 'b' }, { key: '' }, { key: '' }])).toBe(true);
    expect(hasUniqueKeys([{ key: 'a' }, { key: ' a ' }])).toBe(false);
  });

  it('fallback must not repeat the primary channel', () => {
    expect(fallbackExcludesPrimary('EMAIL', ['SMS', 'PUSH'])).toBe(true);
    expect(fallbackExcludesPrimary('EMAIL', ['EMAIL'])).toBe(false);
  });

  it('quiet hours: HH:mm or blank, both ends or neither', () => {
    expect(isQuietTime('')).toBe(true);
    expect(isQuietTime('21:00')).toBe(true);
    expect(isQuietTime('24:00')).toBe(false);
    expect(isQuietTime('9:00')).toBe(false);
    expect(quietPairValid('', '')).toBe(true);
    expect(quietPairValid('21:00', '08:00')).toBe(true);
    expect(quietPairValid('21:00', '')).toBe(false);
  });

  it('fallback order is a unique channel list', () => {
    expect(isFallbackOrder('')).toBe(true);
    expect(isFallbackOrder('SMS,EMAIL')).toBe(true);
    expect(isFallbackOrder('SMS, EMAIL , PUSH')).toBe(true);
    expect(isFallbackOrder('SMS,SMS')).toBe(false);
    expect(isFallbackOrder('FAX')).toBe(false);
    expect(parseChannels(' SMS , EMAIL ')).toEqual(['SMS', 'EMAIL']);
    expect(parseChannels('')).toEqual([]);
  });

  it('parses variable lists and sample data', () => {
    expect(parseVariableList('a, b\nc a')).toEqual(['a', 'b', 'c']);
    expect(parseSampleData('name=Ada\nbad line\namount = 10 \n=x')).toEqual({ name: 'Ada', amount: '10' });
  });

  it('finds {{placeholders}} in a body', () => {
    expect(placeholdersIn('Hi {{ name }}, you owe {{amount}} {{amount}} {{ 9bad }}')).toEqual(['name', 'amount']);
  });

  it('CSV file pre-check', () => {
    expect(validateCsvFile(null)).toBe('Choose a CSV file.');
    expect(validateCsvFile({ name: 'a.txt', size: 5 })).toBe('File must have a .csv extension.');
    expect(validateCsvFile({ name: 'a.csv', size: 0 })).toBe('File is empty.');
    expect(validateCsvFile({ name: 'a.csv', size: 11 * 1024 * 1024 })).toBe('File is larger than 10 MB.');
    expect(validateCsvFile({ name: 'A.CSV', size: 10 })).toBeNull();
  });

  it('day boundaries are UTC instants', () => {
    expect(dayBoundary('2030-02-03', false)).toBe('2030-02-03T00:00:00Z');
    expect(dayBoundary('2030-02-03', true)).toBe('2030-02-03T23:59:59Z');
    expect(dayBoundary('', true)).toBe('');
  });
});
