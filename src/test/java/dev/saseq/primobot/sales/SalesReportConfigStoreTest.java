package dev.saseq.primobot.sales;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SalesReportConfigStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void initializesWithExpectedDefaults() {
        Path configPath = tempDir.resolve("sales-report-config.json");
        SalesReportConfigStore store = new SalesReportConfigStore(
                configPath.toString(),
                true,
                "Asia/Manila",
                "09:00,12:00,15:00,18:00,21:00",
                "",
                "casual",
                "Thanks, Primo");

        store.initialize();

        SalesReportConfig config = store.getSnapshot();
        assertTrue(Files.exists(configPath));
        assertTrue(config.isEnabled());
        assertEquals("Asia/Manila", config.getTimezone());
        assertEquals(5, config.getTimes().size());
        assertEquals("09:00", config.getTimes().get(0));
        assertEquals("casual", config.getMessageTone());
        assertEquals("Thanks, Primo", config.getSignature());
    }

    @Test
    void normalizesTimesAndAccountsOnPersist() {
        Path configPath = tempDir.resolve("nested").resolve("sales-report-config.json");
        SalesReportConfigStore store = new SalesReportConfigStore(
                configPath.toString(),
                true,
                "Asia/Manila",
                "09:00",
                "",
                "casual",
                "Thanks, Primo");

        store.initialize();
        SalesReportConfig config = store.getSnapshot();
        config.setTimes(new ArrayList<>(java.util.List.of("15:00", "09:00", "09:00", "bad")));

        SalesAccountConfig account = new SalesAccountConfig();
        account.setId("utak-main");
        account.setPlatform("UTAK");
        account.setName("Main UTAK");
        account.setUsername("user");
        account.setPassword("pass");

        config.setAccounts(new ArrayList<>(java.util.List.of(account)));
        store.replaceAndPersist(config);

        SalesReportConfig reloaded = store.getSnapshot();
        assertEquals(java.util.List.of("09:00", "15:00"), reloaded.getTimes());
        assertEquals(1, reloaded.getAccounts().size());
        assertEquals("utak-main", reloaded.getAccounts().get(0).getId());
    }
}
