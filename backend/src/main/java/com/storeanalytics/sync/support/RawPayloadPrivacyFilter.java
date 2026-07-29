package com.storeanalytics.sync.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Projects vendor responses onto the fields used by normalization and financial evidence.
 * Unknown fields fail closed by being omitted before hashing and persistence.
 */
@Component
public class RawPayloadPrivacyFilter {

    private static final Shape VALUE = new Shape(Map.of(), null, true);
    private static final Shape ID = object("id", VALUE);
    private static final Shape AMOUNTS = object(
            "price", VALUE,
            "soldPrice", VALUE,
            "purchasePrice", VALUE
    );
    private static final Shape CASH = object(
            "money", VALUE,
            "bank", VALUE,
            "invoice", VALUE
    );
    private static final Shape SALE_POSITION = object(
            "positionId", VALUE,
            "nomenclatureId", VALUE,
            "code", VALUE,
            "article", VALUE,
            "name", VALUE,
            "isWork", VALUE,
            "count", VALUE,
            "price", VALUE,
            "soldPrice", VALUE,
            "purchasePriceSumm", VALUE
    );
    private static final Shape RETURN_POSITION = object(
            "positionId", VALUE,
            "salePositionId", VALUE,
            "nomenclatureId", VALUE,
            "code", VALUE,
            "article", VALUE,
            "name", VALUE,
            "isWork", VALUE,
            "count", VALUE,
            "price", VALUE,
            "soldPrice", VALUE,
            "purchasePriceSumm", VALUE
    );
    private static final Shape SALE_SUMMARY = object(
            "id", VALUE,
            "number", VALUE,
            "date", VALUE,
            "type", VALUE,
            "summ", AMOUNTS
    );
    private static final Shape SALE_DETAIL = object(
            "id", VALUE,
            "number", VALUE,
            "date", VALUE,
            "dateChange", VALUE,
            "type", VALUE,
            "customer", object("id", VALUE, "name", VALUE),
            "shop", ID,
            "cash", CASH,
            "positions", array(SALE_POSITION)
    );
    private static final Shape CASH_ITEM = object(
            "id", VALUE,
            "name", VALUE,
            "type", VALUE,
            "isIncome", VALUE,
            "isBalance", VALUE
    );
    private static final Shape CASH_TRANSACTION_ITEM = object(
            "id", VALUE,
            "type", VALUE,
            "isIncome", VALUE
    );
    private static final Shape CASH_TRANSACTION = object(
            "id", VALUE,
            "date", VALUE,
            "dateChange", VALUE,
            "type", VALUE,
            "shopId", VALUE,
            "isBalance", VALUE,
            "isBankTransfer", VALUE,
            "money", VALUE,
            "customer", ID,
            "worker", ID,
            "cashRegister", ID,
            "cashItem", CASH_TRANSACTION_ITEM,
            "document", ID
    );
    private static final Shape RETURN_DETAIL = object(
            "id", VALUE,
            "number", VALUE,
            "date", VALUE,
            "dateChange", VALUE,
            "type", VALUE,
            "customer", ID,
            "shop", ID,
            "parentDocument", ID,
            "cash", CASH,
            "positions", array(RETURN_POSITION)
    );
    private static final Map<RawPayloadProfile, Shape> PROFILES = Map.of(
            RawPayloadProfile.STORE,
            object(
                    "id", VALUE,
                    "name", VALUE,
                    "address", VALUE,
                    "color", VALUE
            ),
            RawPayloadProfile.EMPLOYEE,
            object("id", VALUE, "name", VALUE),
            RawPayloadProfile.SALE_DOCUMENT,
            object("list", SALE_SUMMARY, "detail", SALE_DETAIL),
            RawPayloadProfile.CASH_ITEM_DICTIONARY,
            object("data", array(CASH_ITEM)),
            RawPayloadProfile.CASH_REGISTER,
            object("id", VALUE, "name", VALUE, "shopId", VALUE),
            RawPayloadProfile.RETURN_DOCUMENT,
            object(
                    "cashTransactions", array(CASH_TRANSACTION),
                    "detail", RETURN_DETAIL
            )
    );

    private final ObjectMapper objectMapper;

    public RawPayloadPrivacyFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode minimize(RawPayloadProfile profile, JsonNode payload) {
        RawPayloadProfile requiredProfile = Objects.requireNonNull(
                profile,
                "profile"
        );
        JsonNode requiredPayload = Objects.requireNonNull(payload, "payload");
        return copy(requiredPayload, PROFILES.get(requiredProfile), "$", true);
    }

    private JsonNode copy(JsonNode source, Shape shape, String path, boolean root) {
        if (source.isNull()) {
            if (root) {
                throw shapeMismatch(path, "object", source);
            }
            return source.deepCopy();
        }
        if (shape.value()) {
            if (!source.isValueNode()) {
                throw shapeMismatch(path, "scalar", source);
            }
            return source.deepCopy();
        }
        if (shape.element() != null) {
            if (!source.isArray()) {
                throw shapeMismatch(path, "array", source);
            }
            ArrayNode result = objectMapper.createArrayNode();
            int index = 0;
            for (JsonNode element : source) {
                result.add(copy(element, shape.element(), path + "[" + index + "]", false));
                index++;
            }
            return result;
        }
        if (!source.isObject()) {
            throw shapeMismatch(path, "object", source);
        }
        ObjectNode result = objectMapper.createObjectNode();
        shape.fields().forEach((name, childShape) -> {
            JsonNode child = source.get(name);
            if (child != null) {
                result.set(name, copy(child, childShape, path + "." + name, false));
            }
        });
        return result;
    }

    private IllegalArgumentException shapeMismatch(
            String path,
            String expected,
            JsonNode actual
    ) {
        return new IllegalArgumentException(
                "Source payload " + path + " must be " + expected
                        + ", but was " + actual.getNodeType()
        );
    }

    private static Shape array(Shape element) {
        return new Shape(Map.of(), element, false);
    }

    private static Shape object(Object... entries) {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("shape entries must contain name/value pairs");
        }
        Map<String, Shape> fields = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            fields.put((String) entries[index], (Shape) entries[index + 1]);
        }
        return new Shape(Map.copyOf(fields), null, false);
    }

    private record Shape(Map<String, Shape> fields, Shape element, boolean value) {
    }
}
