package com.storeanalytics.salary.model;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

public record PayrollSourceFingerprint(
        int version,
        String salesHash,
        String shiftsHash,
        String planHash,
        String classificationHash,
        String schemeHash
) {

    public static final int CURRENT_VERSION = 1;
    private static final String SHA_256_PATTERN = "[0-9a-f]{64}";

    public PayrollSourceFingerprint {
        require(version == CURRENT_VERSION, "unsupported payroll source fingerprint version");
        salesHash = requireHash(salesHash, "salesHash");
        shiftsHash = requireHash(shiftsHash, "shiftsHash");
        planHash = requireHash(planHash, "planHash");
        classificationHash = requireHash(classificationHash, "classificationHash");
        schemeHash = requireHash(schemeHash, "schemeHash");
    }

    private static String requireHash(String value, String name) {
        String validated = requireText(value, name);
        require(validated.matches(SHA_256_PATTERN), name + " must be a lowercase SHA-256 hash");
        return validated;
    }
}
