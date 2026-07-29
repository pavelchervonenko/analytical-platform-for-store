package com.storeanalytics.auth.web;

import com.storeanalytics.auth.security.BreakGlassAccessMonitor;
import com.storeanalytics.auth.service.LoginThrottleService;
import com.storeanalytics.common.security.ClientAddressResolver;
import com.storeanalytics.common.security.SecurityAuditLogger;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

@Component
public record AuthControllerSecurityComponents(
        SecurityContextRepository securityContextRepository,
        CsrfTokenRepository csrfTokenRepository,
        LoginThrottleService loginThrottleService,
        ClientAddressResolver clientAddressResolver,
        BreakGlassAccessMonitor breakGlassAccessMonitor,
        SecurityAuditLogger securityAuditLogger
) {
}
