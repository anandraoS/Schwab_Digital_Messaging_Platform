package com.example.messaging.security;

import com.example.messaging.common.ApiErrorWriter;
import com.example.messaging.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Spring Security deliberately rethrows {@link AuthenticationServiceException} (an infrastructure failure such as an
 * unreachable JWKS endpoint, as opposed to a bad credential). Without this filter that surfaces as an HTML 500. Here it
 * becomes a 503 {@code SERVICE_UNAVAILABLE} ApiError with {@code Retry-After}; the request is still denied (fail
 * closed), never treated as authenticated.
 */
public class IdentityProviderUnavailableFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdentityProviderUnavailableFilter.class);

    private final ApiErrorWriter errors;

    public IdentityProviderUnavailableFilter(ApiErrorWriter errors) {
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (AuthenticationServiceException e) {
            log.warn("Token validation infrastructure failure: {}", e.getClass().getSimpleName());
            response.setHeader("Retry-After", "5");
            errors.write(request, response, ErrorCode.SERVICE_UNAVAILABLE,
                    "The identity provider is unavailable; retry shortly");
        }
    }
}
