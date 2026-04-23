package dev.saseq.primobot.sales;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtakBrowserSalesProviderTest {

    @Test
    void sumsTransactionTotalsFromFirebasePayload() throws Exception {
        String payload = """
                {
                  "1776813312": { "total": 120.5, "paymentType": "Cash" },
                  "1776813412": { "total": 99.5, "paymentType": "QRPh" },
                  "1776813512": { "total": "280", "paymentType": "Grab" }
                }
                """;

        BigDecimal total = UtakBrowserSalesProvider.sumTotalNetSalesFromTransactionsJson(payload);

        assertEquals(new BigDecimal("500.0"), total);
    }

    @Test
    void ignoresDeletedAndInvalidRows() throws Exception {
        String payload = """
                {
                  "1776813312": { "total": 100, "_deleted": true },
                  "1776813412": { "total": "not-a-number" },
                  "1776813512": { "total": 250.25 }
                }
                """;

        BigDecimal total = UtakBrowserSalesProvider.sumTotalNetSalesFromTransactionsJson(payload);

        assertEquals(new BigDecimal("250.25"), total);
    }

    @Test
    void handlesEmptyPayload() throws Exception {
        assertEquals(BigDecimal.ZERO, UtakBrowserSalesProvider.sumTotalNetSalesFromTransactionsJson("null"));
        assertEquals(BigDecimal.ZERO, UtakBrowserSalesProvider.sumTotalNetSalesFromTransactionsJson(""));
    }

    @Test
    void aggregatesSkuSalesFromTransactionItems() throws Exception {
        String payload = """
                {
                  "1776813312": {
                    "total": 995,
                    "items": [
                      { "id": "BG001", "title": "Roasted Tomato & Cheese Bagel", "option": "Cafe", "quantity": 2, "price": 250 },
                      { "id": "LAT12", "title": "12oz Hot Latte", "option": "Cafe", "quantity": 3, "price": 165 }
                    ]
                  },
                  "1776813412": {
                    "total": 360,
                    "items": [
                      { "id": "LAT12", "title": "12oz Hot Latte", "option": "Cafe", "quantity": 1, "price": 165 },
                      { "id": "AM16", "title": "16oz Iced Americano", "option": "Grab", "quantity": 1, "price": 195 }
                    ]
                  }
                }
                """;

        UtakBrowserSalesProvider.TransactionAggregation aggregation =
                UtakBrowserSalesProvider.aggregateSalesFromTransactionsJson(payload);

        assertEquals(new BigDecimal("1355"), aggregation.totalNetSales());
        assertEquals(3, aggregation.skuSales().size());

        Map<String, BigDecimal> byName = aggregation.skuSales().stream()
                .collect(java.util.stream.Collectors.toMap(
                        SkuSalesEntry::displayName,
                        SkuSalesEntry::salesAmount,
                        BigDecimal::add));

        assertEquals(new BigDecimal("500"), byName.get("Roasted Tomato & Cheese Bagel (Cafe)"));
        assertEquals(new BigDecimal("660"), byName.get("12oz Hot Latte (Cafe)"));
        assertEquals(new BigDecimal("195"), byName.get("16oz Iced Americano (Grab)"));
    }

    @Test
    void skipsItemAggregationForMissingItemsButKeepsTotals() throws Exception {
        String payload = """
                {
                  "1776813312": { "total": 120.5, "paymentType": "Cash" },
                  "1776813412": { "total": 99.5, "_deleted": true },
                  "1776813512": { "total": 280, "items": [] }
                }
                """;

        UtakBrowserSalesProvider.TransactionAggregation aggregation =
                UtakBrowserSalesProvider.aggregateSalesFromTransactionsJson(payload);

        assertEquals(new BigDecimal("400.5"), aggregation.totalNetSales());
        assertTrue(aggregation.skuSales().isEmpty());
    }
}
