package com.storeanalytics.sync.service;

import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.integration.livesklad.dto.LiveSkladStorePayload;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.store.repository.StoreRepository;
import com.storeanalytics.sync.model.RawRecordVersion;
import com.storeanalytics.sync.model.SourceSystem;
import com.storeanalytics.sync.model.SyncRun;
import com.storeanalytics.sync.repository.RawRecordVersionRepository;
import com.storeanalytics.sync.repository.SyncRunRepository;
import com.storeanalytics.sync.support.JsonPayloadHasher;
import com.storeanalytics.sync.support.PreparedRawPayload;
import com.storeanalytics.sync.support.RawPayloadProfile;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class StoreSyncPersistence {

    private static final String STORE_ENTITY_TYPE = "STORE";

    private final StoreRepository storeRepository;
    private final SyncRunRepository syncRunRepository;
    private final RawRecordVersionRepository rawRecordRepository;
    private final JsonPayloadHasher payloadHasher;
    private final Clock clock;

    public StoreSyncPersistence(
            StoreRepository storeRepository,
            SyncRunRepository syncRunRepository,
            RawRecordVersionRepository rawRecordRepository,
            JsonPayloadHasher payloadHasher,
            Clock clock
    ) {
        this.storeRepository = storeRepository;
        this.syncRunRepository = syncRunRepository;
        this.rawRecordRepository = rawRecordRepository;
        this.payloadHasher = payloadHasher;
        this.clock = clock;
    }

    @Transactional
    public StoreWriteResult synchronize(UUID syncRunId, LiveSkladStorePayload source) {
        validate(source);
        Instant now = clock.instant();
        SyncRun syncRun = syncRunRepository.getReferenceById(syncRunId);
        PreparedRawPayload preparedPayload = payloadHasher.prepare(RawPayloadProfile.STORE, source.rawPayload());
        String hash = preparedPayload.sha256();
        Optional<RawRecordVersion> existingVersion = rawRecordRepository.findCompanyRecordVersion(
                syncRun.getConnection().getId(),
                SourceSystem.LIVESKLAD.name(),
                STORE_ENTITY_TYPE,
                source.externalId(),
                hash
        );
        RawRecordVersion rawVersion;
        if (existingVersion.isPresent()) {
            rawVersion = existingVersion.get();
            rawVersion.markSeenWithRetainedPayload(
                    syncRun,
                    now,
                    preparedPayload.json()
            );
            if (rawVersion.isNormalized()) {
                return StoreWriteResult.SKIPPED;
            }
        } else {
            rawVersion = RawRecordVersion.pendingStore(
                    source.externalId(),
                    preparedPayload.json(),
                    hash,
                    syncRun,
                    now
            );
            rawRecordRepository.save(rawVersion);
        }

        StoreWriteResult writeResult = normalizeStore(syncRun.getConnection(), source);
        rawVersion.markNormalized(now);
        return writeResult;
    }

    private StoreWriteResult normalizeStore(
            IntegrationConnection connection,
            LiveSkladStorePayload source
    ) {
        Optional<Store> existingStore = storeRepository.findByConnectionIdAndExternalId(
                connection.getId(),
                source.externalId()
        );
        if (existingStore.isEmpty()) {
            storeRepository.save(Store.fromLiveSklad(
                    connection,
                    source.externalId(),
                    source.name(),
                    source.address()
            ));
            return StoreWriteResult.CREATED;
        }
        boolean changed = existingStore.get().updateFromLiveSklad(source.name(), source.address());
        return changed ? StoreWriteResult.UPDATED : StoreWriteResult.SKIPPED;
    }

    private void validate(LiveSkladStorePayload source) {
        if (source == null
                || !StringUtils.hasText(source.externalId())
                || !StringUtils.hasText(source.name())
                || source.rawPayload() == null) {
            throw new IllegalArgumentException("LiveSklad store payload is incomplete");
        }
    }
}
