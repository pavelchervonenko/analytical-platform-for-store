package com.storeanalytics.product.service;

import com.storeanalytics.product.model.ProductSourceKind;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.stream.Collectors;

/** Isolated replay harness: no Spring, persistence, network or assignment side effects. */
public class CatalogDetailAuditProbe {
    public static void main(String[] args) throws Exception {
        var proposer = new CatalogDeviceDetailProposer();
        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = reader.readLine()) != null;) {
            var input = line.split("\t", -1);
            var proposal = proposer.propose(new CatalogDeviceDetailProposer.Observation(
                    decode(input[1]), ProductSourceKind.valueOf(input[2]), decode(input[3])));
            System.out.println(String.join("\t", input[0], proposal.policyVersion(),
                    proposal.observationFingerprint(), proposal.status().name(),
                    proposal.deviceTypes().stream().map(Enum::name).sorted().collect(Collectors.joining(",")),
                    proposal.brandCandidates().stream().sorted().collect(Collectors.joining(",")),
                    proposal.condition().name(),
                    proposal.candidateCategoryCode() == null ? "" : proposal.candidateCategoryCode(),
                    proposal.reasons().stream().map(Enum::name).collect(Collectors.joining(",")),
                    proposal.evidence().stream().map(e -> e.field() + "=" + e.value() + ":" + e.source())
                            .sorted().collect(Collectors.joining(";"))));
        }
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
