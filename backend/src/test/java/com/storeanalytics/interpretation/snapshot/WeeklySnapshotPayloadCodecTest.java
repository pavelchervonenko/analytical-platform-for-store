package com.storeanalytics.interpretation.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Facts;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Manifest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WeeklySnapshotPayloadCodecTest {

    private final WeeklySnapshotPayloadCodec codec = new WeeklySnapshotPayloadCodec();

    @Test
    void membershipIdentityParticipatesInTheHashButNotInProviderPayload() {
        WeeklySnapshotPayload payload = new WeeklySnapshotPayload(
                1,
                new Manifest(List.of("E01"), List.of(), List.of(), List.of(),
                        List.of(), List.of()),
                new Facts(List.of(), List.of(), List.of(), List.of())
        );
        var first = new SnapshotEmployeeMembership(
                UUID.fromString("00000000-0000-4000-8000-000000000001"),
                "E01",
                "First Name"
        );
        var second = new SnapshotEmployeeMembership(
                UUID.fromString("00000000-0000-4000-8000-000000000002"),
                "E01",
                "Second Name"
        );

        assertThat(codec.hash(payload, List.of(first)))
                .isNotEqualTo(codec.hash(payload, List.of(second)));
        assertThat(codec.serialize(payload))
                .doesNotContain(first.employeeId().toString(), first.displayNameSnapshot());
    }
}
