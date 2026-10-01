package com.storeanalytics.product.service;

import com.storeanalytics.product.model.Product;
import com.storeanalytics.product.model.ProductConditionType;
import com.storeanalytics.product.model.ProductSourceKind;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ProductAutoClassificationRuleEngine {

    public static final String RULE_VERSION = "livesklad-product-rules-v32";

    private static final Pattern DEVICE_SERVICE_ANNOTATION = Pattern.compile(
            "\\s+(?:-\\s*)?(?:ремонт|repair|брак по гарантии(?: лежит)?)$"
    );

    // A glass product's applicator label is not an installation service.
    private static final Pattern GLASS_AUTO_INSTALLATION = Pattern.compile(
            "\\bавтоустановк[а-я]*\\b", Pattern.UNICODE_CHARACTER_CLASS
    );

    // Accessory compatibility only; do not broaden recognition of sold phones.
    private static final Pattern SAMSUNG_ACCESSORY_MODEL = Pattern.compile(
            "\\b(?:[aа][35][0-9]|s2[0-9](?:\\s*(?:fe|ultra|plus))?)\\b",
            Pattern.UNICODE_CHARACTER_CLASS
    );

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

        Optional<ProductAutoClassificationDecision> annotatedDevice =
                classifyAnnotatedDevice(name, sourceKind);
        if (annotatedDevice.isPresent()) {
            return annotatedDevice;
        }

        Optional<ProductAutoClassificationDecision> commercialService =
                classifyCommercialService(name);
        if (commercialService.isPresent()) {
            return commercialService;
        }

        if (sourceKind == ProductSourceKind.SERVICE) {
            return decision(
                    "SETUP_SERVICE",
                    ProductConditionType.NOT_APPLICABLE,
                    "source-kind-service"
            );
        }

        // Conflicting targets are unresolved, not a reason to fall through to device rules.
        if ((isCaseProductName(name) || isProtectionProductName(name))
                && isIphone(name) && hasExplicitSamsungAccessoryTarget(name)) {
            return Optional.empty();
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

        return Optional.empty();
    }

    private Optional<ProductAutoClassificationDecision> classifyAnnotatedDevice(
            String name,
            ProductSourceKind sourceKind
    ) {
        // Narrow exception for a catalog device followed by an operational note.
        // Never reinterpret works, service names, accessories or commercial bundles.
        if (sourceKind != ProductSourceKind.PRODUCT) {
            return Optional.empty();
        }
        var annotation = DEVICE_SERVICE_ANNOTATION.matcher(name);
        if (!annotation.find()) {
            return Optional.empty();
        }
        String deviceName = name.substring(0, annotation.start()).strip();
        if (!deviceName.matches(
                "(?:apple )?(?:(?:iphone|айфон)\\s+\\d{1,2}\\b.*"
                        + "|macbook\\s+(?:air|pro)\\s+\\d{2}\\b.*)"
        ) || !deviceName.matches(
                ".*\\b(?:\\d{2,4}\\s*(?:gb|гб|tb|тб)|\\d{1,3}/\\d{2,4})\\b.*"
        ) || containsAny(deviceName, "+", "комплект", "набор")
                || classifyCommercialService(deviceName).isPresent()
                || classifyAccessory(deviceName).isPresent()) {
            return Optional.empty();
        }
        return classifyDevice(deviceName);
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
        if (name.startsWith("восстановлен") && name.contains("парол")) {
            return decision(
                    "SETUP_SERVICE",
                    ProductConditionType.NOT_APPLICABLE,
                    "password-recovery-service"
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
        ) || isInstallationService(name)) {
            return decision(
                    "SETUP_SERVICE",
                    ProductConditionType.NOT_APPLICABLE,
                    "setup-service"
            );
        }
        return Optional.empty();
    }

    private boolean isInstallationService(String name) {
        // Keep all other service markers and explicit installation in bundles.
        String installationText = isProtectionProductName(name)
                && !containsAny(name, "+", "комплект", "набор")
                ? GLASS_AUTO_INSTALLATION.matcher(name).replaceAll("")
                : name;
        return installationText.contains("установк");
    }

    private Optional<ProductAutoClassificationDecision> classifyAccessory(
            String name
    ) {
        if (isFitnessWearableAccessory(name)) {
            return notApplicable("OTHER_ACCESSORY_PRODUCT", "fitness-wearable-accessory");
        }
        if (isHeadphonePart(name)) {
            return isPodsOrWatch(name)
                    ? notApplicable("ACCESSORY_PODS_WATCH", "headphone-part")
                    : notApplicable("OTHER_ACCESSORY_PRODUCT", "headphone-part");
        }

        if (isCaseProductName(name)) {
            return classifyCase(name);
        }

        if (isProtectionProductName(name)) {
            if (isIpad(name)) {
                return notApplicable("ACCESSORY_IPAD", "ipad-glass");
            }
            if (isMac(name)) {
                return notApplicable("ACCESSORY_MAC", "mac-glass");
            }
            if (name.contains("планшет")) {
                return notApplicable("OTHER_ACCESSORY_PRODUCT", "tablet-glass");
            }
            if (isPodsOrWatch(name)) {
                return notApplicable("ACCESSORY_PODS_WATCH", "watch-glass");
            }
            boolean cameraProtection = isCameraProtection(name);
            if (isSamsungAccessoryTarget(name)) {
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
            if (name.contains("instax")) {
                return Optional.empty();
            }
            if (isIpad(name)) {
                return notApplicable("ACCESSORY_IPAD", "ipad-film");
            }
            if (isMac(name)) {
                return notApplicable("ACCESSORY_MAC", "mac-film");
            }
            if (name.contains("планшет")) {
                return notApplicable("OTHER_ACCESSORY_PRODUCT", "tablet-film");
            }
            return notApplicable("FILM_PHONE", "phone-film");
        }

        if (isPowerBank(name)) {
            return notApplicable("POWER_BANK", "power-bank");
        }

        if (isConnectivityAccessory(name)) {
            return notApplicable("OTHER_ACCESSORY_PRODUCT", "connectivity-accessory");
        }

        if (isChargingAccessory(name)) {
            return notApplicable("CHARGER_CABLE", "charger-cable");
        }

        if (containsAny(name, "наконечник")
                && containsAny(name, "apple pencil", "pencil")) {
            return notApplicable("ACCESSORY_IPAD", "ipad-accessory");
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
            if (isIpad(name)) {
                return notApplicable("ACCESSORY_IPAD", "ipad-accessory");
            }
            if (isMac(name)) {
                return notApplicable("ACCESSORY_MAC", "mac-accessory");
            }
            return notApplicable("OTHER_ACCESSORY_PRODUCT", "keyboard-target-unknown");
        }

        if (containsAny(name, "ремешок", "браслет для", "airtag", "брелок")) {
            return notApplicable("ACCESSORY_PODS_WATCH", "pods-watch-accessory");
        }

        if (name.contains("apple pencil")) {
            return notApplicable("ACCESSORY_IPAD", "apple-pencil-accessory");
        }
        if (name.contains("magic mouse")) {
            return notApplicable("ACCESSORY_MAC", "magic-mouse-accessory");
        }
        if (name.contains("magic keyboard")) {
            if (isIpad(name)) {
                return notApplicable("ACCESSORY_IPAD", "ipad-keyboard-accessory");
            }
            if (isMac(name)) {
                return notApplicable("ACCESSORY_MAC", "mac-keyboard-accessory");
            }
            return notApplicable("OTHER_ACCESSORY_PRODUCT", "keyboard-target-unknown");
        }

        if (containsAny(
                name,
                "кардхолдер",
                "картхолдер",
                "cardholder",
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

    private boolean isPowerBank(String name) {
        return containsAny(
                name,
                "power bank",
                "powerbank",
                "пауэрбанк",
                "повербанк",
                "magsafe battery pack"
        ) || name.contains("аккумулятор")
                && containsAny(name, "портативн", "внешн");
    }

    private boolean isConnectivityAccessory(String name) {
        return (containsAny(name, "hub", "хаб")
                && containsAny(name, "usb", "type-c", "port"))
                || (containsAny(name, "aux audio", "aux-аудио")
                && containsAny(name, "lightning", "3.5", "3,5"));
    }

    private boolean isChargingAccessory(String name) {
        return containsAny(
                name,
                "кабель",
                "заряд",
                "зар устройство",
                "сзу",
                "cзу",
                "азу",
                "бзу",
                "адаптер питания",
                "блок питания",
                "power adapter",
                "fast charger"
        ) || name.equals("станция 3 в 1 стоячая")
                || name.contains("провод") && !name.contains("беспровод")
                || isConnectorNamedCable(name)
                || name.matches(".*\\bcable\\s+(?:usb-c|type-c)\\b.*")
                || name.contains("блок baseus")
                && name.matches(".*\\b[0-9]{1,3}w\\b.*")
                || name.startsWith("комплект baseus ")
                && name.matches(".*\\b[0-9]{1,3}w\\b.*")
                && containsAny(name, "lightning", "type-c", "usb-c", "кабел");
    }

    private Optional<ProductAutoClassificationDecision> classifyCase(String name) {
        if (isIpad(name)) {
            return notApplicable("ACCESSORY_IPAD", "ipad-case");
        }
        if (isMac(name)) {
            return notApplicable("ACCESSORY_MAC", "mac-case");
        }
        if (isPodsOrWatch(name)) {
            return notApplicable("ACCESSORY_PODS_WATCH", "pods-watch-case");
        }
        if (isSamsungAccessoryTarget(name)) {
            return notApplicable("CASE_SAMSUNG", "samsung-case");
        }
        if (isIphone(name) || isConfirmedIphoneCaseModel(name)) {
            return notApplicable("CASE_APPLE_IPHONE", "iphone-case");
        }
        if (isOtherDeviceCase(name)) {
            return notApplicable("CASE_OTHER_DEVICE", "other-device-case");
        }
        if (containsAny(name, "ray ban", "rayban", "instax", "фотоаппарат", "dyson")) {
            return notApplicable("OTHER_ACCESSORY_PRODUCT", "non-phone-case");
        }
        if (containsAny(name, "комплект", "набор", "+стекло", "+ стекло")) {
            return notApplicable("OTHER_ACCESSORY_PRODUCT", "mixed-case-bundle");
        }
        return notApplicable("OTHER_CASE", "generic-case-target-unresolved");
    }

    private boolean isConnectorNamedCable(String name) {
        return containsAny(name, "usb-c", "usb c", "type-c", "type c")
                && name.contains("lightning");
    }

    private Optional<ProductAutoClassificationDecision> classifyDevice(String name) {
        ProductConditionType condition = condition(name);
        if (isSpeaker(name)) {
            return decision(
                    "SPEAKERS",
                    condition,
                    "speaker"
            );
        }
        if (isSmartGlasses(name)) {
            return decision(
                    "SMART_GLASSES",
                    condition,
                    "smart-glasses"
            );
        }
        if (isInstaxCamera(name)) {
            return decision(
                    "CAMERAS",
                    condition,
                    "instax-camera"
            );
        }
        if (isFitnessWearable(name)) {
            return decision(
                    "FITNESS_WEARABLE",
                    condition,
                    "fitness-wearable"
            );
        }
        if (isHairStyler(name)) {
            return decision("HAIR_STYLERS", condition, "hair-styler");
        }
        if (isAppleHeadphones(name)) {
            return decision("HEADPHONES_APPLE", condition, "apple-headphones");
        }
        if (isSamsungHeadphones(name)) {
            return decision("HEADPHONES_SAMSUNG", condition, "samsung-headphones");
        }
        if (isOtherHeadphones(name)) {
            return decision("HEADPHONES_OTHER", condition, "other-headphones");
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
        if (isSamsungWatch(name)) {
            return decision("PODS_WATCH_OTHER_DEVICE", condition, "samsung-watch");
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
                "marshall",
                "harman kardon",
                "jbl ",
                "whoop",
                "fitbit",
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

    private boolean isConfirmedIphoneCaseModel(String name) {
        return name.matches(".*x-crystal (?:14|15|16|17)(?: |$).*")
                || name.matches(".*keephone mago pro 15 pro max(?: |$).*")
                || name.matches(".*keephone mago pro matte magsafe "
                        + "(?:15 pro|16 pro max|17 pro(?: max)?)(?: |$).*")
                || (name.matches(".*\\bkeephone\\b.*\\b1[4-9]\\b .+")
                        && !isOtherDeviceCase(name))
                || (name.startsWith("чехол ")
                        && name.matches(".*\\b1[3-9](?:e| pro(?: max)?| plus| mini| air)\\b.*")
                        && !isOtherDeviceCase(name));
    }

    private boolean isCaseProductName(String name) {
        return containsAny(name, "чехол", "чехлол", " case", "case ", "бампер")
                // Confirmed short catalog label, not a rule for every Keephone product.
                || name.matches("keephone x[- ]crystal (?:samsung|самсунг)");
    }

    private boolean isProtectionProductName(String name) {
        return containsAny(name, "стекл", "стекол") || isCameraProtection(name);
    }

    private boolean hasExplicitSamsungAccessoryTarget(String name) {
        return containsAny(name, "samsung", "самсунг")
                || SAMSUNG_ACCESSORY_MODEL.matcher(name).find();
    }

    private boolean isSamsungAccessoryTarget(String name) {
        // Galaxy alone can be a color/design; an explicit iPhone target takes priority.
        // Explicit iPhone + Samsung targets have already been rejected by classify().
        return hasExplicitSamsungAccessoryTarget(name)
                || !isIphone(name) && isSamsung(name);
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
                "camera lens",
                "стекло для камер",
                "стекло на камер"
        );
    }

    private boolean isSpeaker(String name) {
        return containsAny(
                name,
                "колонк",
                "яндекс станци",
                "yandex station",
                "jbl flip",
                "jbl charge",
                "harman kardon onyx",
                "bluetooth speaker",
                "wireless speaker"
        );
    }

    private boolean isSmartGlasses(String name) {
        return containsAny(
                name,
                "ray ban",
                "ray-ban",
                "rayban",
                "meta wayfarer"
        );
    }

    private boolean isInstaxCamera(String name) {
        return name.contains("instax mini 13")
                && !containsAny(name, "film", "пленк", "кассет", "картридж",
                        "альбом", "album");
    }

    private boolean isFitnessWearable(String name) {
        return containsAny(
                name,
                "garmin forerunner",
                "garmin vivoactive",
                "google fitbit air",
                "whoop 5 0",
                "whoop life 5 0"
        );
    }

    private boolean isHairStyler(String name) {
        return containsAny(name, "dyson hs08", "dyson airwrap")
                && !containsAny(name, "насадк", "attachment", "фильтр", "filter",
                        "щетк", "brush", "расческ", "футляр", "case", "чехол");
    }

    private boolean isAppleHeadphones(String name) {
        return containsAny(name, "airpods", "earpods");
    }

    private boolean isSamsungHeadphones(String name) {
        return name.contains("galaxy buds");
    }

    private boolean isOtherHeadphones(String name) {
        return containsAny(name, "marshall major", "sony wf-", "sony wh-",
                "яндекс дропс", "наушник", "headphone", "earphone");
    }

    private boolean isHeadphonePart(String name) {
        return containsAny(name, "амбушюр", "ear tips", "eartips",
                "ear cushions", "earpads", "насадка для наушник");
    }

    private boolean isSamsungWatch(String name) {
        return containsAny(name, "galaxy watch", "samsung watch");
    }

    private boolean isFitnessWearableAccessory(String name) {
        return containsAny(name, "garmin", "fitbit", "whoop")
                && containsAny(
                        name,
                        "ремешок",
                        "сменный браслет",
                        "replacement band",
                        "active band",
                        "sport band",
                        " strap"
                );
    }

    private boolean isIpadOrMac(String name) {
        return containsAny(name, "ipad", "macbook", "imac", "mac mini", "макбук");
    }

    private boolean isIpad(String name) {
        return containsAny(name, "ipad", "apple pencil");
    }

    private boolean isMac(String name) {
        return containsAny(name, "macbook", "imac", "mac mini", "макбук");
    }

    private boolean isOtherDeviceCase(String name) {
        return containsAny(name, "ноутбук", "laptop", "планшет", "pixel",
                "xiaomi", "redmi", "poco", "huawei", "honor", "oneplus",
                "oppo", "realme", "vivo", "tecno", "infinix", "motorola",
                "nokia", "nothing phone", "sony xperia", "lenovo", "asus",
                "nintendo", "steam deck", "playstation", "ps5");
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
                "earpods",
                "apple watch",
                "iwatch",
                "galaxy buds",
                "galaxy watch",
                "samsung watch"
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
