package com.storeanalytics.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

class SpaCsrfTokenRequestHandlerTest {

    private static final String HEADER_NAME = "X-XSRF-TOKEN";
    private static final String PARAMETER_NAME = "_csrf";
    private static final String RAW_TOKEN = "raw-csrf-token";

    private final SpaCsrfTokenRequestHandler handler = new SpaCsrfTokenRequestHandler();

    @Test
    void resolvesRawCookieTokenFromSpaHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HEADER_NAME, RAW_TOKEN);
        CsrfToken csrfToken = csrfToken();

        String resolvedToken = handler.resolveCsrfTokenValue(request, csrfToken);

        assertThat(resolvedToken).isEqualTo(RAW_TOKEN);
    }

    @Test
    void resolvesMaskedTokenFromFormParameter() {
        MockHttpServletRequest renderRequest = new MockHttpServletRequest();
        CsrfToken csrfToken = csrfToken();
        handler.handle(renderRequest, new MockHttpServletResponse(), () -> csrfToken);
        CsrfToken renderedToken = (CsrfToken) renderRequest.getAttribute(PARAMETER_NAME);

        MockHttpServletRequest submitRequest = new MockHttpServletRequest();
        submitRequest.addParameter(PARAMETER_NAME, renderedToken.getToken());

        String resolvedToken = handler.resolveCsrfTokenValue(submitRequest, csrfToken);

        assertThat(resolvedToken).isEqualTo(RAW_TOKEN);
    }

    @Test
    void eagerlyLoadsDeferredTokenSoCookieCanBeRefreshed() {
        AtomicInteger loads = new AtomicInteger();

        handler.handle(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                () -> {
                    loads.incrementAndGet();
                    return csrfToken();
                }
        );

        assertThat(loads).hasValue(1);
    }

    private CsrfToken csrfToken() {
        return new DefaultCsrfToken(HEADER_NAME, PARAMETER_NAME, RAW_TOKEN);
    }
}
