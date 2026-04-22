package dev.saseq.primobot.sales;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class SalesReportConfigStore {
    private static final Logger LOG = LoggerFactory.getLogger(SalesReportConfigStore.class);
    private static final Pattern SNOWFLAKE_PATTERN = Pattern.compile("\\d+");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path configPath;
    private final boolean defaultEnabled;
    private final String defaultTimezone;
    private final String defaultTimesRaw;
    private final String defaultTargetChannelId;
    private final String defaultTone;
    private final String defaultSignature;

    private SalesReportConfig currentConfig;

    public SalesReportConfigStore(
            @Value("${SALES_REPORT_CONFIG_PATH:/data/sales-report-config.json}") String configPath,
            @Value("${SALES_REPORT_DEFAULT_ENABLED:true}") boolean defaultEnabled,
            @Value("${SALES_REPORT_DEFAULT_TIMEZONE:Asia/Manila}") String defaultTimezone,
            @Value("${SALES_REPORT_DEFAULT_TIMES:09:00,12:00,15:00,18:00,21:00}") String defaultTimesRaw,
            @Value("${SALES_REPORT_DEFAULT_TARGET_CHANNEL_ID:}") String defaultTargetChannelId,
            @Value("${SALES_REPORT_DEFAULT_TONE:casual}") String defaultTone,
            @Value("${SALES_REPORT_DEFAULT_SIGNATURE:Thanks, Primo}") String defaultSignature) {
        this.configPath = Path.of(configPath);
        this.defaultEnabled = defaultEnabled;
        this.defaultTimezone = defaultTimezone == null ? "Asia/Manila" : defaultTimezone.trim();
        this.defaultTimesRaw = defaultTimesRaw == null ? "" : defaultTimesRaw.trim();
        this.defaultTargetChannelId = defaultTargetChannelId == null ? "" : defaultTargetChannelId.trim();
        this.defaultTone = defaultTone == null ? "casual" : defaultTone.trim();
        this.defaultSignature = defaultSignature == null ? "Thanks, Primo" : defaultSignature.trim();
    }

    @PostConstruct
    public void initialize() {
        synchronized (this) {
            try {
                ensureParentDirectory();
                if (Files.exists(configPath)) {
                    currentConfig = normalize(objectMapper.readValue(configPath.toFile(), SalesReportConfig.class));
                } else {
                    currentConfig = buildDefaultConfig();
                    writeConfig(currentConfig);
                }
            } catch (Exception ex) {
                LOG.error("Failed to initialize sales report config from {}: {}", configPath, ex.getMessage());
                currentConfig = buildDefaultConfig();
            }
        }
    }

    public synchronized SalesReportConfig getSnapshot() {
        return deepCopy(currentConfig);
    }

    public synchronized SalesReportConfig replaceAndPersist(SalesReportConfig updatedConfig) {
        SalesReportConfig normalized = normalize(updatedConfig);
        currentConfig = normalized;
        writeConfig(normalized);
        return deepCopy(normalized);
    }

    private SalesReportConfig buildDefaultConfig() {
        SalesReportConfig config = new SalesReportConfig();
        config.setEnabled(defaultEnabled);
        config.setTimezone(isValidTimezone(defaultTimezone) ? defaultTimezone : "Asia/Manila");
        config.setTimes(parseTimes(defaultTimesRaw));
        config.setTargetChannelId(isSnowflake(defaultTargetChannelId) ? defaultTargetChannelId : "");
        config.setMessageTone(normalizeTone(defaultTone));
        config.setSignature(defaultSignature.isBlank() ? "Thanks, Primo" : defaultSignature);
        config.setAccounts(new ArrayList<>());
        config.setLastRunDateBySlot(new LinkedHashMap<>());
        return config;
    }

    private SalesReportConfig normalize(SalesReportConfig source) {
        SalesReportConfig config = source == null ? new SalesReportConfig() : source;

        if (!isValidTimezone(config.getTimezone())) {
            config.setTimezone("Asia/Manila");
        }

        List<String> parsedTimes = normalizeTimes(config.getTimes());
        config.setTimes(parsedTimes);

        String targetChannelId = safeTrim(config.getTargetChannelId());
        config.setTargetChannelId(isSnowflake(targetChannelId) ? targetChannelId : "");

        config.setMessageTone(normalizeTone(config.getMessageTone()));

        String signature = safeTrim(config.getSignature());
        config.setSignature(signature.isBlank() ? "Thanks, Primo" : signature);

        config.setAccounts(normalizeAccounts(config.getAccounts()));

        Map<String, String> lastRunBySlot = config.getLastRunDateBySlot();
        if (lastRunBySlot == null) {
            config.setLastRunDateBySlot(new LinkedHashMap<>());
        } else {
            config.setLastRunDateBySlot(new LinkedHashMap<>(lastRunBySlot));
        }

        if (!config.getTimes().isEmpty()) {
            config.getLastRunDateBySlot().entrySet().removeIf(entry -> !config.getTimes().contains(entry.getKey()));
        }

        return config;
    }

    private List<SalesAccountConfig> normalizeAccounts(List<SalesAccountConfig> source) {
        if (source == null) {
            return new ArrayList<>();
        }

        List<SalesAccountConfig> normalized = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        for (SalesAccountConfig account : source) {
            if (account == null) {
                continue;
            }

            String id = safeTrim(account.getId());
            if (id.isBlank() || seenIds.contains(id)) {
                continue;
            }

            SalesPlatform platform = account.resolvePlatform();
            if (platform == null) {
                continue;
            }

            SalesAccountConfig copy = new SalesAccountConfig();
            copy.setId(id);
            copy.setPlatform(platform.name());
            copy.setName(defaultIfBlank(safeTrim(account.getName()), id));
            copy.setEnabled(account.isEnabled());
            copy.setUsername(safeTrim(account.getUsername()));
            copy.setPassword(safeTrim(account.getPassword()));
            copy.setToken(safeTrim(account.getToken()));
            copy.setBaseUrl(safeTrim(account.getBaseUrl()));
            copy.setSalesPageUrl(safeTrim(account.getSalesPageUrl()));

            normalized.add(copy);
            seenIds.add(id);
        }

        normalized.sort(Comparator.comparing(SalesAccountConfig::getId, String.CASE_INSENSITIVE_ORDER));
        return normalized;
    }

    private List<String> parseTimes(String rawTimes) {
        if (rawTimes == null || rawTimes.isBlank()) {
            return new ArrayList<>();
        }
        String[] parts = rawTimes.split(",");
        List<String> candidateTimes = new ArrayList<>();
        for (String part : parts) {
            candidateTimes.add(part == null ? "" : part.trim());
        }
        return normalizeTimes(candidateTimes);
    }

    private List<String> normalizeTimes(List<String> sourceTimes) {
        List<LocalTime> parsed = new ArrayList<>();
        if (sourceTimes != null) {
            for (String raw : sourceTimes) {
                LocalTime parsedTime = parseTime(raw);
                if (parsedTime != null) {
                    parsed.add(parsedTime);
                }
            }
        }

        parsed.sort(Comparator.naturalOrder());
        List<String> normalized = new ArrayList<>();
        for (LocalTime time : parsed) {
            String formatted = time.format(TIME_FORMATTER);
            if (!normalized.contains(formatted)) {
                normalized.add(formatted);
            }
        }
        return normalized;
    }

    private LocalTime parseTime(String rawTime) {
        if (rawTime == null || rawTime.isBlank()) {
            return null;
        }
        String value = rawTime.trim();

        if (value.matches("^\\d{1,2}:\\d{2}$")) {
            int hour = Integer.parseInt(value.substring(0, value.indexOf(':')));
            int minute = Integer.parseInt(value.substring(value.indexOf(':') + 1));
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                return null;
            }
            return LocalTime.of(hour, minute);
        }

        try {
            return LocalTime.parse(value, TIME_FORMATTER);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private void writeConfig(SalesReportConfig config) {
        try {
            ensureParentDirectory();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), config);
        } catch (IOException ex) {
            LOG.error("Failed writing sales report config to {}: {}", configPath, ex.getMessage());
        }
    }

    private void ensureParentDirectory() throws IOException {
        Path parent = configPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private SalesReportConfig deepCopy(SalesReportConfig source) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(source);
            return objectMapper.readValue(bytes, SalesReportConfig.class);
        } catch (IOException ex) {
            LOG.warn("Falling back to shallow copy for sales report config: {}", ex.getMessage());
            SalesReportConfig fallback = new SalesReportConfig();
            fallback.setEnabled(source.isEnabled());
            fallback.setTimezone(source.getTimezone());
            fallback.setTimes(new ArrayList<>(source.getTimes()));
            fallback.setTargetChannelId(source.getTargetChannelId());
            fallback.setMessageTone(source.getMessageTone());
            fallback.setSignature(source.getSignature());
            fallback.setAccounts(new ArrayList<>(source.getAccounts()));
            fallback.setLastRunDateBySlot(new LinkedHashMap<>(source.getLastRunDateBySlot()));
            return fallback;
        }
    }

    private String normalizeTone(String rawTone) {
        if (rawTone == null || rawTone.isBlank()) {
            return "casual";
        }
        String tone = rawTone.trim().toLowerCase(Locale.ENGLISH);
        if (!"casual".equals(tone) && !"formal".equals(tone)) {
            return "casual";
        }
        return tone;
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

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
