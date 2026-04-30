package dev.saseq.primobot.sales;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class SalesReportSchedulerService {
    private static final Logger LOG = LoggerFactory.getLogger(SalesReportSchedulerService.class);
    private static final DateTimeFormatter SLOT_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final String UPDATE_SLOT_KEY_PREFIX = "update:";
    private static final String OVERVIEW_SLOT_KEY_PREFIX = "overview:";

    private final JDA jda;
    private final SalesReportConfigStore configStore;
    private final SalesReportExecutorService executorService;
    private final String defaultGuildId;

    public SalesReportSchedulerService(@Lazy JDA jda,
                                       SalesReportConfigStore configStore,
                                       SalesReportExecutorService executorService,
                                       @Value("${DISCORD_GUILD_ID:}") String defaultGuildId) {
        this.jda = jda;
        this.configStore = configStore;
        this.executorService = executorService;
        this.defaultGuildId = defaultGuildId == null ? "" : defaultGuildId.trim();
    }

    @Scheduled(fixedDelayString = "${SALES_REPORT_TICK_MS:60000}")
    public void runSalesTick() {
        SalesReportConfig config = configStore.getSnapshot();
        boolean hasUpdateSchedule = config.getTimes() != null && !config.getTimes().isEmpty();
        boolean hasOverviewSchedule = config.getOverviewTime() != null && !config.getOverviewTime().isBlank();
        if (!config.isEnabled() || (!hasUpdateSchedule && !hasOverviewSchedule)) {
            return;
        }

        ZoneId zoneId = resolveZoneId(config.getTimezone());
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        String slot = now.toLocalTime().format(SLOT_FORMATTER);

        LocalDate today = now.toLocalDate();
        String todayText = today.toString();

        Guild guild = resolveGuild();
        if (guild == null) {
            LOG.warn("Sales report skipped: no guild available.");
            return;
        }

        String overviewSlot = normalizeSlot(config.getOverviewTime());
        if (!overviewSlot.isBlank()
                && overviewSlot.equals(slot)
                && !alreadyRanToday(config, slotKey(true, slot), slot, todayText)) {
            dispatchScheduled(guild, config, slot, todayText, true);
            return;
        }

        if (config.getTimes() != null
                && config.getTimes().contains(slot)
                && !slot.equals(overviewSlot)
                && !alreadyRanToday(config, slotKey(false, slot), slot, todayText)) {
            dispatchScheduled(guild, config, slot, todayText, false);
        }
    }

    public SalesReportExecutorService.DispatchResult runNow(Guild guild,
                                                            String overrideTargetChannelId,
                                                            String selectedAccountId) {
        SalesReportConfig config = configStore.getSnapshot();
        return executorService.execute(guild, config, overrideTargetChannelId, selectedAccountId, false);
    }

    private void dispatchScheduled(Guild guild,
                                   SalesReportConfig config,
                                   String slot,
                                   String todayText,
                                   boolean dailyOverview) {
        String slotKey = slotKey(dailyOverview, slot);
        config.getLastRunDateBySlot().put(slotKey, todayText);
        configStore.replaceAndPersist(config);

        SalesReportExecutorService.DispatchResult result =
                executorService.execute(guild, config, null, null, dailyOverview);
        if (result.sent()) {
            if (dailyOverview) {
                LOG.info("Posted scheduled daily sales overview for slot {} to channel {} ({} success, {} failed).",
                        slot,
                        result.targetChannelId(),
                        result.successCount(),
                        result.failureCount());
            } else {
                LOG.info("Posted scheduled sales update for slot {} to channel {} ({} success, {} failed).",
                        slot,
                        result.targetChannelId(),
                        result.successCount(),
                        result.failureCount());
            }
        } else {
            config.getLastRunDateBySlot().remove(slotKey);
            configStore.replaceAndPersist(config);
            if (dailyOverview) {
                LOG.warn("Scheduled daily sales overview failed for slot {}: {}", slot, result.message());
            } else {
                LOG.warn("Scheduled sales update failed for slot {}: {}", slot, result.message());
            }
        }
    }

    private boolean alreadyRanToday(SalesReportConfig config, String key, String legacySlot, String todayText) {
        String exact = config.getLastRunDateBySlot().get(key);
        if (todayText.equals(exact)) {
            return true;
        }
        String legacy = config.getLastRunDateBySlot().get(legacySlot);
        return todayText.equals(legacy);
    }

    private String slotKey(boolean dailyOverview, String slot) {
        return (dailyOverview ? OVERVIEW_SLOT_KEY_PREFIX : UPDATE_SLOT_KEY_PREFIX) + slot;
    }

    private String normalizeSlot(String rawSlot) {
        if (rawSlot == null) {
            return "";
        }
        String trimmed = rawSlot.trim();
        if (trimmed.matches("^\\d{1,2}:\\d{2}$")) {
            String[] parts = trimmed.split(":");
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                return "";
            }
            return String.format("%02d:%02d", hour, minute);
        }
        return "";
    }

    private Guild resolveGuild() {
        if (!defaultGuildId.isBlank()) {
            return jda.getGuildById(defaultGuildId);
        }
        return jda.getGuilds().isEmpty() ? null : jda.getGuilds().get(0);
    }

    private ZoneId resolveZoneId(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (Exception ignored) {
            return ZoneId.of("Asia/Manila");
        }
    }
}
