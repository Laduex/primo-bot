package dev.saseq.primobot.meta;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

@Component
public class MetaUnreadConfigStore {
    private static final Logger LOG = LoggerFactory.getLogger(MetaUnreadConfigStore.class);
    private static final Pattern SNOWFLAKE_PATTERN = Pattern.compile("\\d+");
    private static final int MIN_INTERVAL_MINUTES = 5;
    private static final int MAX_INTERVAL_MINUTES = 60;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path configPath;
    private final boolean defaultEnabled;
    private final int defaultIntervalMinutes;
    private final String defaultTargetChannelId;

    private MetaUnreadConfig currentConfig;

    public MetaUnreadConfigStore(
            @Value("${META_UNREAD_CONFIG_PATH:/data/meta-unread-config.json}") String configPath,
            @Value("${META_UNREAD_DEFAULT_ENABLED:false}") boolean defaultEnabled,
            @Value("${META_UNREAD_DEFAULT_INTERVAL_MINUTES:15}") int defaultIntervalMinutes,
            @Value("${META_UNREAD_DEFAULT_TARGET_CHANNEL_ID:}") String defaultTargetChannelId) {
        this.configPath = Path.of(configPath);
        this.defaultEnabled = defaultEnabled;
        this.defaultIntervalMinutes = clampInterval(defaultIntervalMinutes);
        this.defaultTargetChannelId = defaultTargetChannelId == null ? "" : defaultTargetChannelId.trim();
    }

    @PostConstruct
    public void initialize() {
        synchronized (this) {
            try {
                ensureParentDirectory();
                if (Files.exists(configPath)) {
                    currentConfig = normalize(objectMapper.readValue(configPath.toFile(), MetaUnreadConfig.class));
                } else {
                    currentConfig = buildDefaultConfig();
                    writeConfig(currentConfig);
                }
            } catch (Exception ex) {
                LOG.error("Failed to initialize meta unread config from {}: {}", configPath, ex.getMessage());
                currentConfig = buildDefaultConfig();
            }
        }
    }

    public synchronized MetaUnreadConfig getSnapshot() {
        return deepCopy(currentConfig);
    }

    public synchronized MetaUnreadConfig replaceAndPersist(MetaUnreadConfig updatedConfig) {
        MetaUnreadConfig normalized = normalize(updatedConfig);
        currentConfig = normalized;
        writeConfig(normalized);
        return deepCopy(normalized);
    }

    private MetaUnreadConfig buildDefaultConfig() {
        MetaUnreadConfig config = new MetaUnreadConfig();
        config.setEnabled(defaultEnabled);
        config.setIntervalMinutes(defaultIntervalMinutes);
        config.setTargetChannelId(isSnowflake(defaultTargetChannelId) ? defaultTargetChannelId : "");
        config.setLastRunAtEpochMs(0L);
        return config;
    }

    private MetaUnreadConfig normalize(MetaUnreadConfig source) {
        MetaUnreadConfig config = source == null ? new MetaUnreadConfig() : source;
        config.setIntervalMinutes(clampInterval(config.getIntervalMinutes()));

        String targetChannelId = config.getTargetChannelId() == null ? "" : config.getTargetChannelId().trim();
        config.setTargetChannelId(isSnowflake(targetChannelId) ? targetChannelId : "");

        if (config.getLastRunAtEpochMs() < 0L) {
            config.setLastRunAtEpochMs(0L);
        }
        return config;
    }

    private int clampInterval(int minutes) {
        return Math.max(MIN_INTERVAL_MINUTES, Math.min(MAX_INTERVAL_MINUTES, minutes));
    }

    private void writeConfig(MetaUnreadConfig config) {
        try {
            ensureParentDirectory();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), config);
        } catch (IOException ex) {
            LOG.error("Failed writing meta unread config to {}: {}", configPath, ex.getMessage());
        }
    }

    private void ensureParentDirectory() throws IOException {
        Path parent = configPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private MetaUnreadConfig deepCopy(MetaUnreadConfig source) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(source);
            return objectMapper.readValue(bytes, MetaUnreadConfig.class);
        } catch (IOException ex) {
            LOG.warn("Falling back to shallow copy for meta unread config: {}", ex.getMessage());
            MetaUnreadConfig fallback = new MetaUnreadConfig();
            fallback.setEnabled(source.isEnabled());
            fallback.setTargetChannelId(source.getTargetChannelId());
            fallback.setIntervalMinutes(source.getIntervalMinutes());
            fallback.setLastRunAtEpochMs(source.getLastRunAtEpochMs());
            return fallback;
        }
    }

    private boolean isSnowflake(String value) {
        return value != null && SNOWFLAKE_PATTERN.matcher(value.trim()).matches();
    }
}
