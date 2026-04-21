package dev.saseq.primobot.reminders;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class OrdersReminderConfigStore {
    private static final Logger LOG = LoggerFactory.getLogger(OrdersReminderConfigStore.class);
    private static final Pattern SNOWFLAKE_PATTERN = Pattern.compile("\\d+");
    private static final String FALLBACK_DEFAULT_ROUTES =
            "1494503996357087443:1494175620287041536:1494215754084651089;" +
            "1495586918589661384:1494175557208899736:1494215769347854356;" +
            "1495586749802610708:1494175305743601755:1494215727857532980;" +
            "1495586880983531680:1494175215071006730:1494215689165213818";

    private final ObjectMapper objectMapper;
    private final Path configPath;
    private final boolean defaultEnabled;
    private final int defaultHour;
    private final int defaultMinute;
    private final String defaultTimezone;
    private final String defaultRoutesRaw;

    private OrdersReminderConfig currentConfig;

    public OrdersReminderConfigStore(
            ObjectMapper objectMapper,
            @Value("${ORDER_REMINDER_CONFIG_PATH:/data/orders-reminder-config.json}") String configPath,
            @Value("${ORDER_REMINDER_DEFAULT_ENABLED:true}") boolean defaultEnabled,
            @Value("${ORDER_REMINDER_DEFAULT_TIME:08:00}") String defaultTime,
            @Value("${ORDER_REMINDER_DEFAULT_TIMEZONE:Asia/Manila}") String defaultTimezone,
            @Value("${ORDER_REMINDER_DEFAULT_ROUTES:" + FALLBACK_DEFAULT_ROUTES + "}") String defaultRoutesRaw) {
        this.objectMapper = objectMapper;
        this.configPath = Path.of(configPath);
        this.defaultEnabled = defaultEnabled;
        this.defaultTimezone = defaultTimezone == null ? "Asia/Manila" : defaultTimezone.trim();
        this.defaultRoutesRaw = defaultRoutesRaw == null ? "" : defaultRoutesRaw.trim();

        int parsedHour = 8;
        int parsedMinute = 0;
        if (defaultTime != null && defaultTime.contains(":")) {
            String[] parts = defaultTime.split(":", 2);
            try {
                parsedHour = Integer.parseInt(parts[0]);
                parsedMinute = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
                LOG.warn("Invalid ORDER_REMINDER_DEFAULT_TIME '{}'. Falling back to 08:00.", defaultTime);
            }
        }
        this.defaultHour = clampHour(parsedHour);
        this.defaultMinute = clampMinute(parsedMinute);
    }

    @PostConstruct
    public void initialize() {
        synchronized (this) {
            try {
                ensureParentDirectory();
                if (Files.exists(configPath)) {
                    currentConfig = normalize(objectMapper.readValue(configPath.toFile(), OrdersReminderConfig.class));
                } else {
                    currentConfig = buildDefaultConfig();
                    writeConfig(currentConfig);
                }
            } catch (Exception ex) {
                LOG.error("Failed to initialize orders reminder config from {}: {}", configPath, ex.getMessage());
                currentConfig = buildDefaultConfig();
            }
        }
    }

    public synchronized OrdersReminderConfig getSnapshot() {
        return deepCopy(currentConfig);
    }

    public synchronized OrdersReminderConfig replaceAndPersist(OrdersReminderConfig updatedConfig) {
        OrdersReminderConfig normalized = normalize(updatedConfig);
        currentConfig = normalized;
        writeConfig(normalized);
        return deepCopy(normalized);
    }

    private OrdersReminderConfig buildDefaultConfig() {
        OrdersReminderConfig config = new OrdersReminderConfig();
        config.setEnabled(defaultEnabled);
        config.setHour(defaultHour);
        config.setMinute(defaultMinute);
        config.setTimezone(isValidTimezone(defaultTimezone) ? defaultTimezone : "Asia/Manila");
        config.setMessageTone("casual");
        config.setSignature("Thanks, Primo");
        config.setRoutes(parseDefaultRoutes(defaultRoutesRaw));
        config.setLastRunDateByRoute(new LinkedHashMap<>());
        return config;
    }

    private OrdersReminderConfig normalize(OrdersReminderConfig source) {
        OrdersReminderConfig config = source == null ? new OrdersReminderConfig() : source;

        if (!isValidTimezone(config.getTimezone())) {
            config.setTimezone("Asia/Manila");
        }
        config.setHour(clampHour(config.getHour()));
        config.setMinute(clampMinute(config.getMinute()));

        String tone = config.getMessageTone();
        if (tone == null || tone.isBlank()) {
            config.setMessageTone("casual");
        } else {
            config.setMessageTone(tone.trim().toLowerCase(Locale.ENGLISH));
        }

        String signature = config.getSignature();
        if (signature == null || signature.isBlank()) {
            config.setSignature("Thanks, Primo");
        } else {
            config.setSignature(signature.trim());
        }

        if (config.getRoutes() == null) {
            config.setRoutes(new ArrayList<>());
        }
        List<OrdersReminderRoute> validRoutes = new ArrayList<>();
        for (OrdersReminderRoute route : config.getRoutes()) {
            if (route == null) {
                continue;
            }
            if (!isSnowflake(route.getForumId())
                    || !isSnowflake(route.getTargetTextChannelId())
                    || !isSnowflake(route.getMentionRoleId())) {
                continue;
            }
            validRoutes.add(new OrdersReminderRoute(
                    route.getForumId().trim(),
                    route.getTargetTextChannelId().trim(),
                    route.getMentionRoleId().trim()));
        }
        config.setRoutes(validRoutes);

        if (config.getLastRunDateByRoute() == null) {
            config.setLastRunDateByRoute(new LinkedHashMap<>());
        } else {
            config.setLastRunDateByRoute(new LinkedHashMap<>(config.getLastRunDateByRoute()));
        }

        return config;
    }

    private List<OrdersReminderRoute> parseDefaultRoutes(String rawRoutes) {
        List<OrdersReminderRoute> routes = new ArrayList<>();
        if (rawRoutes == null || rawRoutes.isBlank()) {
            return routes;
        }

        for (String rawEntry : rawRoutes.split(";")) {
            String entry = rawEntry == null ? "" : rawEntry.trim();
            if (entry.isEmpty()) {
                continue;
            }

            String[] parts = entry.split(":", 3);
            if (parts.length != 3) {
                LOG.warn("Ignoring invalid ORDER_REMINDER_DEFAULT_ROUTES entry '{}'.", entry);
                continue;
            }

            String forumId = parts[0].trim();
            String channelId = parts[1].trim();
            String roleId = parts[2].trim();
            if (!isSnowflake(forumId) || !isSnowflake(channelId) || !isSnowflake(roleId)) {
                LOG.warn("Ignoring invalid ORDER_REMINDER_DEFAULT_ROUTES entry '{}'.", entry);
                continue;
            }

            routes.add(new OrdersReminderRoute(forumId, channelId, roleId));
        }
        return routes;
    }

    private void writeConfig(OrdersReminderConfig config) {
        try {
            ensureParentDirectory();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), config);
        } catch (IOException ex) {
            LOG.error("Failed writing orders reminder config to {}: {}", configPath, ex.getMessage());
        }
    }

    private void ensureParentDirectory() throws IOException {
        Path parent = configPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private OrdersReminderConfig deepCopy(OrdersReminderConfig source) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(source);
            return objectMapper.readValue(bytes, OrdersReminderConfig.class);
        } catch (IOException ex) {
            LOG.warn("Falling back to shallow copy for orders reminder config: {}", ex.getMessage());
            OrdersReminderConfig fallback = new OrdersReminderConfig();
            fallback.setEnabled(source.isEnabled());
            fallback.setTimezone(source.getTimezone());
            fallback.setHour(source.getHour());
            fallback.setMinute(source.getMinute());
            fallback.setMessageTone(source.getMessageTone());
            fallback.setSignature(source.getSignature());
            fallback.setRoutes(new ArrayList<>(source.getRoutes()));
            fallback.setLastRunDateByRoute(new LinkedHashMap<>(source.getLastRunDateByRoute()));
            return fallback;
        }
    }

    private boolean isValidTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return false;
        }
        try {
            ZoneId.of(timezone.trim());
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isSnowflake(String value) {
        return value != null && SNOWFLAKE_PATTERN.matcher(value.trim()).matches();
    }

    private int clampHour(int hour) {
        return Math.max(0, Math.min(23, hour));
    }

    private int clampMinute(int minute) {
        return Math.max(0, Math.min(59, minute));
    }
}
