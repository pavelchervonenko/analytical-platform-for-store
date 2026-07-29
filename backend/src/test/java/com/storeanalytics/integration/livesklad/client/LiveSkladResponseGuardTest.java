package com.storeanalytics.integration.livesklad.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

class LiveSkladResponseGuardTest {

    @Test
    void actualBytesRemainAuthoritativeWhenContentLengthIsTooSmall()
            throws IOException {
        MockClientHttpResponse response = jsonResponse(new byte[9]);
        response.getHeaders().setContentLength(4);

        InputStream body = guardedBody(response, 8);

        assertThatThrownBy(body::readAllBytes)
                .isInstanceOf(
                        LiveSkladResponseGuard.ResponseSizeLimitIOException.class
                );
    }

    @Test
    void skipCannotBypassByteLimit() throws IOException {
        InputStream body = guardedBody(jsonResponse(new byte[9]), 8);

        assertThat(body.skip(8)).isEqualTo(8);
        assertThatThrownBy(() -> body.skip(1))
                .isInstanceOf(
                        LiveSkladResponseGuard.ResponseSizeLimitIOException.class
                );
    }

    @Test
    void exactByteLimitIsAccepted() throws IOException {
        InputStream body = guardedBody(jsonResponse(new byte[8]), 8);

        assertThat(body.readAllBytes()).hasSize(8);
    }

    private InputStream guardedBody(
            MockClientHttpResponse response,
            long maximum
    ) throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(
                HttpMethod.GET,
                URI.create("https://livesklad.example.test/resource")
        );
        ClientHttpResponse guarded = new LiveSkladResponseGuard(maximum)
                .intercept(request, new byte[0], (ignoredRequest, ignoredBody) ->
                        response);
        return guarded.getBody();
    }

    private MockClientHttpResponse jsonResponse(byte[] body) {
        MockClientHttpResponse response = new MockClientHttpResponse(
                body,
                HttpStatus.OK
        );
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response;
    }
}
