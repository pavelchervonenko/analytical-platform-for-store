package com.storeanalytics.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class StoreScopedAuthorizationArchitectureTest {

    @Test
    void everyStoreScopedControllerMethodDeclaresAuthorization() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));

        List<String> missingAuthorization = new ArrayList<>();
        for (var component : scanner.findCandidateComponents("com.storeanalytics")) {
            Class<?> controller = Class.forName(component.getBeanClassName());
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(
                    controller,
                    RequestMapping.class
            );
            if (classMapping == null || !List.of(classMapping.value()).contains("/api/stores")) {
                continue;
            }
            for (Method method : controller.getDeclaredMethods()) {
                if (AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)
                        && !AnnotatedElementUtils.hasAnnotation(method, PreAuthorize.class)
                        && !AnnotatedElementUtils.hasAnnotation(controller, PreAuthorize.class)) {
                    missingAuthorization.add(controller.getSimpleName() + "#" + method.getName());
                }
            }
        }

        assertThat(missingAuthorization)
                .as("store-scoped endpoints without @PreAuthorize")
                .isEmpty();
    }
}
