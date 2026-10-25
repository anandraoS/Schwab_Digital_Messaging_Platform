import { HttpErrorResponse } from '@angular/common/http';
import { ApiError, ApiErrorDetail } from '../models/api.models';

export interface NormalizedError {
  status: number;
  code: string;
  /** Actionable, user-facing text derived from the error code. */
  message: string;
  /** Raw server message (may be empty). */
  serverMessage?: string;
  details: ApiErrorDetail[];
  correlationId?: string;
  retryAfterSeconds?: number;
  conflict: boolean;
}

/** Error code -> actionable guidance. Keep in sync with CONTRACT section 5. */
export const ERROR_MESSAGES: Record<string, string> = {
  VALIDATION_FAILED: 'Some fields are invalid. Fix the highlighted values and submit again.',
  MALFORMED_REQUEST: 'The request could not be parsed. Check the JSON/CSV format and try again.',
  UNSUPPORTED_CHANNEL: 'That channel is not supported for this operation. Pick EMAIL, SMS or PUSH.',
  UNAUTHENTICATED: 'Your session is missing or expired. Sign in again.',
  FORBIDDEN: 'Your role is not allowed to perform this action. Ask an administrator for access.',
  NOT_FOUND: 'The item was not found, or it belongs to another tenant.',
  IDEMPOTENCY_KEY_REUSED: 'This idempotency key was already used with a different request. Generate a new key to send a different message.',
  IDEMPOTENCY_IN_PROGRESS: 'An identical request is still being processed. Wait a moment, then retry with the same key.',
  ILLEGAL_STATE_TRANSITION: 'The message is no longer in a state that allows this action. Refresh to see its current status.',
  TEMPLATE_NOT_FOUND: 'No published template matches that code, channel and locale. Check the template list.',
  TEMPLATE_VARIABLE_MISSING: 'The template needs variables that were not provided. Add them in the variables editor.',
  TEMPLATE_RENDER_ERROR: 'The template could not be rendered. Check placeholders and allowed variables.',
  RECIPIENT_NOT_FOUND: 'The recipient does not exist (or was deleted) for this tenant.',
  POLICY_SUPPRESSED: 'Delivery was blocked by consent or suppression policy. Fallback channels never bypass consent.',
  RATE_LIMITED: 'Too many requests. Wait before retrying.',
  PAYLOAD_TOO_LARGE: 'The payload is larger than the 256 KB limit. Reduce its size.',
  CONFLICT_OPTIMISTIC_LOCK: 'Someone else changed this record first. Reload the latest version and re-apply your edit.',
  INVALID_SIGNATURE: 'The callback signature was invalid.',
  REPLAYED_EVENT: 'That event was already processed.',
  INTERNAL_ERROR: 'The server hit an unexpected error. Retry; if it persists, quote the correlation id to support.',
  SERVICE_UNAVAILABLE: 'The service is temporarily unavailable or applying backpressure. Retry shortly.',
};

const STATUS_FALLBACK: Record<number, string> = {
  0: 'Cannot reach the server. Check your network or that the API is running on port 18080.',
  400: ERROR_MESSAGES['MALFORMED_REQUEST'],
  401: ERROR_MESSAGES['UNAUTHENTICATED'],
  403: ERROR_MESSAGES['FORBIDDEN'],
  404: ERROR_MESSAGES['NOT_FOUND'],
  409: 'The request conflicts with the current state. Refresh and try again.',
  413: ERROR_MESSAGES['PAYLOAD_TOO_LARGE'],
  422: ERROR_MESSAGES['VALIDATION_FAILED'],
  429: ERROR_MESSAGES['RATE_LIMITED'],
  500: ERROR_MESSAGES['INTERNAL_ERROR'],
  502: ERROR_MESSAGES['SERVICE_UNAVAILABLE'],
  503: ERROR_MESSAGES['SERVICE_UNAVAILABLE'],
  504: ERROR_MESSAGES['SERVICE_UNAVAILABLE'],
};

function isApiError(body: unknown): body is Partial<ApiError> {
  return typeof body === 'object' && body !== null && ('code' in body || 'message' in body);
}

export function isNormalizedError(e: unknown): e is NormalizedError {
  return typeof e === 'object' && e !== null && 'code' in e && 'details' in e && 'conflict' in e;
}

export function normalizeHttpError(err: HttpErrorResponse): NormalizedError {
  const body: unknown = err.error;
  const api = isApiError(body) ? body : undefined;
  const code = api?.code ?? (err.status === 0 ? 'NETWORK_ERROR' : `HTTP_${err.status}`);
  const retryHeader = err.headers?.get('Retry-After');
  const retry = retryHeader ? Number(retryHeader) : undefined;
  const message =
    ERROR_MESSAGES[code] ?? STATUS_FALLBACK[err.status] ?? `Request failed (HTTP ${err.status}).`;
  return {
    status: err.status,
    code,
    message,
    serverMessage: api?.message,
    details: Array.isArray(api?.details) ? (api!.details as ApiErrorDetail[]) : [],
    correlationId: api?.correlationId ?? err.headers?.get('X-Correlation-Id') ?? undefined,
    retryAfterSeconds: retry !== undefined && Number.isFinite(retry) ? retry : undefined,
    conflict: code === 'CONFLICT_OPTIMISTIC_LOCK' || err.status === 409,
  };
}

/** Converts any thrown value into a NormalizedError so components can render it uniformly. */
export function toNormalizedError(e: unknown): NormalizedError {
  if (isNormalizedError(e)) {
    return e;
  }
  if (e instanceof HttpErrorResponse) {
    return normalizeHttpError(e);
  }
  return {
    status: -1, code: 'CLIENT_ERROR', message: e instanceof Error ? e.message : 'Unexpected client error.',
    details: [], conflict: false,
  };
}
