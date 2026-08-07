package com.storeanalytics.interpretation.snapshot;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
final class WeeklySnapshotPayloadCodec {

    private final ObjectMapper canonicalMapper;
    private final ObjectWriter canonicalWriter;

    WeeklySnapshotPayloadCodec() {
        canonicalMapper = JsonMapper.builder()
                .findAndAddModules()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
        canonicalWriter = canonicalMapper.writer();
    }

    String serialize(WeeklySnapshotPayload payload) {
        try {
            return canonicalWriter.writeValueAsString(payload);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Weekly snapshot payload could not be encoded", exception);
        }
    }

    WeeklySnapshotPayload deserialize(String payload) {
        try {
            return canonicalMapper.readValue(payload, WeeklySnapshotPayload.class);
        } catch (JacksonException exception) {
            throw new WeeklySnapshotIntegrityException(
                    "Weekly snapshot payload is not readable",
                    exception
            );
        }
    }

    String hash(
            WeeklySnapshotPayload payload,
            java.util.List<SnapshotEmployeeMembership> memberships
    ) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalWriter.writeValueAsBytes(
                            new SnapshotHashMaterial(payload, memberships)
                    )));
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Weekly snapshot hash could not be created",
                    exception
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record SnapshotHashMaterial(
            WeeklySnapshotPayload payload,
            java.util.List<SnapshotEmployeeMembership> memberships
    ) {
    }
}
