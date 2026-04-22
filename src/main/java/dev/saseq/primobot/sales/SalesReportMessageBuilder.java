package dev.saseq.primobot.sales;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class SalesReportMessageBuilder {
    private static final DecimalFormat PHP_DECIMAL_FORMAT = new DecimalFormat("#,##0.00");

    public String resolveGreeting(LocalTime localTime) {
        int hour = localTime.getHour();
        if (hour >= 5 && hour <= 11) {
            return "Good Morning";
        }
        if (hour >= 12 && hour <= 17) {
            return "Good Afternoon";
        }
        return "Good Evening";
    }

    public String buildMessage(SalesReportSnapshot snapshot, String tone, String signature) {
        boolean casual = "casual".equalsIgnoreCase(tone);
        String greeting = resolveGreeting(snapshot.generatedAt().toLocalTime());

        StringBuilder content = new StringBuilder();
        if (casual) {
            content.append(greeting).append(", team! Here's your sales pulse for today so far.\n\n");
        } else {
            content.append(greeting).append(", team. Here is today's cumulative sales update.\n\n");
        }

        List<SalesAccountResult> results = snapshot.accountResults() == null
                ? new ArrayList<>()
                : new ArrayList<>(snapshot.accountResults());

        if (results.isEmpty()) {
            content.append("No enabled sales accounts are configured yet. Use `/sales-report add-account` to get started.");
            appendSignature(content, signature);
            return content.toString();
        }

        results.sort(Comparator.comparing(SalesAccountResult::accountName, String.CASE_INSENSITIVE_ORDER));

        boolean hasSuccess = false;
        for (SalesAccountResult result : results) {
            if (!result.success()) {
                continue;
            }
            hasSuccess = true;
            content.append("- **")
                    .append(escapeMarkdown(result.accountName()))
                    .append("**: `")
                    .append(formatPhp(result.amount()))
                    .append("`\n");
        }

        if (!hasSuccess) {
            content.append("- (no successful fetches)\n");
        }

        content.append("\n**Grand Total:** `")
                .append(formatPhp(snapshot.grandTotal()))
                .append("`\n");

        List<SalesAccountResult> failures = results.stream()
                .filter(result -> !result.success())
                .toList();
        if (!failures.isEmpty()) {
            if (casual) {
                content.append("\nHeads up: I couldn't fetch some accounts this run. I'll try again on the next schedule:\n");
            } else {
                content.append("\nWarning: Some accounts failed during this run:\n");
            }

            for (SalesAccountResult failure : failures) {
                content.append("- ")
                        .append(escapeMarkdown(failure.accountName()))
                        .append(" (")
                        .append(failure.platform() == null ? "Unknown" : failure.platform().getDisplayName())
                        .append("): couldn't fetch sales right now.")
                        .append("\n");
            }
        }

        appendSignature(content, signature);
        return content.toString();
    }

    private void appendSignature(StringBuilder content, String signature) {
        String trimmed = signature == null ? "" : signature.trim();
        if (!trimmed.isBlank()) {
            content.append("\n\n").append(trimmed);
        }
    }

    private String formatPhp(BigDecimal value) {
        BigDecimal safe = value == null ? BigDecimal.ZERO : value;
        return "PHP " + PHP_DECIMAL_FORMAT.format(safe);
    }

    private String escapeMarkdown(String value) {
        if (value == null || value.isBlank()) {
            return "Unnamed Account";
        }
        return value.replace("`", "'").replace("*", "");
    }

}
