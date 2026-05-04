package dev.saseq.primobot.parity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.saseq.primo.core.vat.VatBasis;
import dev.saseq.primo.core.vat.VatCalculator;
import dev.saseq.primo.core.vat.VatResult;
import dev.saseq.primobot.reminders.OrdersReminderMessageBuilder;
import dev.saseq.primobot.sales.*;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParityFixtureExporterTest {

    private static final Path FIXTURE_ROOT = Path.of("tools", "parity", "fixtures", "java");
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    @Test
    void exportFixtures() throws Exception {
        exportVatFixtures();
        exportReminderFixtures();
        exportSalesFixtures();
    }

    private void exportVatFixtures() throws IOException {
        VatCalculator calculator = new VatCalculator();

        VatResult exclusive = calculator.calculate(new BigDecimal("1000.00"), new BigDecimal("12"), VatBasis.EXCLUSIVE);
        VatResult inclusive = calculator.calculate(new BigDecimal("1120.00"), new BigDecimal("12"), VatBasis.INCLUSIVE);

        writeFixture("commands/vat/exclusive_basic.json", Map.of(
                "input", Map.of(
                        "amount", "1000.00",
                        "vatRatePercent", "12",
                        "basis", "EXCLUSIVE"
                ),
                "output", vatOutput(exclusive)
        ));

        writeFixture("commands/vat/inclusive_basic.json", Map.of(
                "input", Map.of(
                        "amount", "1120.00",
                        "vatRatePercent", "12",
                        "basis", "INCLUSIVE"
                ),
                "output", vatOutput(inclusive)
        ));
    }

    private Map<String, String> vatOutput(VatResult result) {
        return Map.of(
                "basisLabel", result.basisLabel(),
                "netAmount", asMoney(result.netAmount()),
                "vatAmount", asMoney(result.vatAmount()),
                "grossAmount", asMoney(result.grossAmount())
        );
    }

    private void exportReminderFixtures() throws IOException {
        OrdersReminderMessageBuilder builder = new OrdersReminderMessageBuilder();

        writeFixture("commands/orders-reminder/greeting_buckets.json", Map.of(
                "cases", List.of(
                        Map.of("time", "05:00", "greeting", builder.resolveGreeting(LocalTime.of(5, 0))),
                        Map.of("time", "12:00", "greeting", builder.resolveGreeting(LocalTime.of(12, 0))),
                        Map.of("time", "18:00", "greeting", builder.resolveGreeting(LocalTime.of(18, 0)))
                )
        ));

        ThreadChannel thread = mock(ThreadChannel.class);
        when(thread.getName()).thenReturn("April 20 | Monday | Mechanika Order");
        when(thread.getId()).thenReturn("1495615105767837808");

        String message = builder.buildReminderMessage(
                "1494215754084651089",
                "Good Morning",
                "roastery-orders",
                "1478671501338214410",
                List.of(thread),
                "Thanks, Primo",
                "casual"
        );

        writeFixture("commands/orders-reminder/message_casual_single_thread.json", Map.of(
                "input", Map.of(
                        "mentionRoleId", "1494215754084651089",
                        "greeting", "Good Morning",
                        "forumName", "roastery-orders",
                        "guildId", "1478671501338214410",
                        "openThreads", List.of(Map.of(
                                "thread_id", "1495615105767837808",
                                "name", "April 20 | Monday | Mechanika Order"
                        )),
                        "signature", "Thanks, Primo",
                        "tone", "casual"
                ),
                "output", Map.of("message", message)
        ));
    }

    private void exportSalesFixtures() throws IOException {
        SalesReportMessageBuilder builder = new SalesReportMessageBuilder();

        writeFixture("commands/sales-report/greeting_buckets.json", Map.of(
                "cases", List.of(
                        Map.of("time", "05:00", "greeting", builder.resolveGreeting(LocalTime.of(5, 0))),
                        Map.of("time", "12:00", "greeting", builder.resolveGreeting(LocalTime.of(12, 0))),
                        Map.of("time", "18:00", "greeting", builder.resolveGreeting(LocalTime.of(18, 0)))
                )
        ));

        SalesAccountConfig utak = new SalesAccountConfig();
        utak.setId("utak-main");
        utak.setName("UTAK Main");

        SalesAccountConfig loyverse = new SalesAccountConfig();
        loyverse.setId("lyv-main");
        loyverse.setName("Loyverse Main");

        List<SalesAccountResult> casualResults = List.of(
                SalesAccountResult.success(
                        utak,
                        SalesPlatform.UTAK,
                        SalesPlatform.UTAK.getMetricLabel(),
                        new BigDecimal("1000.50"),
                        List.of(
                                new SkuSalesEntry("LAT12::Cafe", "12oz Hot Latte (Cafe)", new BigDecimal("660.00")),
                                new SkuSalesEntry("BG001::Cafe", "Roasted Tomato & Cheese Bagel (Cafe)", new BigDecimal("500.00"))
                        )
                ),
                SalesAccountResult.failure(loyverse, SalesPlatform.LOYVERSE, SalesPlatform.LOYVERSE.getMetricLabel(), "Token expired")
        );

        LinkedHashMap<SalesPlatform, BigDecimal> casualSubtotals = new LinkedHashMap<>();
        casualSubtotals.put(SalesPlatform.UTAK, new BigDecimal("1000.50"));
        casualSubtotals.put(SalesPlatform.LOYVERSE, BigDecimal.ZERO);

        SalesReportSnapshot casualSnapshot = new SalesReportSnapshot(
                ZonedDateTime.of(2026, 4, 22, 9, 0, 0, 0, ZoneId.of("Asia/Manila")),
                casualResults,
                casualSubtotals,
                new BigDecimal("1000.50")
        );

        String casualMessage = builder.buildMessage(casualSnapshot, "casual", "Thanks, Primo", false);
        writeFixture("commands/sales-report/message_casual_with_failure.json", Map.of(
                "input", Map.of(
                        "tone", "casual",
                        "signature", "Thanks, Primo",
                        "dailyOverview", false,
                        "generatedAt", "2026-04-22T09:00:00"
                ),
                "output", Map.of("message", casualMessage)
        ));

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

        List<SalesAccountResult> overviewResults = List.of(
                SalesAccountResult.success(
                        utak,
                        SalesPlatform.UTAK,
                        SalesPlatform.UTAK.getMetricLabel(),
                        new BigDecimal("10450.00"),
                        skuSales
                )
        );

        LinkedHashMap<SalesPlatform, BigDecimal> overviewSubtotals = new LinkedHashMap<>();
        overviewSubtotals.put(SalesPlatform.UTAK, new BigDecimal("10450.00"));
        overviewSubtotals.put(SalesPlatform.LOYVERSE, BigDecimal.ZERO);

        SalesReportSnapshot overviewSnapshot = new SalesReportSnapshot(
                ZonedDateTime.of(2026, 4, 22, 21, 0, 0, 0, ZoneId.of("Asia/Manila")),
                overviewResults,
                overviewSubtotals,
                new BigDecimal("10450.00")
        );

        String overviewMessage = builder.buildMessage(overviewSnapshot, "formal", "Thanks, Primo", true);
        writeFixture("commands/sales-report/message_formal_daily_overview_top10.json", Map.of(
                "input", Map.of(
                        "tone", "formal",
                        "signature", "Thanks, Primo",
                        "dailyOverview", true,
                        "generatedAt", "2026-04-22T21:00:00"
                ),
                "output", Map.of("message", overviewMessage)
        ));
    }

    private void writeFixture(String relativePath, Object payload) throws IOException {
        Path target = FIXTURE_ROOT.resolve(relativePath);
        Files.createDirectories(target.getParent());
        mapper.writeValue(target.toFile(), payload);
    }

    private String asMoney(BigDecimal value) {
        return value == null ? "0.00" : value.setScale(2).toPlainString();
    }
}
