package com.storeanalytics.common.config;

import com.storeanalytics.auth.security.PasswordChangedAuthorizationManager;
import com.storeanalytics.auth.security.UserSecurityStateFilter;
import com.storeanalytics.common.security.JsonAccessDeniedHandler;
import com.storeanalytics.common.security.JsonAuthenticationEntryPoint;
import com.storeanalytics.common.security.JsonSessionInformationExpiredStrategy;
import org.springframework.stereotype.Component;

@Component
public record SecurityChainComponents(
        PasswordChangedAuthorizationManager passwordChanged,
        JsonAuthenticationEntryPoint authenticationEntryPoint,
        JsonAccessDeniedHandler accessDeniedHandler,
        JsonSessionInformationExpiredStrategy expiredSessionStrategy,
        UserSecurityStateFilter userSecurityStateFilter
) {
}
