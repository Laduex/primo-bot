package dev.saseq.primo.core.vat;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

public class VatCalculator {
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final MathContext MC = MathContext.DECIMAL64;

    public VatResult calculate(BigDecimal amount, BigDecimal vatRatePercent, VatBasis basis) {
        if (amount == null || vatRatePercent == null || basis == null) {
            throw new IllegalArgumentException("amount, vatRatePercent, and basis are required");
        }
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("amount must be zero or greater");
        }

        BigDecimal rateFraction = vatRatePercent.divide(ONE_HUNDRED, MC);
        BigDecimal net;
        BigDecimal vat;
        BigDecimal gross;

        if (basis == VatBasis.INCLUSIVE) {
            gross = roundMoney(amount);
            BigDecimal divisor = BigDecimal.ONE.add(rateFraction, MC);
            net = roundMoney(gross.divide(divisor, MC));
            vat = roundMoney(gross.subtract(net, MC));
        } else {
            net = roundMoney(amount);
            vat = roundMoney(net.multiply(rateFraction, MC));
            gross = roundMoney(net.add(vat, MC));
        }

        return new VatResult(
                basis.label(),
                net,
                vat,
                gross
        );
    }

    private BigDecimal roundMoney(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
