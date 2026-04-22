package dev.saseq.primobot.sales;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

public record SalesReportSnapshot(ZonedDateTime generatedAt,
                                  List<SalesAccountResult> accountResults,
                                  Map<SalesPlatform, BigDecimal> subtotals,
                                  BigDecimal grandTotal) {
}
