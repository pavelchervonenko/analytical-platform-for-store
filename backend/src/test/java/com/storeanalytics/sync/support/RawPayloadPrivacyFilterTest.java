package com.storeanalytics.sync.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RawPayloadPrivacyFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RawPayloadPrivacyFilter filter = new RawPayloadPrivacyFilter(
            objectMapper
    );

    @Test
    void keepsDocumentEvidenceAndDropsUnknownFieldsAtEveryDepth() throws Exception {
        JsonNode source = objectMapper.readTree("""
                {
                  "list": {
                    "id": "sale-1",
                    "number": "S-1",
                    "date": "2026-07-01T10:00:00Z",
                    "type": "SALE",
                    "summ": {
                      "price": 120,
                      "soldPrice": 100,
                      "purchasePrice": 60,
                      "internalMargin": 40
                    },
                    "accessToken": "must-not-be-retained"
                  },
                  "detail": {
                    "id": "sale-1",
                    "customer": {
                      "id": "employee-1",
                      "name": "Employee",
                      "phone": "+7-000-000"
                    },
                    "shop": {"id": "store-1", "ownerEmail": "owner@example.com"},
                    "cash": {"money": 100, "bank": 0, "invoice": 0},
                    "positions": [{
                      "positionId": "position-1",
                      "nomenclatureId": "product-1",
                      "name": "Phone",
                      "isWork": false,
                      "count": 1,
                      "price": 120,
                      "soldPrice": 100,
                      "purchasePriceSumm": 60,
                      "supplierSecret": "must-not-be-retained"
                    }],
                    "metadata": {"password": "must-not-be-retained"}
                  },
                  "debug": true
                }
                """);

        JsonNode minimized = filter.minimize(
                RawPayloadProfile.SALE_DOCUMENT,
                source
        );

        assertThat(minimized.at("/list/summ/soldPrice").decimalValue())
                .isEqualByComparingTo("100");
        assertThat(minimized.at("/detail/customer/name").stringValue())
                .isEqualTo("Employee");
        assertThat(minimized.at("/detail/positions/0/purchasePriceSumm")
                .decimalValue()).isEqualByComparingTo("60");
        assertThat(minimized.toString())
                .doesNotContain(
                        "accessToken",
                        "ownerEmail",
                        "phone",
                        "supplierSecret",
                        "password",
                        "metadata",
                        "debug",
                        "must-not-be-retained"
                );
    }

    @Test
    void keepsReturnRelationsButDropsUnexpectedPersonalData() throws Exception {
        JsonNode source = objectMapper.readTree("""
                {
                  "cashTransactions": [{
                    "id": "cash-1",
                    "date": "2026-07-01T10:00:00Z",
                    "money": 100,
                    "customer": {"id": "employee-1", "email": "private@example.com"},
                    "cashRegister": {"id": "register-1"},
                    "cashItem": {"id": "item-1", "type": "RETURN", "isIncome": false}
                  }],
                  "detail": {
                    "id": "return-1",
                    "parentDocument": {"id": "sale-1", "token": "secret"},
                    "positions": []
                  }
                }
                """);

        JsonNode minimized = filter.minimize(
                RawPayloadProfile.RETURN_DOCUMENT,
                source
        );

        assertThat(minimized.at("/cashTransactions/0/customer/id").stringValue())
                .isEqualTo("employee-1");
        assertThat(minimized.at("/detail/parentDocument/id").stringValue())
                .isEqualTo("sale-1");
        assertThat(minimized.toString())
                .doesNotContain("email", "private@example.com", "token", "secret");
    }

    @ParameterizedTest
    @EnumSource(RawPayloadProfile.class)
    void everyPersistenceProfileHasAnExplicitRootShape(RawPayloadProfile profile) {
        assertThat(filter.minimize(profile, objectMapper.createObjectNode()).isObject())
                .isTrue();
    }

    @Test
    void rejectsAnUnexpectedRetainedFieldShapeWithoutEchoingItsValue() {
        JsonNode source = objectMapper.createObjectNode().set(
                "name",
                objectMapper.createObjectNode().put("secret", "must-not-appear")
        );

        assertThatThrownBy(() -> filter.minimize(RawPayloadProfile.EMPLOYEE, source))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("$.name must be scalar")
                .hasMessageNotContaining("must-not-appear");
    }
}
