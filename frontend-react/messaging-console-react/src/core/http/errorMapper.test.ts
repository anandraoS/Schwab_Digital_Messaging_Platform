import { describe, expect, it } from 'vitest';
import { ERROR_MESSAGES, NormalizedError, isNormalizedError, normalizeFailure, toNormalizedError } from './errorMapper';

const hdr = (h: Record<string, string> = {}) => new Headers(h);

describe('error mapper', () => {
  it('maps a known ApiError code to actionable text and keeps the server message', () => {
    const n = normalizeFailure({ status: 422, body: { code: 'IDEMPOTENCY_KEY_REUSED', message: 'raw', correlationId: 'c1' } });
    expect(n.code).toBe('IDEMPOTENCY_KEY_REUSED');
    expect(n.message).toBe(ERROR_MESSAGES['IDEMPOTENCY_KEY_REUSED']);
    expect(n.serverMessage).toBe('raw');
    expect(n.correlationId).toBe('c1');
  });

  it('keeps field details from VALIDATION_FAILED', () => {
    const n = normalizeFailure({ status: 400, body: { code: 'VALIDATION_FAILED', message: 'bad', details: [{ field: 'channel', message: 'required' }] } });
    expect(n.details).toEqual([{ field: 'channel', message: 'required' }]);
  });

  it('flags optimistic lock conflicts', () => {
    const n = normalizeFailure({ status: 409, body: { code: 'CONFLICT_OPTIMISTIC_LOCK', message: 'stale' } });
    expect(n.conflict).toBe(true);
    expect(n.message).toContain('Reload');
  });

  it('treats any 409 as a conflict', () => {
    expect(normalizeFailure({ status: 409, body: null }).conflict).toBe(true);
  });

  it('falls back to a status message for non-ApiError bodies (HTML gateway page)', () => {
    const n = normalizeFailure({ status: 503, body: '<html>gateway</html>' });
    expect(n.code).toBe('HTTP_503');
    expect(n.message).toContain('unavailable');
    expect(n.serverMessage).toBeUndefined();
  });

  it('describes network failures (status 0)', () => {
    const n = normalizeFailure({ status: 0, body: null });
    expect(n.code).toBe('NETWORK_ERROR');
    expect(n.message).toContain('Cannot reach the server');
  });

  it('parses Retry-After on 429', () => {
    const n = normalizeFailure({ status: 429, body: { code: 'RATE_LIMITED', message: 'slow' }, headers: hdr({ 'Retry-After': '7' }) });
    expect(n.retryAfterSeconds).toBe(7);
  });

  it('ignores a non-numeric Retry-After', () => {
    const n = normalizeFailure({ status: 429, body: { code: 'RATE_LIMITED' }, headers: hdr({ 'Retry-After': 'soon' }) });
    expect(n.retryAfterSeconds).toBeUndefined();
  });

  it('reads the correlation id from the response header when the body lacks it', () => {
    const n = normalizeFailure({ status: 500, body: null, headers: hdr({ 'X-Correlation-Id': 'hdr-1' }) });
    expect(n.correlationId).toBe('hdr-1');
  });

  it('has a mapping for every contract error code', () => {
    const codes = ['VALIDATION_FAILED', 'MALFORMED_REQUEST', 'UNSUPPORTED_CHANNEL', 'UNAUTHENTICATED', 'FORBIDDEN', 'NOT_FOUND',
      'IDEMPOTENCY_KEY_REUSED', 'IDEMPOTENCY_IN_PROGRESS', 'ILLEGAL_STATE_TRANSITION', 'TEMPLATE_NOT_FOUND', 'TEMPLATE_VARIABLE_MISSING',
      'TEMPLATE_RENDER_ERROR', 'RECIPIENT_NOT_FOUND', 'POLICY_SUPPRESSED', 'RATE_LIMITED', 'PAYLOAD_TOO_LARGE', 'CONFLICT_OPTIMISTIC_LOCK',
      'INVALID_SIGNATURE', 'REPLAYED_EVENT', 'INTERNAL_ERROR', 'SERVICE_UNAVAILABLE'];
    for (const c of codes) {
      expect(ERROR_MESSAGES[c], c).toBeTruthy();
    }
  });

  it('uses a generic message for unknown statuses', () => {
    expect(normalizeFailure({ status: 418, body: null }).message).toBe('Request failed (HTTP 418).');
  });

  it('toNormalizedError passes through normalized errors and wraps generic ones', () => {
    const n = normalizeFailure({ status: 404, body: { code: 'NOT_FOUND' } });
    expect(isNormalizedError(n)).toBe(true);
    expect(n).toBeInstanceOf(NormalizedError);
    expect(toNormalizedError(n)).toBe(n);
    expect(toNormalizedError(new Error('boom')).message).toBe('boom');
    expect(toNormalizedError('weird').code).toBe('CLIENT_ERROR');
  });
});
