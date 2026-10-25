package com.example.messaging.web;

import com.example.messaging.common.ApiError;
import com.example.messaging.common.ApiErrorWriter;
import com.example.messaging.common.ApiException;
import com.example.messaging.common.ErrorCode;
import com.example.messaging.domain.Channel;
import com.example.messaging.domain.IllegalStateTransitionException;
import com.example.messaging.messaging.BusRejectedException;
import com.example.messaging.security.RequestSizeFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps every failure to the documented {@link ApiError} payload. Internal details never reach the client. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ApiErrorWriter errors;

    public GlobalExceptionHandler(ApiErrorWriter errors) {
        this.errors = errors;
    }

    private ResponseEntity<ApiError> respond(HttpServletRequest req, ErrorCode code, String message,
            List<ApiError.Detail> details) {
        return respond(req, code.status(), code, message, details, null);
    }

    private ResponseEntity<ApiError> respond(HttpServletRequest req, HttpStatus status, ErrorCode code, String message,
            List<ApiError.Detail> details, HttpHeaders headers) {
        ApiError body = errors.build(req, status.value(), status.getReasonPhrase(), code, message, details);
        ResponseEntity.BodyBuilder b = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
        if (headers != null) {
            b.headers(headers);
        }
        return b.body(body);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> api(ApiException e, HttpServletRequest req) {
        HttpHeaders h = new HttpHeaders();
        if (e instanceof ApiException.ServiceUnavailableException su && su.getRetryAfterSeconds() > 0) {
            h.set(HttpHeaders.RETRY_AFTER, Long.toString(su.getRetryAfterSeconds()));
        }
        return respond(req, e.getCode().status(), e.getCode(), e.getMessage(), e.getDetails(), h);
    }

    @ExceptionHandler(BusRejectedException.class)
    public ResponseEntity<ApiError> busRejected(BusRejectedException e, HttpServletRequest req) {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, e.getRetryAfterSeconds())));
        return respond(req, HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE, e.getMessage(), null, h);
    }

    /** Database unreachable or a transient lock failure: fail closed with a retryable 503 (clients reuse the same key). */
    @ExceptionHandler({org.springframework.dao.DataAccessResourceFailureException.class,
            org.springframework.transaction.CannotCreateTransactionException.class,
            org.springframework.dao.TransientDataAccessException.class})
    public ResponseEntity<ApiError> dependencyUnavailable(Exception e, HttpServletRequest req) {
        log.warn("Database dependency unavailable on {} {}: {}", req.getMethod(), req.getRequestURI(),
                e.getClass().getSimpleName());
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.RETRY_AFTER, "5");
        return respond(req, HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE,
                "A required dependency is temporarily unavailable; retry with the same idempotency key", null, h);
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<ApiError> illegalTransition(IllegalStateTransitionException e, HttpServletRequest req) {
        return respond(req, ErrorCode.ILLEGAL_STATE_TRANSITION, e.getMessage(), null);
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<ApiError> optimistic(Exception e, HttpServletRequest req) {
        return respond(req, ErrorCode.CONFLICT_OPTIMISTIC_LOCK,
                "The resource was modified concurrently; reload and retry", null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> integrity(DataIntegrityViolationException e, HttpServletRequest req) {
        log.warn("Data integrity violation: {}", e.getMostSpecificCause().getClass().getSimpleName());
        return respond(req, ErrorCode.DUPLICATE_RESOURCE, "The request conflicts with existing data", null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException e, HttpServletRequest req) {
        List<ApiError.Detail> details = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.Detail(f.getField(), f.getDefaultMessage())).collect(Collectors.toList());
        e.getBindingResult().getGlobalErrors().forEach(g -> details.add(new ApiError.Detail(g.getObjectName(), g.getDefaultMessage())));
        return respond(req, ErrorCode.VALIDATION_FAILED, "Request validation failed", details);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> methodValidation(HandlerMethodValidationException e, HttpServletRequest req) {
        List<ApiError.Detail> details = e.getAllErrors().stream()
                .map(m -> new ApiError.Detail(null, m.getDefaultMessage())).toList();
        return respond(req, ErrorCode.VALIDATION_FAILED, "Request validation failed", details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> constraint(ConstraintViolationException e, HttpServletRequest req) {
        List<ApiError.Detail> details = e.getConstraintViolations().stream()
                .map(v -> new ApiError.Detail(v.getPropertyPath().toString(), v.getMessage())).toList();
        return respond(req, ErrorCode.VALIDATION_FAILED, "Request validation failed", details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e, HttpServletRequest req) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof RequestSizeFilter.RequestTooLargeException) {
                return respond(req, ErrorCode.PAYLOAD_TOO_LARGE, "Request body is too large", null);
            }
            if (t == t.getCause()) {
                break;
            }
        }
        Throwable cause = e.getCause();
        if (cause instanceof InvalidFormatException ife && ife.getTargetType() != null && ife.getTargetType().isEnum()) {
            String field = path(ife);
            if (ife.getTargetType() == Channel.class) {
                return respond(req, ErrorCode.UNSUPPORTED_CHANNEL,
                        "Unsupported channel; supported channels are EMAIL, SMS, PUSH",
                        List.of(new ApiError.Detail(field, "must be one of EMAIL, SMS, PUSH")));
            }
            String allowed = java.util.Arrays.stream(ife.getTargetType().getEnumConstants())
                    .map(Object::toString).collect(Collectors.joining(", "));
            return respond(req, ErrorCode.VALIDATION_FAILED, "Request validation failed",
                    List.of(new ApiError.Detail(field, "must be one of " + allowed)));
        }
        if (cause instanceof MismatchedInputException mie && !(cause instanceof InvalidFormatException)
                && mie.getPath() != null && !mie.getPath().isEmpty()) {
            return respond(req, ErrorCode.MALFORMED_REQUEST, "Request body has an unexpected structure",
                    List.of(new ApiError.Detail(path(mie), "has an unexpected type or structure")));
        }
        if (cause instanceof InvalidFormatException ife) {
            return respond(req, ErrorCode.VALIDATION_FAILED, "Request validation failed",
                    List.of(new ApiError.Detail(path(ife), "has an invalid format")));
        }
        // never echo the offending content back
        return respond(req, ErrorCode.MALFORMED_REQUEST, "Request body is missing or is not valid JSON", null);
    }

    private static String path(JsonProcessingException e) {
        if (e instanceof com.fasterxml.jackson.databind.JsonMappingException jme) {
            String p = jme.getPath().stream().map(r -> r.getFieldName() != null ? r.getFieldName() : "[" + r.getIndex() + "]")
                    .collect(Collectors.joining("."));
            return p.replace(".[", "[");
        }
        return null;
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> missingHeader(MissingRequestHeaderException e, HttpServletRequest req) {
        return respond(req, ErrorCode.VALIDATION_FAILED, "Request validation failed",
                List.of(new ApiError.Detail(e.getHeaderName(), "header is required")));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> missingParam(MissingServletRequestParameterException e, HttpServletRequest req) {
        return respond(req, ErrorCode.VALIDATION_FAILED, "Request validation failed",
                List.of(new ApiError.Detail(e.getParameterName(), "parameter is required")));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest req) {
        if (e.getRequiredType() == Channel.class) {
            return respond(req, ErrorCode.UNSUPPORTED_CHANNEL, "Unsupported channel; supported channels are EMAIL, SMS, PUSH",
                    List.of(new ApiError.Detail(e.getName(), "must be one of EMAIL, SMS, PUSH")));
        }
        return respond(req, ErrorCode.VALIDATION_FAILED, "Request validation failed",
                List.of(new ApiError.Detail(e.getName(), "has an invalid value")));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> tooLarge(MaxUploadSizeExceededException e, HttpServletRequest req) {
        return respond(req, ErrorCode.PAYLOAD_TOO_LARGE, "Upload is too large", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException e, HttpServletRequest req) {
        HttpHeaders h = new HttpHeaders();
        java.util.Set<org.springframework.http.HttpMethod> supported = e.getSupportedHttpMethods();
        if (supported != null) {
            h.setAllow(supported);
        }
        return respond(req, HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.MALFORMED_REQUEST, "HTTP method not supported", null, h);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> mediaType(HttpMediaTypeNotSupportedException e, HttpServletRequest req) {
        return respond(req, HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.MALFORMED_REQUEST,
                "Content type not supported; use application/json", null, null);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiError> notAcceptable(HttpMediaTypeNotAcceptableException e, HttpServletRequest req) {
        return respond(req, HttpStatus.NOT_ACCEPTABLE, ErrorCode.MALFORMED_REQUEST, "Not acceptable", null, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> noResource(NoResourceFoundException e, HttpServletRequest req) {
        return respond(req, ErrorCode.NOT_FOUND, "Resource not found", null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> denied(AccessDeniedException e, HttpServletRequest req) {
        return respond(req, ErrorCode.FORBIDDEN, "You do not have permission to perform this action", null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> unauthenticated(AuthenticationException e, HttpServletRequest req) {
        return respond(req, ErrorCode.UNAUTHENTICATED, "Authentication is required", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest req) {
        // Spring's own ErrorResponse exceptions keep their status; everything else is an opaque 500.
        if (e instanceof org.springframework.web.ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            HttpStatus s = HttpStatus.valueOf(er.getStatusCode().value());
            return respond(req, s, s == HttpStatus.NOT_FOUND ? ErrorCode.NOT_FOUND : ErrorCode.MALFORMED_REQUEST,
                    s.getReasonPhrase(), null, null);
        }
        for (Throwable t = e; t != null && t.getCause() != t; t = t.getCause()) {
            if (t instanceof java.sql.SQLTransientConnectionException
                    || t instanceof java.sql.SQLNonTransientConnectionException
                    || t instanceof org.springframework.jdbc.CannotGetJdbcConnectionException) {
                return dependencyUnavailable(e, req);
            }
        }
        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), e);
        return respond(req, ErrorCode.INTERNAL_ERROR, "An unexpected error occurred", null);
    }
}
