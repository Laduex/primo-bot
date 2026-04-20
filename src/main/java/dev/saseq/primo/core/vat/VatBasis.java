package dev.saseq.primo.core.vat;

import java.util.Locale;

public enum VatBasis {
    INCLUSIVE("Gross (VAT Inclusive)"),
    EXCLUSIVE("Net (VAT Exclusive)");

    private final String label;

    VatBasis(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static VatBasis fromInput(String input) {
        if (input == null) {
            throw new IllegalArgumentException("basis is required");
        }

        String normalized = input.trim().toLowerCase(Locale.ENGLISH);
        return switch (normalized) {
            case "inclusive", "gross" -> INCLUSIVE;
            case "exclusive", "net" -> EXCLUSIVE;
            default -> throw new IllegalArgumentException("basis must be either 'inclusive' or 'exclusive'");
        };
    }
}
