package com.storeanalytics.common.web;

import java.util.Objects;

public final class StrongEtag {

    private StrongEtag() {
    }

    public static String of(String namespace, Object... components) {
        StringBuilder value = new StringBuilder(namespace);
        for (Object component : components) {
            value.append(':').append(Objects.requireNonNull(component, "component"));
        }
        return '"' + value.toString() + '"';
    }
}
