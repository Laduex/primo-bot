package dev.saseq.primobot.sales;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
