package com.storeanalytics.integration.livesklad.webhook;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

final class BoundedLiveSkladWebhookRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    BoundedLiveSkladWebhookRequest(HttpServletRequest request, int maxBytes)
            throws IOException {
        super(request);
        body = request.getInputStream().readNBytes(maxBytes + 1);
        if (body.length > maxBytes) {
            throw new LiveSkladWebhookBodyTooLargeException();
        }
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream input = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return input.read();
            }

            @Override
            public boolean isFinished() {
                return input.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // Synchronous MVC request; async callbacks are not used.
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(
                getInputStream(),
                StandardCharsets.UTF_8
        ));
    }

    @Override
    public int getContentLength() {
        return body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }
}
