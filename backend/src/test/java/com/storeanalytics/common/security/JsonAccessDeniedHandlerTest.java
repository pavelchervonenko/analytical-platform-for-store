package com.storeanalytics.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import tools.jackson.databind.ObjectMapper;
import com.storeanalytics.common.web.CorrelationId;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfException;

class JsonAccessDeniedHandlerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void classifiesCsrfRejectionSeparatelyFromGeneralAccessDenial()
            throws Exception {
        ClientAddressResolver resolver = mock(ClientAddressResolver.class);
        SecurityAuditLogger auditLogger = mock(SecurityAuditLogger.class);
        JsonAccessDeniedHandler handler = new JsonAccessDeniedHandler(
                new ObjectMapper().rebuild().findAndAddModules().build(), resolver, auditLogger
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        ClientAddress clientAddress = new ClientAddress(
                "203.0.113.25", "203.0.113.25"
        );
        when(resolver.resolve(request)).thenReturn(clientAddress);

        handler.handle(request, response, mock(CsrfException.class));

        verify(auditLogger).csrfRejected(null, clientAddress);
        assertThat(response.getStatus()).isEqualTo(403);
        String requestId = response.getHeader(CorrelationId.HEADER_NAME);
        assertThat(UUID.fromString(requestId)).isNotNull();
        assertThat(
                new ObjectMapper()
                        .rebuild().findAndAddModules().build()
                        .readTree(response.getContentAsString())
                        .path("correlationId")
                        .asString()
        ).isEqualTo(requestId);
    }

    @Test
    void classifiesNonCsrfForbiddenResponseAsAccessDenial() throws Exception {
        ClientAddressResolver resolver = mock(ClientAddressResolver.class);
        SecurityAuditLogger auditLogger = mock(SecurityAuditLogger.class);
        JsonAccessDeniedHandler handler = new JsonAccessDeniedHandler(
                new ObjectMapper().rebuild().findAndAddModules().build(), resolver, auditLogger
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        ClientAddress clientAddress = new ClientAddress(
                "203.0.113.25", "203.0.113.25"
        );
        when(resolver.resolve(request)).thenReturn(clientAddress);

        handler.handle(
                request,
                response,
                new AccessDeniedException("sensitive failure details")
        );

        verify(auditLogger).accessDenied(null, clientAddress);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .doesNotContain("sensitive failure details");
    }
}
