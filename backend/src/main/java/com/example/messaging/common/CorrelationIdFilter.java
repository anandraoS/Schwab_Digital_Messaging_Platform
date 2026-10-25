package com.example.messaging.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts or generates {@code X-Correlation-Id}, exposes it via MDC and the response, and stamps
 * {@code API-Version}. Inbound values are validated so they cannot be used for log injection.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    public static final String ATTRIBUTE = CorrelationIdFilter.class.getName() + ".id";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String id = incoming != null && SAFE.matcher(incoming).matches() ? incoming : Ids.newId();
        request.setAttribute(ATTRIBUTE, id);
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        response.setHeader("API-Version", "1");
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
            MDC.remove("tenant");
            MDC.remove("messageId");
        }
    }

    public static String current(HttpServletRequest request) {
        Object attr = request == null ? null : request.getAttribute(ATTRIBUTE);
        if (attr instanceof String s) {
            return s;
        }
        return MDC.get(MDC_KEY);
    }
}
