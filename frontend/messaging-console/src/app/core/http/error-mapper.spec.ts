import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { ERROR_MESSAGES, isNormalizedError, normalizeHttpError, toNormalizedError } from './error-mapper';

function httpError(status: number, body: unknown, headers?: Record<string, string>): HttpErrorResponse {
  return new HttpErrorResponse({ status, error: body, headers: new HttpHeaders(headers ?? {}), url: '/api/v1/x' });
}

describe('error-mapper', () => {
  it('maps a known ApiError code to actionable text', () => {
    const n = normalizeHttpError(httpError(422, { code: 'IDEMPOTENCY_KEY_REUSED', message: 'raw', correlationId: 'c1', status: 422 }));
    expect(n.code).toBe('IDEMPOTENCY_KEY_REUSED');
    expect(n.message).toBe(ERROR_MESSAGES['IDEMPOTENCY_KEY_REUSED']);
    expect(n.serverMessage).toBe('raw');
    expect(n.correlationId).toBe('c1');
  });

  it('keeps field details from VALIDATION_FAILED', () => {
    const n = normalizeHttpError(httpError(400, { code: 'VALIDATION_FAILED', message: 'bad', details: [{ field: 'channel', message: 'required' }] }));
    expect(n.details).toEqual([{ field: 'channel', message: 'required' }]);
  });

  it('flags optimistic lock conflicts', () => {
    const n = normalizeHttpError(httpError(409, { code: 'CONFLICT_OPTIMISTIC_LOCK', message: 'stale' }));
    expect(n.conflict).toBeTrue();
    expect(n.message).toContain('Reload');
  });

  it('falls back to a status message for non-ApiError bodies', () => {
    const n = normalizeHttpError(httpError(503, '<html>gateway</html>'));
    expect(n.code).toBe('HTTP_503');
    expect(n.message).toContain('unavailable');
  });

  it('describes network failures (status 0)', () => {
    const n = normalizeHttpError(httpError(0, null));
    expect(n.code).toBe('NETWORK_ERROR');
    expect(n.message).toContain('Cannot reach the server');
  });

  it('parses Retry-After on 429', () => {
    const n = normalizeHttpError(httpError(429, { code: 'RATE_LIMITED', message: 'slow' }, { 'Retry-After': '7' }));
    expect(n.retryAfterSeconds).toBe(7);
  });

  it('ignores a non-numeric Retry-After', () => {
    const n = normalizeHttpError(httpError(429, { code: 'RATE_LIMITED' }, { 'Retry-After': 'soon' }));
    expect(n.retryAfterSeconds).toBeUndefined();
  });

  it('reads correlation id from the response header when the body lacks it', () => {
    const n = normalizeHttpError(httpError(500, null, { 'X-Correlation-Id': 'hdr-1' }));
    expect(n.correlationId).toBe('hdr-1');
  });

  it('has a mapping for every contract error code', () => {
    const codes = ['VALIDATION_FAILED', 'MALFORMED_REQUEST', 'UNSUPPORTED_CHANNEL', 'UNAUTHENTICATED', 'FORBIDDEN', 'NOT_FOUND',
      'IDEMPOTENCY_KEY_REUSED', 'IDEMPOTENCY_IN_PROGRESS', 'ILLEGAL_STATE_TRANSITION', 'TEMPLATE_NOT_FOUND', 'TEMPLATE_VARIABLE_MISSING',
      'TEMPLATE_RENDER_ERROR', 'RECIPIENT_NOT_FOUND', 'POLICY_SUPPRESSED', 'RATE_LIMITED', 'PAYLOAD_TOO_LARGE', 'CONFLICT_OPTIMISTIC_LOCK',
      'INVALID_SIGNATURE', 'REPLAYED_EVENT', 'INTERNAL_ERROR', 'SERVICE_UNAVAILABLE'];
    for (const c of codes) {
      expect(ERROR_MESSAGES[c]).withContext(c).toBeTruthy();
    }
  });

  it('toNormalizedError passes through normalized errors and wraps generic ones', () => {
    const n = normalizeHttpError(httpError(404, { code: 'NOT_FOUND' }));
    expect(isNormalizedError(n)).toBeTrue();
    expect(toNormalizedError(n)).toBe(n);
    expect(toNormalizedError(new Error('boom')).message).toBe('boom');
    expect(toNormalizedError(httpError(403, { code: 'FORBIDDEN' })).code).toBe('FORBIDDEN');
    expect(toNormalizedError('weird').code).toBe('CLIENT_ERROR');
  });
});
