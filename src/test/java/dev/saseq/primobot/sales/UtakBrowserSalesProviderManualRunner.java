package dev.saseq.primobot.sales;

import java.time.LocalDate;
import java.time.ZoneId;

public final class UtakBrowserSalesProviderManualRunner {
    private UtakBrowserSalesProviderManualRunner() {
    }

    public static void main(String[] args) {
        String username = requiredEnv("UTAK_USERNAME");
        String password = requiredEnv("UTAK_PASSWORD");
        String salesUrl = envOrDefault("UTAK_SALES_URL", "https://utak.io/login");
        String baseUrl = envOrDefault("UTAK_BASE_URL", "https://utak.io/login");
        String accountName = envOrDefault("UTAK_ACCOUNT_NAME", "manual-utak");
        String timezone = envOrDefault("UTAK_TIMEZONE", "Asia/Manila");

        SalesAccountConfig account = new SalesAccountConfig();
        account.setId("manual-utak");
        account.setPlatform("UTAK");
        account.setName(accountName);
        account.setEnabled(true);
        account.setUsername(username);
        account.setPassword(password);
        account.setBaseUrl(baseUrl);
        account.setSalesPageUrl(salesUrl);

        ZoneId zoneId = ZoneId.of(timezone);
        SalesFetchContext context = new SalesFetchContext(zoneId, LocalDate.now(zoneId));

        UtakBrowserSalesProvider provider = new UtakBrowserSalesProvider();
        SalesAccountResult result = provider.fetchTodayCumulative(account, context);

        System.out.println("UTAK Total Net Sales fetch succeeded.");
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
