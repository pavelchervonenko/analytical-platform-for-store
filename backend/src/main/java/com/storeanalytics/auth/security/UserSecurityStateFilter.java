package com.storeanalytics.auth.security;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.common.security.JsonAuthenticationEntryPoint;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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

    public UserSecurityStateFilter(
            AppUserRepository userRepository,
            JsonAuthenticationEntryPoint authenticationEntryPoint
    ) {
        this.userRepository = userRepository;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AppUserPrincipal principal) {
            AppUser user = userRepository.findById(principal.getUserId()).orElse(null);
            if (isStaleOrDisabled(principal, user)) {
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

    private boolean isStaleOrDisabled(AppUserPrincipal principal, AppUser user) {
        return user == null
                || !user.isActive()
                || user.getSecurityVersion() != principal.getSecurityVersion();
    }
}
