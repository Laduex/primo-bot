package dev.saseq.primobot.meta;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetaUnreadConfigStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void initializesDefaultConfigAndPersistsFile() {
        Path configPath = tempDir.resolve("meta-unread-config.json");
        MetaUnreadConfigStore store = new MetaUnreadConfigStore(
                configPath.toString(),
                false,
                15,
                "");

        store.initialize();

        MetaUnreadConfig config = store.getSnapshot();
        assertTrue(Files.exists(configPath));
        assertFalse(config.isEnabled());
        assertEquals(15, config.getIntervalMinutes());
        assertEquals("", config.getTargetChannelId());
        assertEquals(0L, config.getLastRunAtEpochMs());
    }

    @Test
    void normalizesIntervalAndPersistsLastRun() {
        Path configPath = tempDir.resolve("nested").resolve("meta-unread-config.json");
        MetaUnreadConfigStore store = new MetaUnreadConfigStore(
                configPath.toString(),
                true,
                1,
                "not-snowflake");

        store.initialize();

        MetaUnreadConfig config = store.getSnapshot();
        assertTrue(config.isEnabled());
        assertEquals(5, config.getIntervalMinutes());

        config.setIntervalMinutes(99);
        config.setTargetChannelId("1494175620287041536");
        config.setLastRunAtEpochMs(1_743_654_000_000L);
        store.replaceAndPersist(config);

        MetaUnreadConfig reloaded = store.getSnapshot();
        assertEquals(60, reloaded.getIntervalMinutes());
        assertEquals("1494175620287041536", reloaded.getTargetChannelId());
        assertEquals(1_743_654_000_000L, reloaded.getLastRunAtEpochMs());
    }
}
