package dev.saseq.primobot.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossProcessClaimStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void persistentClaimBlocksUntilReleased() {
        CrossProcessClaimStore store = new CrossProcessClaimStore(tempDir.toString());

        assertTrue(store.tryClaim("sales", "2026-05-04|update:12:00"));
        assertFalse(store.tryClaim("sales", "2026-05-04|update:12:00"));

        store.release("sales", "2026-05-04|update:12:00");

        assertTrue(store.tryClaim("sales", "2026-05-04|update:12:00"));
    }

    @Test
    void expiredLeaseCanBeReclaimed() throws IOException {
        CrossProcessClaimStore store = new CrossProcessClaimStore(tempDir.toString());
        Duration ttl = Duration.ofMinutes(5);

        assertTrue(store.tryClaim("slash", "interaction-123", ttl));
        assertFalse(store.tryClaim("slash", "interaction-123", ttl));

        Path claimPath;
        try (var files = Files.walk(tempDir)) {
            claimPath = files.filter(Files::isRegularFile)
                    .findFirst()
                    .orElseThrow();
        }
        Files.setLastModifiedTime(claimPath, FileTime.from(Instant.now().minus(Duration.ofMinutes(10))));

        assertTrue(store.tryClaim("slash", "interaction-123", ttl));
    }
}
