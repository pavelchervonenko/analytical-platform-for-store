package com.storeanalytics.notification.fanout;

import com.storeanalytics.notification.daily.DailyStorePulsePayload;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DailyTelegramMessageRenderer {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public RenderedTelegramMessage render(
            DailyNotificationEvent event,
            DailyStorePulsePayload payload
    ) {
        StringBuilder text = new StringBuilder();
        line(text, "☀️ УТРО · СВОДКА");
        line(text, event.storeName());
        line(text, DATE.format(payload.businessDate()) + " · сравнение с "
                + DATE.format(payload.comparisonDate()));
        blank(text);
        line(text, "💰 КЛЮЧЕВЫЕ ПОКАЗАТЕЛИ");
        metricLine(text, "Выручка", payload.result());
        metricLine(text, "Средний чек", payload.averageReceipt());
        metricLine(text, "Доп. продажи", payload.additionalRevenue());
        metricLine(text, "Доп. продажи на телефон", payload.additionalRevenuePerPhone());
        namedSection(text, "📦 КАТЕГОРИИ", payload.categories());
        namedSection(text, "👥 ЛИДЕРЫ КОМАНДЫ", payload.employees());
        if (!payload.quality().completeCostData()
                || payload.quality().openIssueCount() > 0) {
            blank(text);
            line(text, "⚠️ ДАННЫЕ");
            line(text, "Есть ограничения качества данных — проверьте кабинет.");
        }
        blank(text);
        line(text, "ПОЛНЫЙ ОБЗОР");
        line(text, "Детали и текущая динамика доступны в аналитическом кабинете.");
        String rendered = text.toString().strip();
        if (rendered.codePointCount(0, rendered.length())
                > WeeklyTelegramMessageRenderer.TELEGRAM_TEXT_LIMIT) {
            throw new IllegalStateException("Daily Telegram message exceeds provider limit");
        }
        return new RenderedTelegramMessage(rendered, sha256(rendered));
    }

    private void namedSection(
            StringBuilder text,
            String title,
            List<DailyStorePulsePayload.NamedMetric> values
    ) {
        if (values.isEmpty()) {
            return;
        }
        blank(text);
        line(text, title);
        values.forEach(value -> metricLine(
                text,
                value.name(),
                value.value(),
                value.changePercent()
        ));
    }

    private void metricLine(
            StringBuilder text,
            String label,
            DailyStorePulsePayload.Metric metric
    ) {
        metricLine(text, label, metric.value(), metric.changePercent());
    }

    private void metricLine(
            StringBuilder text,
            String label,
            BigDecimal value,
            BigDecimal changePercent
    ) {
        line(text, "• " + label + " — " + money(value) + trend(changePercent));
    }

    private String money(BigDecimal value) {
        if (value == null) {
            return "нет данных";
        }
        return value.setScale(0, RoundingMode.HALF_UP)
                .toPlainString().replaceAll("(?<=\\d)(?=(\\d{3})+$)", " ") + " ₽";
    }

    private String trend(BigDecimal value) {
        if (value == null) {
            return "";
        }
        if (value.signum() == 0) {
            return " · → без изменений";
        }
        String arrow = value.signum() > 0 ? "↑" : "↓";
        return " · " + arrow + " " + decimal(value.abs()) + "% ко вчера";
    }

    private String decimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private void line(StringBuilder text, String value) {
        String normalized = normalize(value);
        if (!normalized.isBlank()) {
            text.append(normalized).append('\n');
        }
    }

    private String normalize(String value) {
        return value.strip().replaceAll("\\s+", " ");
    }

    private void blank(StringBuilder text) {
        text.append('\n');
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)
            ));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
