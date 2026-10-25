package com.example.messaging.security;

import com.example.messaging.common.ApiErrorWriter;
import com.example.messaging.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects bodies over {@code messaging.api.max-body-bytes} with 413. Declared Content-Length is checked up front;
 * chunked bodies are counted while being read (the resulting {@link RequestTooLargeException} is mapped to 413).
 * Multipart bulk uploads use their own, larger limit (spring.servlet.multipart) and skip this filter.
 */
public class RequestSizeFilter extends OncePerRequestFilter {

    private final long maxBytes;
    private final ApiErrorWriter errors;

    public RequestSizeFilter(long maxBytes, ApiErrorWriter errors) {
        this.maxBytes = maxBytes;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String ct = request.getContentType();
        return ct != null && ct.toLowerCase().startsWith("multipart/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            errors.write(request, response, ErrorCode.PAYLOAD_TOO_LARGE,
                    "Request body exceeds the " + maxBytes + " byte limit");
            return;
        }
        if (declared < 0 && request.getHeader("Transfer-Encoding") != null) {
            chain.doFilter(new LimitedRequest(request, maxBytes), response);
            return;
        }
        chain.doFilter(request, response);
    }

    /** Thrown while reading a chunked body that exceeds the limit. */
    public static class RequestTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        public RequestTooLargeException(long max) {
            super("Request body exceeds " + max + " bytes");
        }
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long max;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long max) {
            super(request);
            this.max = max;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedStream(super.getInputStream(), max);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String enc = getCharacterEncoding();
            Charset cs = enc == null ? StandardCharsets.UTF_8 : Charset.forName(enc);
            return new BufferedReader(new InputStreamReader(getInputStream(), cs));
        }
    }

    private static final class LimitedStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long max;
        private long count;

        LimitedStream(ServletInputStream delegate, long max) {
            this.delegate = delegate;
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0 && ++count > max) {
                throw new RequestTooLargeException(max);
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = delegate.read(buf, off, len);
            if (n > 0) {
                count += n;
                if (count > max) {
                    throw new RequestTooLargeException(max);
                }
            }
            return n;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
