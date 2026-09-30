package com.storeanalytics.product.service;

import java.nio.charset.StandardCharsets;
import java.util.Collection;

/** Dependency-free read-only parity probe for the shared Java/Python category contract. */
public final class CatalogRegistryAuditProbe {

    private CatalogRegistryAuditProbe() {
    }

    public static void main(String[] args) throws Exception {
        CatalogCategoryRegistry registry;
        if (args.length == 0) {
            registry = CatalogCategoryRegistry.standard();
        } else if (args.length == 1 && args[0].equals("--stdin")) {
            registry = CatalogCategoryRegistry.parse(
                    new String(System.in.readAllBytes(), StandardCharsets.UTF_8));
        } else {
            throw new IllegalArgumentException("Expected no arguments or --stdin");
        }
        System.out.println(CatalogCategoryRegistry.VERSION + "\t" + registry.sha256());
        for (CatalogCategoryRegistry.Definition entry : registry.definitions()) {
            System.out.println(String.join("\t", entry.code(), entry.name(),
                    entry.categoryKind().name(), entry.deviceFamily().name(),
                    Boolean.toString(entry.countsAsPhone()),
                    Boolean.toString(entry.countsAsDevice()),
                    Boolean.toString(entry.countsAsAdditionalRevenue()),
                    entry.scope().name(), entry.deviceType(), entry.deviceBrandMatch(),
                    sorted(entry.proposalKeys()), sorted(entry.confirmedFunctions()),
                    sorted(entry.confirmedConditions())));
        }
    }

    private static String sorted(Collection<?> values) {
        return String.join("|", values.stream().map(Object::toString).sorted().toList());
    }
}
