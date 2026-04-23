package dev.saseq.primobot.sales;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SalesReportMessageBuilderTest {

    private final SalesReportMessageBuilder builder = new SalesReportMessageBuilder();

    @Test
    void resolvesGreetingByTimeBucket() {
        assertEquals("Good Morning", builder.resolveGreeting(LocalTime.of(5, 0)));
        assertEquals("Good Morning", builder.resolveGreeting(LocalTime.of(11, 59)));
        assertEquals("Good Afternoon", builder.resolveGreeting(LocalTime.of(12, 0)));
        assertEquals("Good Afternoon", builder.resolveGreeting(LocalTime.of(17, 59)));
        assertEquals("Good Evening", builder.resolveGreeting(LocalTime.of(18, 0)));
        assertEquals("Good Evening", builder.resolveGreeting(LocalTime.of(4, 59)));
    }

    @Test
    void buildsCasualMessageWithTotalsAndWarnings() {
        SalesAccountConfig utak = new SalesAccountConfig();
        utak.setId("utak-main");
        utak.setName("UTAK Main");

        SalesAccountConfig loyverse = new SalesAccountConfig();
        loyverse.setId("lyv-main");
        loyverse.setName("Loyverse Main");

        List<SalesAccountResult> results = List.of(
                SalesAccountResult.success(
                        utak,
                        SalesPlatform.UTAK,
                        SalesPlatform.UTAK.getMetricLabel(),
                        new BigDecimal("1000.50"),
                        List.of(
                                new SkuSalesEntry("LAT12::Cafe", "12oz Hot Latte (Cafe)", new BigDecimal("660.00")),
                                new SkuSalesEntry("BG001::Cafe", "Roasted Tomato & Cheese Bagel (Cafe)", new BigDecimal("500.00"))
                        )),
                SalesAccountResult.failure(loyverse, SalesPlatform.LOYVERSE, SalesPlatform.LOYVERSE.getMetricLabel(), "Token expired")
        );

        LinkedHashMap<SalesPlatform, BigDecimal> subtotals = new LinkedHashMap<>();
        subtotals.put(SalesPlatform.UTAK, new BigDecimal("1000.50"));
        subtotals.put(SalesPlatform.LOYVERSE, BigDecimal.ZERO);

        SalesReportSnapshot snapshot = new SalesReportSnapshot(
                ZonedDateTime.of(2026, 4, 22, 9, 0, 0, 0, ZoneId.of("Asia/Manila")),
                results,
                subtotals,
                new BigDecimal("1000.50")
        );

        String message = builder.buildMessage(snapshot, "casual", "Thanks, Primo");
        assertTrue(message.contains("Good Morning, team! Here's your daily sales overview."));
        assertTrue(message.contains("- **UTAK Main**: `PHP 1,000.50`"));
        assertTrue(message.contains("Top 10 Sold SKU (PHP):"));
        assertTrue(message.contains("1. 12oz Hot Latte (Cafe): `PHP 660.00`"));
        assertTrue(message.contains("2. Roasted Tomato & Cheese Bagel (Cafe): `PHP 500.00`"));
        assertTrue(message.contains("**Grand Total:** `PHP 1,000.50`"));
        assertTrue(!message.contains("Subtotal:"));
        assertTrue(!message.contains("UTAK Net + Loyverse Gross"));
        assertTrue(message.contains("Heads up"));
        assertTrue(message.endsWith("Thanks, Primo"));
    }

    @Test
    void rendersOnlyTopTenSkuEntries() {
        SalesAccountConfig account = new SalesAccountConfig();
        account.setId("utak-main");
        account.setName("UTAK Main");

        List<SkuSalesEntry> skuSales = List.of(
                new SkuSalesEntry("sku-01", "SKU 01", new BigDecimal("1000")),
                new SkuSalesEntry("sku-02", "SKU 02", new BigDecimal("990")),
                new SkuSalesEntry("sku-03", "SKU 03", new BigDecimal("980")),
                new SkuSalesEntry("sku-04", "SKU 04", new BigDecimal("970")),
                new SkuSalesEntry("sku-05", "SKU 05", new BigDecimal("960")),
                new SkuSalesEntry("sku-06", "SKU 06", new BigDecimal("950")),
                new SkuSalesEntry("sku-07", "SKU 07", new BigDecimal("940")),
                new SkuSalesEntry("sku-08", "SKU 08", new BigDecimal("930")),
                new SkuSalesEntry("sku-09", "SKU 09", new BigDecimal("920")),
                new SkuSalesEntry("sku-10", "SKU 10", new BigDecimal("910")),
                new SkuSalesEntry("sku-11", "SKU 11", new BigDecimal("900"))
        );

        List<SalesAccountResult> results = List.of(
                SalesAccountResult.success(account, SalesPlatform.UTAK, SalesPlatform.UTAK.getMetricLabel(), new BigDecimal("10450.00"), skuSales)
        );

        LinkedHashMap<SalesPlatform, BigDecimal> subtotals = new LinkedHashMap<>();
        subtotals.put(SalesPlatform.UTAK, new BigDecimal("10450.00"));
        subtotals.put(SalesPlatform.LOYVERSE, BigDecimal.ZERO);

        SalesReportSnapshot snapshot = new SalesReportSnapshot(
                ZonedDateTime.of(2026, 4, 22, 21, 0, 0, 0, ZoneId.of("Asia/Manila")),
                results,
                subtotals,
                new BigDecimal("10450.00")
        );

        String message = builder.buildMessage(snapshot, "formal", "Thanks, Primo");
        assertTrue(message.contains("Good Evening, team. Here is today's daily sales overview."));
        assertTrue(message.contains("10. SKU 10: `PHP 910.00`"));
        assertTrue(!message.contains("11. SKU 11"));
    }
}
