package dev.saseq.primobot.sales;

import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SalesAggregatorService {
    private static final Logger LOG = LoggerFactory.getLogger(SalesAggregatorService.class);
    private final Map<SalesPlatform, SalesProvider> providers = new EnumMap<>(SalesPlatform.class);

    public SalesAggregatorService(List<SalesProvider> salesProviders) {
        if (salesProviders != null) {
            for (SalesProvider provider : salesProviders) {
                if (provider != null) {
                    providers.put(provider.platform(), provider);
                }
            }
        }
    }

    public SalesReportSnapshot aggregate(SalesReportConfig config, ZoneId zoneId) {
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        LocalDate reportDate = now.toLocalDate();
        SalesFetchContext context = new SalesFetchContext(zoneId, reportDate);

        List<SalesAccountResult> accountResults = new ArrayList<>();
        Map<SalesPlatform, BigDecimal> subtotals = new LinkedHashMap<>();
        for (SalesPlatform platform : SalesPlatform.values()) {
            subtotals.put(platform, BigDecimal.ZERO);
        }

        if (config == null || config.getAccounts() == null) {
            return new SalesReportSnapshot(now, accountResults, subtotals, BigDecimal.ZERO);
        }

        for (SalesAccountConfig account : config.getAccounts()) {
            if (account == null || !account.isEnabled()) {
                continue;
            }

            SalesPlatform platform = account.resolvePlatform();
            if (platform == null) {
                accountResults.add(SalesAccountResult.failure(account, null, "Unknown Metric", "Invalid platform"));
                continue;
            }

            SalesProvider provider = providers.get(platform);
            if (provider == null) {
                accountResults.add(SalesAccountResult.failure(account, platform, platform.getMetricLabel(), "No provider configured"));
                continue;
            }

            try {
                SalesAccountResult result = provider.fetchTodayCumulative(account, context);
                if (result == null) {
                    accountResults.add(SalesAccountResult.failure(account, platform, platform.getMetricLabel(), "Provider returned no data"));
                    continue;
                }

                accountResults.add(result);
                if (result.success() && result.amount() != null) {
                    subtotals.put(platform, subtotals.getOrDefault(platform, BigDecimal.ZERO).add(result.amount()));
                }
            } catch (Exception ex) {
                LOG.warn("Sales fetch failed for account '{}' ({}) : {}",
                        account.getName(),
                        platform,
                        ex.getMessage());
                accountResults.add(SalesAccountResult.failure(account, platform, platform.getMetricLabel(), cleanError(ex.getMessage())));
            }
        }

        BigDecimal grandTotal = BigDecimal.ZERO;
        for (BigDecimal value : subtotals.values()) {
            grandTotal = grandTotal.add(value == null ? BigDecimal.ZERO : value);
        }

        return new SalesReportSnapshot(now, accountResults, subtotals, grandTotal);
    }

    private String cleanError(String message) {
        if (message == null || message.isBlank()) {
            return "Unable to fetch sales";
        }
        String normalized = message.trim().replace('\n', ' ');
        return normalized.length() > 160 ? normalized.substring(0, 160) + "..." : normalized;
    }
}
