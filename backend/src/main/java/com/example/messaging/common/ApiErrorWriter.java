package com.example.messaging.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/** Builds and writes {@link ApiError} bodies, also from servlet filters and security handlers. */
@Component
public class ApiErrorWriter {

    private final ObjectMapper mapper;
    private final Clock clock;

    public ApiErrorWriter(ObjectMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public ApiError build(HttpServletRequest request, int status, String reason, ErrorCode code, String message,
            List<ApiError.Detail> details) {
        return new ApiError(clock.instant(), status, reason, code.name(), message,
                request == null ? null : request.getRequestURI(), CorrelationIdFilter.current(request),
                details == null || details.isEmpty() ? null : details);
    }

    public ApiError build(HttpServletRequest request, ErrorCode code, String message, List<ApiError.Detail> details) {
        return build(request, code.status().value(), code.status().getReasonPhrase(), code, message, details);
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code, String message)
            throws IOException {
        write(request, response, code, message, null);
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code, String message,
            List<ApiError.Detail> details) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), build(request, code, message, details));
    }
}
