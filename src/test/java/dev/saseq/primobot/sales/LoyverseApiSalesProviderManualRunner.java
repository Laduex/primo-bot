package dev.saseq.primobot.sales;

import java.time.LocalDate;
import java.time.ZoneId;

public final class LoyverseApiSalesProviderManualRunner {
    private LoyverseApiSalesProviderManualRunner() {
    }

    public static void main(String[] args) {
        String token = requiredEnv("LOYVERSE_TOKEN");
        String endpoint = envOrDefault("LOYVERSE_ENDPOINT", "https://api.loyverse.com/v1.0/receipts");
        String accountName = envOrDefault("LOYVERSE_ACCOUNT_NAME", "manual-loyverse");
        String timezone = envOrDefault("LOYVERSE_TIMEZONE", "Asia/Manila");

        SalesAccountConfig account = new SalesAccountConfig();
        account.setId("manual-loyverse");
        account.setPlatform("LOYVERSE");
        account.setName(accountName);
        account.setEnabled(true);
        account.setToken(token);
        account.setBaseUrl(endpoint);

        ZoneId zoneId = ZoneId.of(timezone);
        SalesFetchContext context = new SalesFetchContext(zoneId, LocalDate.now(zoneId));

        LoyverseApiSalesProvider provider = new LoyverseApiSalesProvider();
        SalesAccountResult result = provider.fetchTodayCumulative(account, context);

        System.out.println("Loyverse Gross Sales fetch succeeded.");
        System.out.println("account=" + result.accountName());
        System.out.println("metric=" + result.metricLabel());
        System.out.println("amount=" + result.amount());
    }

    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.trim().isBlank()) {
            throw new IllegalArgumentException("Missing required env var: " + key);
        }
        return value.trim();
    }

    private static String envOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.trim().isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }
}
