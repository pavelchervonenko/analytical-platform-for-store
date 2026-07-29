package com.storeanalytics.integration.livesklad.client;

import com.storeanalytics.integration.livesklad.exception.LiveSkladPayloadRejectedException;
import com.storeanalytics.integration.livesklad.exception.LiveSkladPayloadRejectedException.Reason;
import com.storeanalytics.integration.livesklad.observability.LiveSkladPayloadRejectionMetrics;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

final class LiveSkladResponseGuard implements ClientHttpRequestInterceptor {

    private final long maxResponseBytes;
    private final LiveSkladPayloadRejectionMetrics rejectionMetrics;

    LiveSkladResponseGuard(long maxResponseBytes) {
        this(maxResponseBytes, LiveSkladPayloadRejectionMetrics.noop());
    }

    LiveSkladResponseGuard(
            long maxResponseBytes,
            LiveSkladPayloadRejectionMetrics rejectionMetrics
    ) {
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
        this.maxResponseBytes = maxResponseBytes;
        this.rejectionMetrics = rejectionMetrics;
    }

    @Override
    public ClientHttpResponse intercept(
            org.springframework.http.HttpRequest request,
            byte[] body,
            ClientHttpRequestExecution execution
    ) throws IOException {
        ClientHttpResponse response = execution.execute(request, body);
        try {
            validateHeaders(response);
            return new LimitedClientHttpResponse(response, maxResponseBytes);
        } catch (RuntimeException exception) {
            response.close();
            throw exception;
        }
    }

    private void validateHeaders(ClientHttpResponse response) throws IOException {
        long contentLength = response.getHeaders().getContentLength();
        if (contentLength > maxResponseBytes) {
            throw rejected(
                    Reason.RESPONSE_TOO_LARGE,
                    "LiveSklad response exceeds the configured byte limit"
            );
        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            return;
        }
        validateContentType(response.getHeaders().getContentType());
        validateContentEncoding(response.getHeaders());
    }

    private void validateContentType(MediaType contentType) {
        if (contentType == null || !isJson(contentType)) {
            throw rejected(
                    Reason.UNSUPPORTED_CONTENT_TYPE,
                    "LiveSklad successful response must contain JSON"
            );
        }
    }

    private boolean isJson(MediaType contentType) {
        String subtype = contentType.getSubtype().toLowerCase(Locale.ROOT);
        return subtype.equals("json") || subtype.endsWith("+json");
    }

    private void validateContentEncoding(HttpHeaders headers) {
        List<String> values = headers.getOrEmpty(HttpHeaders.CONTENT_ENCODING);
        boolean unsupported = values.stream()
                .flatMap(value -> List.of(value.split(",")).stream())
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .anyMatch(value -> !value.equalsIgnoreCase("identity"));
        if (unsupported) {
            throw rejected(
                    Reason.UNSUPPORTED_CONTENT_ENCODING,
                    "LiveSklad response uses an unsupported content encoding"
            );
        }
    }

    private LiveSkladPayloadRejectedException rejected(
            Reason reason,
            String message
    ) {
        rejectionMetrics.record(reason);
        return new LiveSkladPayloadRejectedException(reason, message);
    }

    private static final class LimitedClientHttpResponse
            implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final long maxResponseBytes;
        private InputStream body;

        private LimitedClientHttpResponse(
                ClientHttpResponse delegate,
                long maxResponseBytes
        ) {
            this.delegate = delegate;
            this.maxResponseBytes = maxResponseBytes;
        }

        @Override
        public org.springframework.http.HttpStatusCode getStatusCode()
                throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() throws IOException {
            if (body == null) {
                body = new LimitedInputStream(delegate.getBody(), maxResponseBytes);
            }
            return body;
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {

        private final long maximum;
        private long count;

        private LimitedInputStream(InputStream input, long maximum) {
            super(input);
            this.maximum = maximum;
        }

        @Override
        public int read() throws IOException {
            if (count == maximum) {
                return probeForEndOfInput();
            }
            int value = in.read();
            if (value >= 0) {
                count++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            if (count == maximum) {
                return probeForEndOfInput();
            }
            int permitted = (int) Math.min(length, maximum - count);
            int read = in.read(buffer, offset, permitted);
            if (read > 0) {
                count += read;
            }
            return read;
        }

        @Override
        public long skip(long requested) throws IOException {
            if (requested <= 0) {
                return 0;
            }
            if (count == maximum) {
                probeForEndOfInput();
                return 0;
            }
            long skipped = in.skip(Math.min(requested, maximum - count));
            count += skipped;
            return skipped;
        }

        private int probeForEndOfInput() throws IOException {
            if (in.read() == -1) {
                return -1;
            }
            throw new ResponseSizeLimitIOException();
        }
    }

    static final class ResponseSizeLimitIOException extends IOException {

        private ResponseSizeLimitIOException() {
            super("LiveSklad response exceeds the configured byte limit");
        }
    }
}
