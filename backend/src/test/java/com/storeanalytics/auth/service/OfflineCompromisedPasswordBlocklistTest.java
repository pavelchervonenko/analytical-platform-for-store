package com.storeanalytics.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OfflineCompromisedPasswordBlocklistTest {

    private final OfflineCompromisedPasswordBlocklist blocklist =
            new OfflineCompromisedPasswordBlocklist();

    @Test
    void loadsExpectedVersionedDataset() {
        assertThat(blocklist.size())
                .isEqualTo(OfflineCompromisedPasswordBlocklist.EXPECTED_BLOCKED_DIGESTS);
    }

    @Test
    void checksCommonPasswordsOfflineAndCaseInsensitively() {
        assertThat(blocklist.contains("password1234")).isTrue();
        assertThat(blocklist.contains("PASSWORD1234")).isTrue();
        assertThat(blocklist.contains("X7!vQ2#nL9@p")).isFalse();
    }
}
