package com.storeanalytics.auth.web;

import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.auth.service.AuthenticationService;
import com.storeanalytics.auth.service.CurrentUserViewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
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
    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final AuthenticationService authenticationService;
    private final CurrentUserViewService currentUserViewService;

    public AuthController(
            AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokenRepository,
            AuthenticationService authenticationService,
            CurrentUserViewService currentUserViewService
    ) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
        this.authenticationService = authenticationService;
        this.currentUserViewService = currentUserViewService;
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
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(
                        loginRequest.email(),
                        loginRequest.password()
                )
        );
        HttpSession session = request.getSession(true);
        request.changeSessionId();

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(securityContext);
        securityContextRepository.saveContext(securityContext, request, response);

        AppUserPrincipal principal = requirePrincipal(authentication);
        authenticationService.recordSuccessfulLogin(principal.getUserId());
        csrfTokenRepository.saveToken(null, request, response);
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
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        new CookieClearingLogoutHandler("JSESSIONID", "XSRF-TOKEN")
                .logout(request, response, authentication);
    }

    private AppUserPrincipal requirePrincipal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
            throw new IllegalStateException("Application authentication principal is missing");
        }
        return principal;
    }
}
