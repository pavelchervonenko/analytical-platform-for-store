package com.storeanalytics.product.service;

import com.storeanalytics.product.model.ProductSourceKind;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Read-only harness: execute the real source rules, without a database or Spring context. */
public class CatalogAuditProbe {
    public static void main(String[] args) throws Exception {
        var engine = new ProductAutoClassificationRuleEngine();
        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = reader.readLine()) != null;) {
            var fields = line.split("\t", -1);
            var name = new String(Base64.getDecoder().decode(fields[1]), StandardCharsets.UTF_8);
            var decision = engine.classify(name, ProductSourceKind.valueOf(fields[2]));
            System.out.println(fields[0] + "\t" + decision.map(d -> d.categoryCode() + "\t"
                    + d.conditionType() + "\t" + d.ruleId()).orElse("UNMAPPED\tUNKNOWN\tno-rule"));
        }
    }
}
