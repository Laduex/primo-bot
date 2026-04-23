package dev.saseq.primobot.sales;

import java.math.BigDecimal;

public record SkuSalesEntry(String skuKey,
                            String displayName,
                            BigDecimal salesAmount) {
}
