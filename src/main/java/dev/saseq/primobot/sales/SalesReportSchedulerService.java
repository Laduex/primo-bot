package dev.saseq.primobot.sales;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    private final JDA jda;
    private final SalesReportConfigStore configStore;
    private final SalesReportExecutorService executorService;
    private final String defaultGuildId;

    public SalesReportSchedulerService(JDA jda,
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
        if (!config.isEnabled() || config.getTimes() == null || config.getTimes().isEmpty()) {
            return;
        }

        ZoneId zoneId = resolveZoneId(config.getTimezone());
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        String slot = now.toLocalTime().format(SLOT_FORMATTER);
        if (!config.getTimes().contains(slot)) {
            return;
        }

        LocalDate today = now.toLocalDate();
        String todayText = today.toString();
        if (todayText.equals(config.getLastRunDateBySlot().get(slot))) {
            return;
        }

        Guild guild = resolveGuild();
        if (guild == null) {
            LOG.warn("Sales report skipped: no guild available.");
            return;
        }

        SalesReportExecutorService.DispatchResult result = executorService.execute(guild, config, null);
        if (result.sent()) {
            config.getLastRunDateBySlot().put(slot, todayText);
            configStore.replaceAndPersist(config);
            LOG.info("Posted scheduled sales report for slot {} to channel {} ({} success, {} failed).",
                    slot,
                    result.targetChannelId(),
                    result.successCount(),
                    result.failureCount());
        } else {
            LOG.warn("Scheduled sales report failed for slot {}: {}", slot, result.message());
        }
    }

    public SalesReportExecutorService.DispatchResult runNow(Guild guild, String overrideTargetChannelId) {
        SalesReportConfig config = configStore.getSnapshot();
        return executorService.execute(guild, config, overrideTargetChannelId);
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
