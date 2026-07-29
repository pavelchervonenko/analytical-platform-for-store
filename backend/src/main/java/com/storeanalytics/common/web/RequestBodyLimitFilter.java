package com.storeanalytics.common.web;

import com.storeanalytics.common.config.ResourceLimitsProperties;
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
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = "/api";
    private static final String ERROR_MESSAGE = "Request body is too large";
    private static final String CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

    private final long maximumBodyBytes;
    private final ObjectMapper objectMapper;

    public RequestBodyLimitFilter(
            ResourceLimitsProperties properties,
            ObjectMapper objectMapper
    ) {
        this.maximumBodyBytes = properties.http().maxRequestBodySize().toBytes();
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.equals(API_PREFIX) && !path.startsWith(API_PREFIX + "/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (request.getContentLengthLong() > maximumBodyBytes) {
            writePayloadTooLarge(request, response);
            return;
        }

        try {
            filterChain.doFilter(
                    new LimitedBodyRequest(request, maximumBodyBytes),
                    response
            );
        } catch (RequestBodyTooLargeException exception) {
            if (response.isCommitted()) {
                throw exception;
            }
            writePayloadTooLarge(request, response);
        }
    }

    private void writePayloadTooLarge(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        HttpStatus status = HttpStatus.CONTENT_TOO_LARGE;
        response.resetBuffer();
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(CONTENT_TYPE_OPTIONS, "nosniff");
        response.setHeader(
                CorrelationId.HEADER_NAME,
                CorrelationId.getOrCreateRequestId(request)
        );
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiErrorFactory.create(
                        status,
                        ApiErrorCode.PAYLOAD_TOO_LARGE,
                        ERROR_MESSAGE,
                        request
                )
        );
    }

    private static final class LimitedBodyRequest extends HttpServletRequestWrapper {

        private final long maximumBodyBytes;
        private ServletInputStream inputStream;
        private BufferedReader reader;

        private LimitedBodyRequest(HttpServletRequest request, long maximumBodyBytes) {
            super(request);
            this.maximumBodyBytes = maximumBodyBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (inputStream == null) {
                inputStream = new LimitedServletInputStream(
                        super.getInputStream(),
                        maximumBodyBytes
                );
            }
            return inputStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                String encoding = getCharacterEncoding();
                InputStreamReader inputReader;
                if (encoding == null) {
                    inputReader = new InputStreamReader(
                            getInputStream(), StandardCharsets.UTF_8
                    );
                } else {
                    inputReader = new InputStreamReader(getInputStream(), encoding);
                }
                reader = new BufferedReader(inputReader);
            }
            return reader;
        }
    }

    private static final class LimitedServletInputStream extends ServletInputStream {

        private static final int SKIP_BUFFER_SIZE = 8192;

        private final ServletInputStream delegate;
        private long remaining;

        private LimitedServletInputStream(
                ServletInputStream delegate,
                long maximumBodyBytes
        ) {
            this.delegate = delegate;
            this.remaining = maximumBodyBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value == -1) {
                return -1;
            }
            if (remaining == 0) {
                throw new RequestBodyTooLargeException();
            }
            remaining--;
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            int permitted = (int) Math.min((long) length, remaining + 1);
            int count = delegate.read(bytes, offset, permitted);
            if (count == -1) {
                return -1;
            }
            if (count > remaining) {
                remaining = 0;
                throw new RequestBodyTooLargeException();
            }
            remaining -= count;
            return count;
        }

        @Override
        public long skip(long length) throws IOException {
            if (length <= 0) {
                return 0;
            }
            byte[] buffer = new byte[(int) Math.min(length, SKIP_BUFFER_SIZE)];
            long skipped = 0;
            while (skipped < length) {
                int count = read(
                        buffer,
                        0,
                        (int) Math.min(buffer.length, length - skipped)
                );
                if (count == -1) {
                    break;
                }
                skipped += count;
            }
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min((long) delegate.available(), remaining + 1);
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

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public synchronized void reset() throws IOException {
            throw new IOException("Request body stream cannot be reset");
        }
    }
}
