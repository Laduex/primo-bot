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
                SalesAccountResult.success(utak, SalesPlatform.UTAK, SalesPlatform.UTAK.getMetricLabel(), new BigDecimal("1000.50")),
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
        assertTrue(message.contains("Good Morning, team!"));
        assertTrue(message.contains("- **UTAK Main**: `PHP 1,000.50`"));
        assertTrue(message.contains("**Grand Total:** `PHP 1,000.50`"));
        assertTrue(!message.contains("Subtotal:"));
        assertTrue(!message.contains("UTAK Net + Loyverse Gross"));
        assertTrue(message.contains("Heads up"));
        assertTrue(message.endsWith("Thanks, Primo"));
    }
}
