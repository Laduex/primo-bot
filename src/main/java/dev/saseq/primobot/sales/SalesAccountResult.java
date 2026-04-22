package dev.saseq.primobot.sales;

import java.math.BigDecimal;

public record SalesAccountResult(String accountId,
                                 String accountName,
                                 SalesPlatform platform,
                                 String metricLabel,
                                 BigDecimal amount,
                                 boolean success,
                                 String errorMessage) {

    public static SalesAccountResult success(SalesAccountConfig account,
                                             SalesPlatform platform,
                                             String metricLabel,
                                             BigDecimal amount) {
        return new SalesAccountResult(
                account == null ? "" : account.getId(),
                account == null ? "Unknown" : account.getName(),
                platform,
                metricLabel,
                amount == null ? BigDecimal.ZERO : amount,
                true,
                null
        );
    }

    public static SalesAccountResult failure(SalesAccountConfig account,
                                             SalesPlatform platform,
                                             String metricLabel,
                                             String errorMessage) {
        return new SalesAccountResult(
                account == null ? "" : account.getId(),
                account == null ? "Unknown" : account.getName(),
                platform,
                metricLabel,
                BigDecimal.ZERO,
                false,
                errorMessage == null ? "Unknown error" : errorMessage
        );
    }
}
