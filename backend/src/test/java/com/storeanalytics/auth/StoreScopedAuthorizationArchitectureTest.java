package com.storeanalytics.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
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

    private static final String STORE_ACCESS_CHECK =
            "@storeAccessAuthorization.canAccess(#storeId, authentication)";

    @Test
    void everyStoreScopedControllerMethodDeclaresAuthorization() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));

        List<String> missingOrInvalidAuthorization = new ArrayList<>();
        for (var component : scanner.findCandidateComponents("com.storeanalytics")) {
            Class<?> controller = Class.forName(component.getBeanClassName());
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(
                    controller,
                    RequestMapping.class
            );
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping methodMapping = AnnotatedElementUtils.findMergedAnnotation(
                        method,
                        RequestMapping.class
                );
                if (methodMapping == null || effectivePaths(classMapping, methodMapping).stream()
                        .noneMatch(this::isStoreScopedPath)) {
                    continue;
                }
                PreAuthorize authorization = AnnotatedElementUtils.findMergedAnnotation(
                        method,
                        PreAuthorize.class
                );
                if (authorization == null) {
                    authorization = AnnotatedElementUtils.findMergedAnnotation(
                            controller,
                            PreAuthorize.class
                    );
                }
                if (authorization == null || !authorization.value().contains(STORE_ACCESS_CHECK)) {
                    missingOrInvalidAuthorization.add(
                            controller.getSimpleName() + "#" + method.getName()
                    );
                }
            }
        }

        assertThat(missingOrInvalidAuthorization)
                .as("store-scoped endpoints without an explicit store access check")
                .isEmpty();
    }

    private List<String> effectivePaths(
            RequestMapping classMapping,
            RequestMapping methodMapping
    ) {
        List<String> classPaths = paths(classMapping);
        List<String> methodPaths = paths(methodMapping);
        return classPaths.stream()
                .flatMap(classPath -> methodPaths.stream()
                        .map(methodPath -> join(classPath, methodPath)))
                .toList();
    }

    private List<String> paths(RequestMapping mapping) {
        if (mapping == null) {
            return List.of("");
        }
        String[] paths = mapping.path().length == 0 ? mapping.value() : mapping.path();
        return paths.length == 0 ? List.of("") : Arrays.asList(paths);
    }

    private String join(String prefix, String suffix) {
        return (prefix + "/" + suffix).replaceAll("/{2,}", "/");
    }

    private boolean isStoreScopedPath(String path) {
        return path.startsWith("/api/stores/") && path.contains("{storeId}");
    }
}
