package com.storeanalytics.auth.web;

import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.auth.service.AuthenticationService;
import com.storeanalytics.auth.service.CurrentUserViewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final AuthenticationService authenticationService;
    private final CurrentUserViewService currentUserViewService;
    private final AuthControllerSecurityComponents security;

    public AuthController(
            AuthenticationManager authenticationManager,
            AuthenticationService authenticationService,
            CurrentUserViewService currentUserViewService,
            AuthControllerSecurityComponents security
    ) {
        this.authenticationManager = authenticationManager;
        this.authenticationService = authenticationService;
        this.currentUserViewService = currentUserViewService;
        this.security = security;
    }

    @GetMapping("/csrf")
    CsrfConfigurationResponse csrf(CsrfToken csrfToken) {
        return new CsrfConfigurationResponse(csrfToken.getHeaderName(), "XSRF-TOKEN");
    }

    @PostMapping("/login")
    CurrentUserResponse login(
            @Valid @RequestBody LoginRequest loginRequest,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        String clientAddress = request.getRemoteAddr();
        security.loginThrottleService().checkAllowed(loginRequest.email(), clientAddress);

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            loginRequest.email(),
                            loginRequest.password()
                    )
            );
        } catch (AuthenticationException exception) {
            security.loginThrottleService().recordFailure(loginRequest.email(), clientAddress);
            security.securityAuditLogger().loginFailed(loginRequest.email(), clientAddress);
            throw exception;
        }

        AppUserPrincipal principal = requirePrincipal(authentication);
        authenticationService.recordSuccessfulLogin(
                principal.getUserId(),
                loginRequest.password()
        );
        security.loginThrottleService().recordSuccess(loginRequest.email());

        HttpSession session = request.getSession(true);
        request.changeSessionId();
        expireOldSessions(principal);
        security.sessionRegistry().registerNewSession(session.getId(), principal);

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(securityContext);
        security.securityContextRepository().saveContext(
                securityContext,
                request,
                response
        );

        security.csrfTokenRepository().saveToken(null, request, response);
        security.securityAuditLogger().loginSucceeded(principal.getUserId(), clientAddress);
        return currentUserViewService.create(principal);
    }

    @GetMapping("/me")
    CurrentUserResponse currentUser(Authentication authentication) {
        return currentUserViewService.create(requirePrincipal(authentication));
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(
            @Valid @RequestBody ChangePasswordRequest changeRequest,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        AppUserPrincipal principal = requirePrincipal(authentication);
        authenticationService.changePassword(
                principal.getUserId(),
                changeRequest.currentPassword(),
                changeRequest.newPassword()
        );
        security.securityAuditLogger().passwordChanged(principal.getUserId());
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        new CookieClearingLogoutHandler("JSESSIONID", "XSRF-TOKEN")
                .logout(request, response, authentication);
    }

    private void expireOldSessions(AppUserPrincipal principal) {
        List<SessionInformation> sessions = security.sessionRegistry()
                .getAllSessions(principal, false)
                .stream()
                .sorted(Comparator.comparing(SessionInformation::getLastRequest))
                .toList();
        int sessionsToExpire = sessions.size()
                - security.securityProperties().maxConcurrentSessions()
                + 1;
        for (int index = 0; index < sessionsToExpire; index++) {
            sessions.get(index).expireNow();
        }
    }

    private AppUserPrincipal requirePrincipal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
            throw new IllegalStateException("Application authentication principal is missing");
        }
        return principal;
    }
}
