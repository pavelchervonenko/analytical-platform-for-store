package com.storeanalytics.interpretation.validation;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

public final class LlmJsonSchemaValidator {

    private final Schema schema;

    public LlmJsonSchemaValidator(String classpathResource) {
        Objects.requireNonNull(classpathResource, "classpathResource");
        this.schema = loadSchema(classpathResource);
    }

    public List<StructuralValidationViolation> validate(String json) {
        Objects.requireNonNull(json, "json");
        return schema.validate(json, InputFormat.JSON).stream()
                .map(LlmJsonSchemaValidator::toViolation)
                .toList();
    }

    private static Schema loadSchema(String classpathResource) {
        ClassLoader classLoader = LlmJsonSchemaValidator.class.getClassLoader();
        try (InputStream input = classLoader.getResourceAsStream(classpathResource)) {
            if (input == null) {
                throw new IllegalStateException(
                        "LLM contract resource is missing: " + classpathResource
                );
            }
            SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                    SpecificationVersion.DRAFT_2020_12
            );
            return registry.getSchema(input, InputFormat.JSON);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Cannot close LLM contract resource: " + classpathResource,
                    exception
            );
        }
    }

    private static StructuralValidationViolation toViolation(Error error) {
        return new StructuralValidationViolation(
                error.getKeyword(),
                error.getInstanceLocation().toString()
        );
    }
}
