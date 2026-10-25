/**
 * Form validators ported from the Angular console (core/forms/validators.ts). Each validator takes the raw field value
 * and returns an error key or null (empty values are valid; combine with a `required` check where needed).
 */

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;
const E164_RE = /^\+[1-9]\d{6,14}$/;
const SENDER_ID_RE = /^[A-Za-z0-9]{3,11}$/;
const VAR_KEY_RE = /^[A-Za-z][A-Za-z0-9_]{0,63}$/;
const LOCALE_RE = /^[a-z]{2,3}(-[A-Z]{2})?$/;
const TEMPLATE_CODE_RE = /^[A-Z][A-Z0-9_]{2,63}$/;
const TIME_RE = /^([01]\d|2[0-3]):[0-5]\d$/;

/** Max push TTL: 28 days (common provider cap). */
export const MAX_PUSH_TTL_SECONDS = 28 * 24 * 3600;

export function isEmpty(v: unknown): boolean {
  return v === null || v === undefined || v === '';
}

export const isEmail = (v: unknown): boolean => isEmpty(v) || EMAIL_RE.test(String(v));
export const isE164 = (v: unknown): boolean => isEmpty(v) || E164_RE.test(String(v));
export const isSmsSenderId = (v: unknown): boolean => isEmpty(v) || SENDER_ID_RE.test(String(v));
export const isLocale = (v: unknown): boolean => isEmpty(v) || LOCALE_RE.test(String(v));
export const isTemplateCode = (v: unknown): boolean => isEmpty(v) || TEMPLATE_CODE_RE.test(String(v));
export const isVariableKey = (v: unknown): boolean => isEmpty(v) || VAR_KEY_RE.test(String(v));

/** HH:mm (24h), or blank (= use the default quiet-hours window). */
export const isQuietTime = (v: unknown): boolean => {
  const s = String(v ?? '').trim();
  return s === '' || TIME_RE.test(s);
};

/** Integer within [min, max] (empty is valid). */
export function isIntegerInRange(v: unknown, min: number, max: number): boolean {
  if (isEmpty(v)) {
    return true;
  }
  const n = Number(v);
  return Number.isInteger(n) && n >= min && n <= max;
}

/** Number within [min, max] (empty is valid). */
export function isNumberInRange(v: unknown, min: number, max: number): boolean {
  if (isEmpty(v)) {
    return true;
  }
  const n = Number(v);
  return Number.isFinite(n) && n >= min && n <= max;
}

/** The datetime-local value must be a valid date in the future (empty is valid). */
export function futureDateError(v: unknown, now: () => number = Date.now): 'date' | 'pastDate' | null {
  if (isEmpty(v)) {
    return null;
  }
  const t = new Date(String(v)).getTime();
  return Number.isNaN(t) ? 'date' : t > now() ? null : 'pastDate';
}

/** `expiresAt` must be after `sendAt` when both are given. */
export function expiryAfterSend(sendAt: unknown, expiresAt: unknown): boolean {
  if (isEmpty(sendAt) || isEmpty(expiresAt)) {
    return true;
  }
  return new Date(String(expiresAt)).getTime() > new Date(String(sendAt)).getTime();
}

/** At least one of the values must be non-empty. */
export function oneOf(...values: unknown[]): boolean {
  return values.some((v) => !isEmpty(typeof v === 'string' ? v.trim() : v));
}

/** `key` values in a {key,value} list must be unique (blank keys are ignored). */
export function hasUniqueKeys(rows: { key: string }[]): boolean {
  const keys = rows.map((r) => r.key.trim()).filter((k) => k.length > 0);
  return new Set(keys).size === keys.length;
}

/** Fallback channels must not repeat the primary channel. */
export function fallbackExcludesPrimary(primary: string, fallback: readonly string[]): boolean {
  return !fallback.includes(primary);
}

/** Quiet hours need both ends or neither. */
export function quietPairValid(start: string, end: string): boolean {
  return (start.trim() === '') === (end.trim() === '');
}

/** Comma-separated unique channel list (EMAIL, SMS, PUSH), or blank. */
export function isFallbackOrder(v: unknown): boolean {
  const s = String(v ?? '').trim();
  if (!s) {
    return true;
  }
  const parts = s.split(',').map((p) => p.trim());
  return parts.every((p) => ['EMAIL', 'SMS', 'PUSH'].includes(p)) && new Set(parts).size === parts.length;
}

export function parseChannels(text: string): ('EMAIL' | 'SMS' | 'PUSH')[] {
  return text.split(',').map((p) => p.trim()).filter(Boolean) as ('EMAIL' | 'SMS' | 'PUSH')[];
}

/** Parses "a, b\nc" into a unique list of variable names. */
export function parseVariableList(text: string): string[] {
  return Array.from(new Set(text.split(/[\s,]+/).map((s) => s.trim()).filter(Boolean)));
}

/** Parses `key=value` lines into a record. Lines without '=' are ignored. */
export function parseSampleData(text: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const line of text.split(/\r?\n/)) {
    const i = line.indexOf('=');
    if (i > 0) {
      out[line.slice(0, i).trim()] = line.slice(i + 1).trim();
    }
  }
  return out;
}

/** Template placeholders used in a body: {{name}}. */
export function placeholdersIn(body: string): string[] {
  return Array.from(new Set(Array.from(body.matchAll(/\{\{\s*([A-Za-z][A-Za-z0-9_]*)\s*\}\}/g)).map((m) => m[1])));
}

export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024;

/** Client-side CSV file pre-check (the server re-validates everything). */
export function validateCsvFile(file: { name: string; size: number } | null): string | null {
  if (!file) return 'Choose a CSV file.';
  if (!/\.csv$/i.test(file.name)) return 'File must have a .csv extension.';
  if (file.size === 0) return 'File is empty.';
  if (file.size > MAX_UPLOAD_BYTES) return 'File is larger than 10 MB.';
  return null;
}

/** Converts a yyyy-MM-dd date input to an ISO instant at start/end of that UTC day. */
export function dayBoundary(date: string, end: boolean): string {
  return date ? `${date}T${end ? '23:59:59' : '00:00:00'}Z` : '';
}
