package com.storeanalytics.common.security;

public record ClientAddress(
        String canonicalAddress,
        String throttleKey
) {

    public ClientAddress {
        if (canonicalAddress == null || canonicalAddress.isBlank()) {
            throw new IllegalArgumentException(
                    "Canonical client address is required"
            );
        }
        if (throttleKey == null || throttleKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Client address throttle key is required"
            );
        }
    }
}
