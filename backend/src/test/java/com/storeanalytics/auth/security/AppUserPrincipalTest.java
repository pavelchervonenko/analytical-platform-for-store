package com.storeanalytics.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserFeature;
import com.storeanalytics.auth.model.UserRole;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AppUserPrincipalTest {

    @Test
    void administratorAlwaysReceivesEveryFeature() {
        AppUser user = new AppUser("admin@example.com", "hash", "Admin", UserRole.ADMIN);

        AppUserPrincipal principal = AppUserPrincipal.from(user, Set.of());

        assertThat(principal.getFeatures()).containsExactlyInAnyOrder(UserFeature.values());
        assertThat(principal.getAuthorities())
                .extracting(authority -> authority.getAuthority())
                .contains("FEATURE_PLAN", "FEATURE_SHIFTS", "FEATURE_PAYROLL");
    }

    @Test
    void managerReceivesOnlyExplicitlyGrantedFeatures() {
        AppUser user = new AppUser("manager@example.com", "hash", "Manager", UserRole.MANAGER);

        AppUserPrincipal principal = AppUserPrincipal.from(
                user,
                Set.of(UserFeature.PLAN, UserFeature.PAYROLL)
        );

        assertThat(principal.getFeatures())
                .containsExactlyInAnyOrder(UserFeature.PLAN, UserFeature.PAYROLL);
        assertThat(principal.hasFeature(UserFeature.PLAN)).isTrue();
        assertThat(principal.hasFeature(UserFeature.SHIFTS)).isFalse();
    }
}
