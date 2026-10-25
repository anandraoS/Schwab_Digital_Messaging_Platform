package com.example.messaging.security;

import com.example.messaging.common.ApiErrorWriter;
import com.example.messaging.common.ErrorCode;
import com.example.messaging.config.MessagingProperties;
import com.example.messaging.coordination.CoordinationService;
import com.example.messaging.coordination.RateLimitDecision;
import com.example.messaging.metrics.MessagingMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Token bucket per authenticated client (JWT subject) using the {@link CoordinationService}. Emits
 * {@code X-RateLimit-*} headers, and 429 + {@code Retry-After} when the bucket is empty.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final CoordinationService coordination;
    private final MessagingProperties props;
    private final MessagingMetrics metrics;
    private final ApiErrorWriter errors;

    public RateLimitFilter(CoordinationService coordination, MessagingProperties props, MessagingMetrics metrics,
            ApiErrorWriter errors) {
        this.coordination = coordination;
        this.props = props;
        this.metrics = metrics;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return p.startsWith("/actuator") || p.startsWith("/dev/token") || p.startsWith("/v3/api-docs")
                || p.startsWith("/swagger-ui");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof TenantAwareAuthentication) || !auth.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }
        MessagingProperties.Api api = props.api();
        RateLimitDecision d = coordination.tryAcquire("api:" + auth.getName(), api.ratePerSec(), api.burst());
        response.setHeader("X-RateLimit-Limit", Integer.toString(d.limit()));
        response.setHeader("X-RateLimit-Remaining", Long.toString(d.remaining()));
        response.setHeader("X-RateLimit-Reset", Long.toString(Math.max(0, (d.resetMillis() + 999) / 1000)));
        if (!d.allowed()) {
            metrics.rateLimitRejected("api");
            response.setHeader("Retry-After", Long.toString(Math.max(1, (d.retryAfterMillis() + 999) / 1000)));
            errors.write(request, response, ErrorCode.RATE_LIMITED, "Rate limit exceeded; retry later");
            return;
        }
        chain.doFilter(request, response);
    }
}
