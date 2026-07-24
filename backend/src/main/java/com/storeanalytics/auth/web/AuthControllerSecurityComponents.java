package com.storeanalytics.auth.web;

import com.storeanalytics.auth.service.LoginThrottleService;
import com.storeanalytics.common.config.ApplicationSecurityProperties;
import com.storeanalytics.common.security.SecurityAuditLogger;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

@Component
public record AuthControllerSecurityComponents(
        SecurityContextRepository securityContextRepository,
        CsrfTokenRepository csrfTokenRepository,
        LoginThrottleService loginThrottleService,
        SessionRegistry sessionRegistry,
        ApplicationSecurityProperties securityProperties,
        SecurityAuditLogger securityAuditLogger
) {
}
