package dev.saseq.primobot.sales;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoyverseApiSalesProviderTest {

    private final LoyverseApiSalesProvider provider = new LoyverseApiSalesProvider();

    @Test
    void aggregatesSkuSalesFromLineItems() {
        String payload = """
                {
                  "receipts": [
                    {
                      "created_at": "2026-04-23T02:00:00Z",
                      "line_items": [
                        {
                          "sku": "BG001",
                          "item_name": "Roasted Tomato & Cheese Bagel",
                          "variant_name": "Cafe",
                          "gross_total_money": { "amount": 50000 }
                        },
                        {
                          "sku": "LAT12",
                          "item_name": "12oz Hot Latte",
                          "variant_name": "Cafe",
                          "gross_total_money": { "amount": 49500 }
                        }
                      ]
                    },
                    {
                      "created_at": "2026-04-23T05:00:00Z",
                      "line_items": [
                        {
                          "sku": "LAT12",
                          "item_name": "12oz Hot Latte",
                          "variant_name": "Cafe",
                          "quantity": "3",
                          "price": "165"
                        }
                      ]
                    },
                    {
                      "created_at": "2026-04-23T06:00:00Z",
                      "receipt_type": "REFUND",
                      "line_items": [
                        {
                          "sku": "BG001",
                          "item_name": "Roasted Tomato & Cheese Bagel",
                          "variant_name": "Cafe",
                          "gross_total_money": { "amount": 50000 }
                        }
                      ]
                    }
                  ],
                  "cursor": ""
                }
                """;

        LoyverseApiSalesProvider.PageResult result = provider.parseSalesPageForDate(
                payload,
                LocalDate.of(2026, 4, 23),
                ZoneId.of("Asia/Manila"));

        assertEquals(new BigDecimal("1490.00"), result.amount());
        assertEquals(2, result.includedCount());
        assertEquals(2, result.skuSales().size());

        Map<String, BigDecimal> byName = result.skuSales().stream()
                .collect(Collectors.toMap(
                        SkuSalesEntry::displayName,
                        SkuSalesEntry::salesAmount,
                        BigDecimal::add));

        assertEquals(new BigDecimal("500.00"), byName.get("Roasted Tomato & Cheese Bagel (Cafe)"));
        assertEquals(new BigDecimal("990.00"), byName.get("12oz Hot Latte (Cafe)"));
    }

    @Test
    void returnsEmptySkuSalesWhenLineItemsMissing() {
        String payload = """
                {
                  "receipts": [
                    { "created_at": "2026-04-23T02:00:00Z", "gross_total": "350.00" }
                  ]
                }
                """;

        LoyverseApiSalesProvider.PageResult result = provider.parseSalesPageForDate(
                payload,
                LocalDate.of(2026, 4, 23),
                ZoneId.of("Asia/Manila"));

        assertEquals(new BigDecimal("350.00"), result.amount());
        assertTrue(result.skuSales().isEmpty());
    }
}
