package com.storeanalytics.auth.web;

import com.storeanalytics.auth.exception.LoginThrottledException;
import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.auth.service.ActiveSessionView;
import com.storeanalytics.auth.service.AuthenticationService;
import com.storeanalytics.auth.service.CurrentUserViewService;
import com.storeanalytics.auth.service.UserSessionService;
import com.storeanalytics.common.security.ClientAddress;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final UserSessionService userSessionService;
    private final AuthControllerSecurityComponents security;

    public AuthController(
            AuthenticationManager authenticationManager,
            AuthenticationService authenticationService,
            CurrentUserViewService currentUserViewService,
            UserSessionService userSessionService,
            AuthControllerSecurityComponents security
    ) {
        this.authenticationManager = authenticationManager;
        this.authenticationService = authenticationService;
        this.currentUserViewService = currentUserViewService;
        this.userSessionService = userSessionService;
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
        ClientAddress clientAddress = security.clientAddressResolver()
                .resolve(request);
        try {
            security.loginThrottleService().checkAllowed(
                    loginRequest.email(), clientAddress
            );
        } catch (LoginThrottledException exception) {
            security.securityAuditLogger().loginThrottled(
                    loginRequest.email(), clientAddress
            );
            throw exception;
        }

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
        boolean breakGlassLogin = security.breakGlassAccessMonitor()
                .recordSuccessfulLogin(principal.getUserId());
        security.loginThrottleService().recordSuccess(loginRequest.email());

        HttpSession session = request.getSession(true);
        request.changeSessionId();
        userSessionService.registerSession(principal, session.getId());

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
        if (breakGlassLogin) {
            security.securityAuditLogger().breakGlassLoginSucceeded(
                    principal.getUserId(), clientAddress
            );
        }
        return currentUserViewService.create(principal);
    }

    @GetMapping("/me")
    CurrentUserResponse currentUser(Authentication authentication) {
        return currentUserViewService.create(requirePrincipal(authentication));
    }

    @GetMapping("/sessions")
    ActiveSessionListResponse activeSessions(
            Authentication authentication,
            HttpServletRequest request
    ) {
        AppUserPrincipal principal = requirePrincipal(authentication);
        return new ActiveSessionListResponse(
                userSessionService.listSessions(
                                principal,
                                currentSessionId(request)
                        ).stream()
                        .map(this::response)
                        .toList()
        );
    }

    @DeleteMapping("/sessions/{sessionReference}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeSession(
            @PathVariable String sessionReference,
            Authentication authentication,
            HttpServletRequest request
    ) {
        userSessionService.revokeSession(
                requirePrincipal(authentication),
                currentSessionId(request),
                sessionReference
        );
    }

    @DeleteMapping("/sessions/others")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeOtherSessions(
            Authentication authentication,
            HttpServletRequest request
    ) {
        userSessionService.revokeOtherSessions(
                requirePrincipal(authentication),
                currentSessionId(request)
        );
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

    private String currentSessionId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new IllegalStateException(
                    "Authenticated request has no HTTP session"
            );
        }
        return session.getId();
    }

    private ActiveSessionResponse response(ActiveSessionView session) {
        return new ActiveSessionResponse(
                session.sessionReference(),
                session.lastSeenAt(),
                session.current()
        );
    }

    private AppUserPrincipal requirePrincipal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
            throw new IllegalStateException("Application authentication principal is missing");
        }
        return principal;
    }
}
