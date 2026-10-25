package com.example.messaging.common;

import org.springframework.http.HttpStatus;

/** Stable machine-readable error codes (CONTRACT section 5). */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    UNSUPPORTED_CHANNEL(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY),
    IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT),
    ILLEGAL_STATE_TRANSITION(HttpStatus.CONFLICT),
    TEMPLATE_NOT_FOUND(HttpStatus.UNPROCESSABLE_ENTITY),
    TEMPLATE_VARIABLE_MISSING(HttpStatus.UNPROCESSABLE_ENTITY),
    TEMPLATE_RENDER_ERROR(HttpStatus.UNPROCESSABLE_ENTITY),
    RECIPIENT_NOT_FOUND(HttpStatus.UNPROCESSABLE_ENTITY),
    POLICY_SUPPRESSED(HttpStatus.UNPROCESSABLE_ENTITY),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),
    CONFLICT_OPTIMISTIC_LOCK(HttpStatus.CONFLICT),
    /** Additive (see docs/design/DEVIATIONS.md): unique-constraint style conflicts, e.g. duplicate template code. */
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT),
    INVALID_SIGNATURE(HttpStatus.UNAUTHORIZED),
    REPLAYED_EVENT(HttpStatus.CONFLICT),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
