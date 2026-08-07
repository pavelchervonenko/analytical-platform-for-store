package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class SnapshotEmployeePseudonymizer {

    List<SnapshotEmployeeMembership> assign(List<EmployeeIdentity> identities) {
        Map<UUID, String> names = new LinkedHashMap<>();
        for (EmployeeIdentity identity : requireNonNull(identities, "identities")) {
            String previous = names.putIfAbsent(identity.employeeId(), identity.displayName());
            if (previous != null && !previous.equals(identity.displayName())) {
                throw new IllegalArgumentException(
                        "Conflicting display names for employee " + identity.employeeId()
                );
            }
        }
        if (names.size() > WeeklySnapshotPolicyV1.MAX_EMPLOYEES) {
            throw new IllegalArgumentException(
                    "Weekly interpretation supports at most "
                            + WeeklySnapshotPolicyV1.MAX_EMPLOYEES + " employees"
            );
        }
        List<Map.Entry<UUID, String>> sorted = names.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)))
                .toList();
        return java.util.stream.IntStream.range(0, sorted.size())
                .mapToObj(index -> new SnapshotEmployeeMembership(
                        sorted.get(index).getKey(),
                        "E%02d".formatted(index + 1),
                        sorted.get(index).getValue()
                ))
                .toList();
    }

    record EmployeeIdentity(UUID employeeId, String displayName) {

        EmployeeIdentity {
            requireNonNull(employeeId, "employeeId");
            requireText(displayName, "displayName");
        }
    }
}
