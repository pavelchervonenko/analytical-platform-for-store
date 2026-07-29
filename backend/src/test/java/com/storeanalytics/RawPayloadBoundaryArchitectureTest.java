package com.storeanalytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class RawPayloadBoundaryArchitectureTest {

    private static final List<String> FORBIDDEN_TERMS = List.of(
            "rawpayload",
            "raw_payload",
            "raw_record_versions",
            "rawrecordversion",
            "preparedrawpayload"
    );

    @Test
    void rawPayloadInternalsStayOutOfApiAndUiSources() throws IOException {
        Path repositoryRoot = repositoryRoot();
        List<String> violations = new ArrayList<>();
        inspect(
                repositoryRoot.resolve("backend/src/main/java"),
                path -> path.toString().replace('\\', '/').contains("/web/"),
                violations
        );
        inspect(
                repositoryRoot.resolve("frontend/src"),
                path -> {
                    String name = path.getFileName().toString();
                    return name.endsWith(".ts") || name.endsWith(".tsx");
                },
                violations
        );

        assertThat(violations)
                .as("raw persistence details exposed by an API or UI source")
                .isEmpty();
    }

    private void inspect(
            Path root,
            java.util.function.Predicate<Path> included,
            List<String> violations
    ) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(included)
                    .toList()) {
                String source = Files.readString(path).toLowerCase(Locale.ROOT);
                FORBIDDEN_TERMS.stream()
                        .filter(source::contains)
                        .forEach(term -> violations.add(
                                root.relativize(path) + " contains " + term
                        ));
            }
        }
    }

    private Path repositoryRoot() {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(workingDirectory.resolve("backend/src/main/java"))) {
            return workingDirectory;
        }
        Path parent = workingDirectory.getParent();
        if (parent != null
                && Files.isDirectory(parent.resolve("backend/src/main/java"))) {
            return parent;
        }
        throw new IllegalStateException("Cannot locate repository root");
    }
}
