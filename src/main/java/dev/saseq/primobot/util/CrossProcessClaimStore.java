package dev.saseq.primobot.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class CrossProcessClaimStore {
    private static final Logger LOG = LoggerFactory.getLogger(CrossProcessClaimStore.class);
    private static final long CLEANUP_INTERVAL = 64L;

    private final Path basePath;
    private final AtomicLong operationCounter = new AtomicLong();

    public CrossProcessClaimStore(@Value("${PRIMO_COORDINATION_PATH:/data/coordination}") String rawBasePath) {
        if (rawBasePath == null || rawBasePath.isBlank()) {
            this.basePath = Files.isDirectory(Path.of("/data"))
                    ? Path.of("/data/coordination")
                    : Path.of("data/coordination");
            return;
        }
        this.basePath = Path.of(rawBasePath.trim());
    }

    public boolean tryClaim(String namespace, String key) {
        return tryClaim(namespace, key, null);
    }

    public boolean tryClaim(String namespace, String key, Duration ttl) {
        if (namespace == null || namespace.isBlank() || key == null || key.isBlank()) {
            return true;
        }

        Path namespacePath = basePath.resolve(sanitizeNamespace(namespace));
        try {
            Files.createDirectories(namespacePath);
            maybeCleanup(namespacePath, ttl);

            Path claimPath = namespacePath.resolve(hashKey(key) + ".claim");
            if (ttl != null && isExpired(claimPath, ttl)) {
                Files.deleteIfExists(claimPath);
            }

            Files.writeString(
                    claimPath,
                    Instant.now() + System.lineSeparator() + key + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );
            return true;
        } catch (FileAlreadyExistsException ignored) {
            return false;
        } catch (IOException ex) {
            LOG.warn("Failed to create claim in {} for key {}: {}", namespacePath, key, ex.getMessage());
            return true;
        }
    }

    public void release(String namespace, String key) {
        if (namespace == null || namespace.isBlank() || key == null || key.isBlank()) {
            return;
        }

        try {
            Files.deleteIfExists(basePath.resolve(sanitizeNamespace(namespace)).resolve(hashKey(key) + ".claim"));
        } catch (IOException ex) {
            LOG.warn("Failed to release claim {}:{}: {}", namespace, key, ex.getMessage());
        }
    }

    private void maybeCleanup(Path namespacePath, Duration ttl) throws IOException {
        if (ttl == null || operationCounter.incrementAndGet() % CLEANUP_INTERVAL != 0) {
            return;
        }

        Instant cutoff = Instant.now().minus(ttl.multipliedBy(4));
        try (var files = Files.list(namespacePath)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                try {
                    FileTime lastModifiedTime = Files.getLastModifiedTime(path);
                    if (lastModifiedTime.toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(path);
                    }
                } catch (IOException ex) {
                    LOG.debug("Skipping stale claim cleanup for {}: {}", path, ex.getMessage());
                }
            });
        }
    }

    private boolean isExpired(Path claimPath, Duration ttl) {
        if (ttl == null || !Files.exists(claimPath)) {
            return false;
        }

        try {
            FileTime lastModifiedTime = Files.getLastModifiedTime(claimPath);
            return lastModifiedTime.toInstant().isBefore(Instant.now().minus(ttl));
        } catch (IOException ex) {
            LOG.debug("Could not inspect claim {}: {}", claimPath, ex.getMessage());
            return false;
        }
    }

    private String sanitizeNamespace(String namespace) {
        return namespace.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String hashKey(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash claim key", ex);
        }
    }
}
