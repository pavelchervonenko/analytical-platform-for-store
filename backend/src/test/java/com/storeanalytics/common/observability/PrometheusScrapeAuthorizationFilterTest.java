package com.storeanalytics.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.common.config.PrometheusScrapeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PrometheusScrapeAuthorizationFilterTest {

    private static final String TOKEN = "prometheus-test-token-with-32-chars";

    @Test
    void returnsNotFoundWhenScrapingIsNotConfigured() throws Exception {
        MockHttpServletResponse response = filter("")
                .doRequest(null);

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void challengesMissingOrInvalidBearerToken() throws Exception {
        MockHttpServletResponse missing = filter(TOKEN)
                .doRequest(null);
        MockHttpServletResponse invalid = filter(TOKEN)
                .doRequest("Bearer wrong-token-with-at-least-32-characters");

        assertThat(missing.getStatus()).isEqualTo(401);
        assertThat(missing.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer");
        assertThat(invalid.getStatus()).isEqualTo(401);
    }

    @Test
    void allowsMatchingBearerToken() throws Exception {
        RequestResult result = filter(TOKEN)
                .execute("bearer " + TOKEN);

        assertThat(result.response().getStatus()).isEqualTo(200);
        assertThat(result.chain().getRequest()).isNotNull();
    }

    private FilterRequest filter(String token) {
        return new FilterRequest(new PrometheusScrapeAuthorizationFilter(
                new PrometheusScrapeProperties(token)
        ));
    }

    private record FilterRequest(
            PrometheusScrapeAuthorizationFilter filter
    ) {

        MockHttpServletResponse doRequest(String authorization)
                throws Exception {
            return execute(authorization).response();
        }

        RequestResult execute(String authorization) throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRequestURI("/actuator/prometheus");
            if (authorization != null) {
                request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
            }
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request, response, chain);
            return new RequestResult(response, chain);
        }
    }

    private record RequestResult(
            MockHttpServletResponse response,
            MockFilterChain chain
    ) {
    }
}
