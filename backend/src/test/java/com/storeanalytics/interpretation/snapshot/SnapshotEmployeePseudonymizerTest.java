package com.storeanalytics.interpretation.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SnapshotEmployeePseudonymizerTest {

    private final SnapshotEmployeePseudonymizer pseudonymizer =
            new SnapshotEmployeePseudonymizer();

    @Test
    void assignsStableReferencesByEmployeeIdRatherThanInputOrder() {
        var first = identity("00000000-0000-4000-8000-000000000002", "Second Person");
        var second = identity("00000000-0000-4000-8000-000000000001", "First Person");

        List<SnapshotEmployeeMembership> memberships = pseudonymizer.assign(
                List.of(first, second)
        );

        assertThat(memberships)
                .extracting(SnapshotEmployeeMembership::employeeRef)
                .containsExactly("E01", "E02");
        assertThat(memberships)
                .extracting(SnapshotEmployeeMembership::employeeId)
                .containsExactly(second.employeeId(), first.employeeId());
    }

    @Test
    void refusesToSilentlyTruncateMoreEmployeesThanTheContractAllows() {
        List<SnapshotEmployeePseudonymizer.EmployeeIdentity> identities = IntStream
                .rangeClosed(1, 11)
                .mapToObj(index -> identity(
                        "00000000-0000-4000-8000-%012d".formatted(index),
                        "Employee " + index
                ))
                .toList();

        assertThatThrownBy(() -> pseudonymizer.assign(identities))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 10 employees");
    }

    private SnapshotEmployeePseudonymizer.EmployeeIdentity identity(
            String id,
            String name
    ) {
        return new SnapshotEmployeePseudonymizer.EmployeeIdentity(UUID.fromString(id), name);
    }
}
