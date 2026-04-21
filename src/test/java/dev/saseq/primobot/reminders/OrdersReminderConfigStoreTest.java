package dev.saseq.primobot.reminders;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrdersReminderConfigStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void initializesDefaultConfigAndPersistsFile() throws Exception {
        Path configPath = tempDir.resolve("orders-reminder-config.json");
        OrdersReminderConfigStore store = new OrdersReminderConfigStore(
                configPath.toString(),
                true,
                "08:00",
                "Asia/Manila",
                "1494503996357087443:1494175620287041536:1494215754084651089");

        store.initialize();

        OrdersReminderConfig config = store.getSnapshot();
        assertTrue(Files.exists(configPath));
        assertTrue(config.isEnabled());
        assertEquals("Asia/Manila", config.getTimezone());
        assertEquals(8, config.getHour());
        assertEquals(0, config.getMinute());
        assertEquals("casual", config.getMessageTone());
        assertEquals("Thanks, Primo", config.getSignature());
        assertEquals(1, config.getRoutes().size());
        assertEquals("1494503996357087443", config.getRoutes().get(0).getForumId());
    }

    @Test
    void replaceAndPersistWritesUpdatedValues() {
        Path configPath = tempDir.resolve("nested").resolve("orders-reminder-config.json");
        OrdersReminderConfigStore store = new OrdersReminderConfigStore(
                configPath.toString(),
                true,
                "08:00",
                "Asia/Manila",
                "");

        store.initialize();
        OrdersReminderConfig config = store.getSnapshot();
        config.setEnabled(false);
        config.setHour(14);
        config.setMinute(30);
        config.setTimezone("Asia/Manila");
        config.setSignature("Thanks, Primo");
        config.getLastRunDateByRoute().put("1494503996357087443", "2026-04-21");

        store.replaceAndPersist(config);

        OrdersReminderConfig reloaded = store.getSnapshot();
        assertFalse(reloaded.isEnabled());
        assertEquals(14, reloaded.getHour());
        assertEquals(30, reloaded.getMinute());
        assertEquals("2026-04-21", reloaded.getLastRunDateByRoute().get("1494503996357087443"));
    }
}
