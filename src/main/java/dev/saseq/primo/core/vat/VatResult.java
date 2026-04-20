package dev.saseq.primo.core.vat;

import java.math.BigDecimal;

public record VatResult(
        String basisLabel,
        BigDecimal netAmount,
        BigDecimal vatAmount,
        BigDecimal grossAmount
) {
}
