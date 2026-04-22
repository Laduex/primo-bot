package dev.saseq.primobot.sales;

import java.util.Locale;

public enum SalesPlatform {
    UTAK("UTAK", "Total Net Sales"),
    LOYVERSE("Loyverse", "Gross Sales Today");

    private final String displayName;
    private final String metricLabel;

    SalesPlatform(String displayName, String metricLabel) {
        this.displayName = displayName;
        this.metricLabel = metricLabel;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getMetricLabel() {
        return metricLabel;
    }

    public static SalesPlatform fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return SalesPlatform.valueOf(raw.trim().toUpperCase(Locale.ENGLISH));
        } catch (Exception ignored) {
            return null;
        }
    }
}
