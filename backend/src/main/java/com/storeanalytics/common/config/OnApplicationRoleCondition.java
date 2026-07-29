package com.storeanalytics.common.config;

import java.util.Arrays;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

final class OnApplicationRoleCondition extends SpringBootCondition {

    private static final String ROLE_PROPERTY = "app.runtime.role";

    @Override
    public ConditionOutcome getMatchOutcome(
            ConditionContext context,
            AnnotatedTypeMetadata metadata
    ) {
        ApplicationRole configuredRole = Binder.get(context.getEnvironment())
                .bind(ROLE_PROPERTY, ApplicationRole.class)
                .orElse(ApplicationRole.COMBINED);
        Set<ApplicationRole> expectedRoles = Set.copyOf(Arrays.asList(
                (ApplicationRole[]) metadata.getAnnotationAttributes(
                        ConditionalOnApplicationRole.class.getName()
                ).get("value")
        ));
        if (expectedRoles.contains(configuredRole)) {
            return ConditionOutcome.match(
                    "Application role " + configuredRole + " is enabled"
            );
        }
        return ConditionOutcome.noMatch(
                "Application role " + configuredRole + " is not enabled"
        );
    }
}
