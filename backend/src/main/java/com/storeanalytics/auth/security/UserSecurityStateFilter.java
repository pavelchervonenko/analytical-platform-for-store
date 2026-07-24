package com.storeanalytics.auth.security;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.common.config.ApplicationSecurityProperties;
import com.storeanalytics.common.security.JsonAuthenticationEntryPoint;
import com.storeanalytics.common.security.SecurityAuditLogger;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class UserSecurityStateFilter extends OncePerRequestFilter {

    private final AppUserRepository userRepository;
    private final JsonAuthenticationEntryPoint authenticationEntryPoint;
    private final ApplicationSecurityProperties securityProperties;
    private final SecurityAuditLogger securityAuditLogger;
    private final Clock clock;

    public UserSecurityStateFilter(
            AppUserRepository userRepository,
            JsonAuthenticationEntryPoint authenticationEntryPoint,
            ApplicationSecurityProperties securityProperties,
            SecurityAuditLogger securityAuditLogger,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.securityProperties = securityProperties;
        this.securityAuditLogger = securityAuditLogger;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AppUserPrincipal principal) {
            String rejectionReason = rejectionReason(request, principal);
            if (rejectionReason != null) {
                securityAuditLogger.sessionRejected(principal.getUserId(), rejectionReason);
                new SecurityContextLogoutHandler().logout(request, response, authentication);
                authenticationEntryPoint.commence(
                        request,
                        response,
                        new InsufficientAuthenticationException("User session is no longer valid")
                );
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private String rejectionReason(HttpServletRequest request, AppUserPrincipal principal) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            Instant expiresAt = Instant.ofEpochMilli(session.getCreationTime())
                    .plus(securityProperties.sessionAbsoluteTimeout());
            if (!Instant.now(clock).isBefore(expiresAt)) {
                return "absolute_timeout";
            }
        }

        AppUser user = userRepository.findById(principal.getUserId()).orElse(null);
        if (user == null) {
            return "user_missing";
        }
        if (!user.isActive()) {
            return "user_disabled";
        }
        if (user.getSecurityVersion() != principal.getSecurityVersion()) {
            return "security_version_changed";
        }
        return null;
    }
}
