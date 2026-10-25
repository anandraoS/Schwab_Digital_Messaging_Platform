import { AbstractControl, FormArray, FormGroup, ValidationErrors, ValidatorFn } from '@angular/forms';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;
const E164_RE = /^\+[1-9]\d{6,14}$/;
const SENDER_ID_RE = /^[A-Za-z0-9]{3,11}$/;
const VAR_KEY_RE = /^[A-Za-z][A-Za-z0-9_]{0,63}$/;
const LOCALE_RE = /^[a-z]{2,3}(-[A-Z]{2})?$/;
const TEMPLATE_CODE_RE = /^[A-Z][A-Z0-9_]{2,63}$/;

/** Max push TTL: 28 days (common provider cap). */
export const MAX_PUSH_TTL_SECONDS = 28 * 24 * 3600;

function empty(v: unknown): boolean {
  return v === null || v === undefined || v === '';
}

/** Valid e-mail address (empty is valid; combine with Validators.required if needed). */
export const emailValidator: ValidatorFn = (c) =>
  empty(c.value) || EMAIL_RE.test(String(c.value)) ? null : { email: true };

/** E.164 phone number (empty is valid). */
export const e164Validator: ValidatorFn = (c) =>
  empty(c.value) || E164_RE.test(String(c.value)) ? null : { e164: true };

/** Alphanumeric SMS sender id, 3-11 chars (empty is valid). */
export const smsSenderIdValidator: ValidatorFn = (c) =>
  empty(c.value) || SENDER_ID_RE.test(String(c.value)) ? null : { senderId: true };

export const localeValidator: ValidatorFn = (c) =>
  empty(c.value) || LOCALE_RE.test(String(c.value)) ? null : { locale: true };

export const templateCodeValidator: ValidatorFn = (c) =>
  empty(c.value) || TEMPLATE_CODE_RE.test(String(c.value)) ? null : { templateCode: true };

export const variableKeyValidator: ValidatorFn = (c) =>
  empty(c.value) || VAR_KEY_RE.test(String(c.value)) ? null : { variableKey: true };

export function integerRange(min: number, max: number): ValidatorFn {
  return (c: AbstractControl): ValidationErrors | null => {
    if (empty(c.value)) {
      return null;
    }
    const n = Number(c.value);
    return Number.isInteger(n) && n >= min && n <= max ? null : { range: { min, max, actual: c.value } };
  };
}

/** The ISO/datetime-local value must be in the future (empty is valid). */
export function futureDate(now: () => number = Date.now): ValidatorFn {
  return (c) => {
    if (empty(c.value)) {
      return null;
    }
    const t = new Date(String(c.value)).getTime();
    return Number.isNaN(t) ? { date: true } : t > now() ? null : { pastDate: true };
  };
}

/** Group validator: `expiresAt` must be after `sendAt` when both are given. */
export const expiryAfterSend: ValidatorFn = (g) => {
  const send = g.get('sendAt')?.value;
  const exp = g.get('expiresAt')?.value;
  if (empty(send) || empty(exp)) {
    return null;
  }
  return new Date(String(exp)).getTime() > new Date(String(send)).getTime() ? null : { expiryBeforeSend: true };
};

/** Group validator: at least one of the named controls must be non-empty. */
export function oneOf(...names: string[]): ValidatorFn {
  return (g) => (names.some((n) => !empty(g.get(n)?.value)) ? null : { oneOfRequired: names });
}

/** FormArray validator: `key` values in a {key,value} array must be unique. */
export const uniqueKeys: ValidatorFn = (arr) => {
  const keys = (arr as FormArray).controls
    .map((g) => String((g as FormGroup).get('key')?.value ?? '').trim())
    .filter((k) => k.length > 0);
  return new Set(keys).size === keys.length ? null : { duplicateKeys: true };
};

/** Fallback channels must not repeat the primary channel. */
export function fallbackNotPrimary(primaryName: string, fallbackName: string): ValidatorFn {
  return (g) => {
    const primary = g.get(primaryName)?.value;
    const fb = g.get(fallbackName)?.value as string[] | null;
    return Array.isArray(fb) && fb.includes(primary) ? { fallbackIsPrimary: true } : null;
  };
}
