package com.example.messaging.common;

import java.util.List;

/** Base class for exceptions that map directly to an {@link ApiError}. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final transient List<ApiError.Detail> details;

    public ApiException(ErrorCode code, String message) {
        this(code, message, List.of());
    }

    public ApiException(ErrorCode code, String message, List<ApiError.Detail> details) {
        super(message);
        this.code = code;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public ErrorCode getCode() {
        return code;
    }

    public List<ApiError.Detail> getDetails() {
        return details;
    }

    public static class NotFoundException extends ApiException {
        public NotFoundException(String what) {
            super(ErrorCode.NOT_FOUND, what + " not found");
        }
    }

    public static class ValidationException extends ApiException {
        public ValidationException(String message, List<ApiError.Detail> details) {
            super(ErrorCode.VALIDATION_FAILED, message, details);
        }

        public ValidationException(String field, String message) {
            super(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                    List.of(new ApiError.Detail(field, message)));
        }
    }

    public static class ConflictException extends ApiException {
        public ConflictException(ErrorCode code, String message) {
            super(code, message);
        }
    }

    public static class ForbiddenException extends ApiException {
        public ForbiddenException(String message) {
            super(ErrorCode.FORBIDDEN, message);
        }
    }

    public static class UnprocessableException extends ApiException {
        public UnprocessableException(ErrorCode code, String message) {
            super(code, message);
        }

        public UnprocessableException(ErrorCode code, String message, List<ApiError.Detail> details) {
            super(code, message, details);
        }
    }

    /** 503 with an optional Retry-After hint (backpressure, dependency outage). */
    public static class ServiceUnavailableException extends ApiException {
        private final long retryAfterSeconds;

        public ServiceUnavailableException(String message, long retryAfterSeconds) {
            super(ErrorCode.SERVICE_UNAVAILABLE, message);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long getRetryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
