package dev.saseq.primobot.sales;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SalesAggregatorServiceTest {

    @Test
    void aggregatesByPlatformAndGrandTotal() {
        SalesProvider utakProvider = new SalesProvider() {
            @Override
            public SalesPlatform platform() {
                return SalesPlatform.UTAK;
            }

            @Override
            public SalesAccountResult fetchTodayCumulative(SalesAccountConfig account, SalesFetchContext context) {
                return SalesAccountResult.success(account, SalesPlatform.UTAK, SalesPlatform.UTAK.getMetricLabel(), new BigDecimal("1200.00"));
            }
        };

        SalesProvider loyverseProvider = new SalesProvider() {
            @Override
            public SalesPlatform platform() {
                return SalesPlatform.LOYVERSE;
            }

            @Override
            public SalesAccountResult fetchTodayCumulative(SalesAccountConfig account, SalesFetchContext context) {
                return SalesAccountResult.success(account, SalesPlatform.LOYVERSE, SalesPlatform.LOYVERSE.getMetricLabel(), new BigDecimal("800.00"));
            }
        };

        SalesAggregatorService service = new SalesAggregatorService(List.of(utakProvider, loyverseProvider));

        SalesAccountConfig utakAccount = new SalesAccountConfig();
        utakAccount.setId("utak-main");
        utakAccount.setName("UTAK Main");
        utakAccount.setPlatform("UTAK");
        utakAccount.setEnabled(true);

        SalesAccountConfig loyverseAccount = new SalesAccountConfig();
        loyverseAccount.setId("lyv-main");
        loyverseAccount.setName("Loyverse Main");
        loyverseAccount.setPlatform("LOYVERSE");
        loyverseAccount.setEnabled(true);

        SalesReportConfig config = new SalesReportConfig();
        config.setAccounts(new ArrayList<>(List.of(utakAccount, loyverseAccount)));

        SalesReportSnapshot snapshot = service.aggregate(config, ZoneId.of("Asia/Manila"));

        assertEquals(2, snapshot.accountResults().size());
        assertEquals(new BigDecimal("1200.00"), snapshot.subtotals().get(SalesPlatform.UTAK));
        assertEquals(new BigDecimal("800.00"), snapshot.subtotals().get(SalesPlatform.LOYVERSE));
        assertEquals(new BigDecimal("2000.00"), snapshot.grandTotal());
    }
}
