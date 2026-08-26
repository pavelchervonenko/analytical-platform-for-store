package com.storeanalytics.product.service;

import com.storeanalytics.product.model.Product;
import com.storeanalytics.product.model.ProductConditionType;
import com.storeanalytics.product.model.ProductSourceKind;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class ProductAutoClassificationRuleEngine {

    public static final String RULE_VERSION = "livesklad-product-rules-v6";

    public Optional<ProductAutoClassificationDecision> classify(Product product) {
        return classify(product.getName(), product.getSourceKind());
    }

    Optional<ProductAutoClassificationDecision> classify(
            String productName,
            ProductSourceKind sourceKind
    ) {
        String name = normalize(productName);
        if (name.isBlank()) {
            return Optional.empty();
        }

        Optional<ProductAutoClassificationDecision> commercialService =
                classifyCommercialService(name);
        if (commercialService.isPresent()) {
            return commercialService;
        }

        Optional<ProductAutoClassificationDecision> accessory =
                classifyAccessory(name);
        if (accessory.isPresent()) {
            return accessory;
        }

        Optional<ProductAutoClassificationDecision> device = classifyDevice(name);
        if (device.isPresent()) {
            return device;
        }

        if (sourceKind == ProductSourceKind.SERVICE) {
            return decision(
                    "SETUP_SERVICE",
                    ProductConditionType.NOT_APPLICABLE,
                    "source-kind-service"
            );
        }
        return Optional.empty();
    }

    private Optional<ProductAutoClassificationDecision> classifyCommercialService(
            String name
    ) {
        if (containsAny(name, "premium", "ultimate care")) {
            return decision(
                    "PREMIUM_PROTECTION",
                    ProductConditionType.NOT_APPLICABLE,
                    "premium-protection"
            );
        }
        if (containsAny(
                name,
                "check discount",
                "check diskount",
                "check+",
                "check +",
                "check++",
                "check ++",
                "elite care",
                "privilege care",
                "гаранти"
        )) {
            return decision(
                    "WARRANTY_GENERIC",
                    ProductConditionType.NOT_APPLICABLE,
                    "warranty"
            );
        }
        if (containsAny(
                name,
                "настройк",
                "активац",
                "учетн",
                "учётн",
                "перенос данных",
                "перенос контактов",
                "установк",
                "обновление программ",
                "восстановление программ",
                "сброс ",
                "чистка устройства",
                "гравировк",
                "перезагрузка устройства",
                "защитного покрытия",
                "подзаряд",
                "подпис",
                "subscription",
                "ремонт",
                "repair"
        )) {
            return decision(
                    "SETUP_SERVICE",
                    ProductConditionType.NOT_APPLICABLE,
                    "setup-service"
            );
        }
        return Optional.empty();
    }

    private Optional<ProductAutoClassificationDecision> classifyAccessory(
            String name
    ) {
        if (containsAny(name, "чехол", "чехлол", " case", "case ", "бампер")) {
            if (isIpadOrMac(name)) {
                return notApplicable("ACCESSORY_IPAD_MAC", "ipad-case");
            }
            if (isPodsOrWatch(name)) {
                return notApplicable("ACCESSORY_PODS_WATCH", "pods-watch-case");
            }
            if (isSamsung(name)) {
                return notApplicable("CASE_SAMSUNG", "samsung-case");
            }
            if (isIphone(name)) {
                return notApplicable("CASE_APPLE_IPHONE", "iphone-case");
            }
            return notApplicable("OTHER_ACCESSORY_PRODUCT", "generic-case");
        }

        if (containsAny(name, "стекл", "стекол") || isCameraProtection(name)) {
            if (isIpadOrMac(name) || name.contains("планшет")) {
                return notApplicable("ACCESSORY_IPAD_MAC", "ipad-glass");
            }
            if (isPodsOrWatch(name)) {
                return notApplicable("ACCESSORY_PODS_WATCH", "watch-glass");
            }
            boolean cameraProtection = isCameraProtection(name);
            if (isSamsung(name)) {
                return cameraProtection
                        ? notApplicable(
                                "GLASS_CAMERA_SAMSUNG",
                                "samsung-camera-protection"
                        )
                        : notApplicable("GLASS_SAMSUNG", "samsung-glass");
            }
            return cameraProtection
                    ? notApplicable(
                            "GLASS_CAMERA_IPHONE",
                            "iphone-camera-protection"
                    )
                    : notApplicable("GLASS_IPHONE", "iphone-glass");
        }

        if (containsAny(name, "пленк", "плёнк")) {
            if (isIpadOrMac(name) || name.contains("планшет")) {
                return notApplicable("ACCESSORY_IPAD_MAC", "ipad-film");
            }
            return notApplicable("FILM_PHONE", "phone-film");
        }

        if (containsAny(
                name,
                "кабель",
                "заряд",
                "сзу",
                "cзу",
                "азу",
                "бзу",
                "power bank",
                "powerbank",
                "пауэрбанк",
                "magsafe battery",
                "адаптер питания",
                "блок питания",
                "провод"
        ) || name.contains("аккумулятор")
                && containsAny(name, "портативн", "внешн")) {
            return notApplicable("CHARGER_CABLE", "charger-cable");
        }

        if (containsAny(name, "наконечник")
                && containsAny(name, "apple pencil", "pencil")) {
            return notApplicable("ACCESSORY_IPAD_MAC", "ipad-mac-accessory");
        }

        if (containsAny(
                name,
                "док-станц",
                "док станц",
                "charging station",
                "chargingstation"
        )) {
            return notApplicable("OTHER_ACCESSORY_PRODUCT", "dock-station-accessory");
        }

        if (containsAny(name, "клавиатур")
                && !containsAny(name, "magic keyboard")) {
            return notApplicable("ACCESSORY_IPAD_MAC", "ipad-mac-accessory");
        }

        if (containsAny(name, "ремешок", "браслет для", "airtag", "брелок")) {
            return notApplicable("ACCESSORY_PODS_WATCH", "pods-watch-accessory");
        }

        if (isIpadMacPeripheralDevice(name)) {
            return Optional.empty();
        }

        if (containsAny(
                name,
                "кардхолдер",
                "taggy",
                "держатель",
                "переходник",
                "адаптер",
                "монопод",
                "стилус",
                "сумка",
                "рюкзак"
        )) {
            return notApplicable("OTHER_ACCESSORY_PRODUCT", "other-accessory");
        }
        return Optional.empty();
    }

    private Optional<ProductAutoClassificationDecision> classifyDevice(String name) {
        ProductConditionType condition = condition(name);
        if (containsAny(name, "яндекс станци", "yandex station")) {
            return decision(
                    "PODS_WATCH_OTHER_DEVICE",
                    condition,
                    "yandex-station"
            );
        }
        if (isIpadMacPeripheralDevice(name)) {
            return decision("IPAD_MAC", condition, "ipad-mac-peripheral-device");
        }
        if (isIphone(name)) {
            return decision(
                    condition == ProductConditionType.USED
                            ? "IPHONE_USED"
                            : "IPHONE_NEW_ASIS",
                    condition,
                    condition == ProductConditionType.USED
                            ? "iphone-used"
                            : "iphone-new-asis"
            );
        }
        if (isSamsung(name)) {
            return decision(
                    condition == ProductConditionType.USED
                            ? "SAMSUNG_USED"
                            : "SAMSUNG_NEW",
                    condition,
                    condition == ProductConditionType.USED
                            ? "samsung-used"
                            : "samsung-new"
            );
        }
        if (isIpadOrMac(name)) {
            return decision("IPAD_MAC", condition, "ipad-mac-device");
        }
        if (isPodsOrWatch(name) || containsAny(
                name,
                "наушник",
                "колонк",
                "marshall",
                "harman kardon",
                "jbl ",
                "dyson",
                "whoop",
                "fitbit",
                "ray ban",
                "playstation",
                "sony ps"
        )) {
            return decision(
                    "PODS_WATCH_OTHER_DEVICE",
                    condition,
                    "pods-watch-other-device"
            );
        }
        return Optional.empty();
    }

    private ProductConditionType condition(String name) {
        if (containsAny(name, "б/у", "б у", "бу ", " used")) {
            return ProductConditionType.USED;
        }
        if (containsAny(name, "asis", "as is")) {
            return ProductConditionType.ASIS;
        }
        return ProductConditionType.NEW;
    }

    private boolean isIphone(String name) {
        return containsAny(name, "iphone", "айфон")
                || name.matches(
                        ".*\\bphone\\s+1[1-9]\\s+(?:pro(?:\\s+max)?|plus|mini|e)\\b.*"
                );
    }

    private boolean isSamsung(String name) {
        return containsAny(name, "samsung", "galaxy", "самсунг")
                || name.matches(".*\\bs2[0-9](?: ultra| plus| fe)?\\b.*")
                || name.matches(".*\\ba5[0-9]\\b.*");
    }

    private boolean isCameraProtection(String name) {
        return containsAny(
                name,
                "защита камер",
                "защита kамер",
                "защита kaмер",
                "защита линз",
                "защитные линз",
                "линзы на камер",
                "camera lens"
        );
    }

    private boolean isIpadOrMac(String name) {
        return containsAny(name, "ipad", "macbook", "imac", "mac mini", "макбук");
    }

    private boolean isIpadMacPeripheralDevice(String name) {
        return containsAny(
                name,
                "apple pencil",
                "magic mouse",
                "magic keyboard"
        );
    }

    private boolean isPodsOrWatch(String name) {
        return containsAny(
                name,
                "airpods",
                "apple watch",
                "iwatch",
                "galaxy buds"
        );
    }

    private Optional<ProductAutoClassificationDecision> notApplicable(
            String categoryCode,
            String ruleId
    ) {
        return decision(categoryCode, ProductConditionType.NOT_APPLICABLE, ruleId);
    }

    private Optional<ProductAutoClassificationDecision> decision(
            String categoryCode,
            ProductConditionType conditionType,
            String ruleId
    ) {
        return Optional.of(new ProductAutoClassificationDecision(
                categoryCode,
                conditionType,
                ruleId
        ));
    }

    private boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е');
        return normalized.replaceAll("[^a-zа-я0-9+\\-/]+", " ").trim();
    }
}
