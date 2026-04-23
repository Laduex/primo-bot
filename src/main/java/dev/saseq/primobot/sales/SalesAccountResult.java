package dev.saseq.primobot.sales;

import java.math.BigDecimal;
import java.util.List;

public record SalesAccountResult(String accountId,
                                 String accountName,
                                 SalesPlatform platform,
                                 String metricLabel,
                                 BigDecimal amount,
                                 boolean success,
                                 String errorMessage,
                                 List<SkuSalesEntry> skuSales) {

    public static SalesAccountResult success(SalesAccountConfig account,
                                             SalesPlatform platform,
                                             String metricLabel,
                                             BigDecimal amount) {
        return success(account, platform, metricLabel, amount, List.of());
    }

    public static SalesAccountResult success(SalesAccountConfig account,
                                             SalesPlatform platform,
                                             String metricLabel,
                                             BigDecimal amount,
                                             List<SkuSalesEntry> skuSales) {
        return new SalesAccountResult(
                account == null ? "" : account.getId(),
                account == null ? "Unknown" : account.getName(),
                platform,
                metricLabel,
                amount == null ? BigDecimal.ZERO : amount,
                true,
                null,
                skuSales == null ? List.of() : List.copyOf(skuSales)
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
                errorMessage == null ? "Unknown error" : errorMessage,
                List.of()
        );
    }
}
