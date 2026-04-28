package dev.saseq.primobot.meta;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MetaUnreadSchedulerService {
    private static final Logger LOG = LoggerFactory.getLogger(MetaUnreadSchedulerService.class);

    private final JDA jda;
    private final MetaUnreadConfigStore configStore;
    private final MetaUnreadExecutorService executorService;
    private final String defaultGuildId;

    public MetaUnreadSchedulerService(@Lazy JDA jda,
                                      MetaUnreadConfigStore configStore,
                                      MetaUnreadExecutorService executorService,
                                      @Value("${DISCORD_GUILD_ID:}") String defaultGuildId) {
        this.jda = jda;
        this.configStore = configStore;
        this.executorService = executorService;
        this.defaultGuildId = defaultGuildId == null ? "" : defaultGuildId.trim();
    }

    @Scheduled(fixedDelayString = "${META_UNREAD_TICK_MS:60000}")
    public void runMetaUnreadTick() {
        MetaUnreadConfig config = configStore.getSnapshot();
        if (!config.isEnabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (!isDue(config, now)) {
            return;
        }

        Guild guild = resolveGuild();
        MetaUnreadExecutorService.DispatchResult result = executorService.execute(guild, config);

        config.setLastRunAtEpochMs(now);
        configStore.replaceAndPersist(config);
        logResult("scheduled", result);
    }

    public MetaUnreadExecutorService.DispatchResult runNow(Guild guild) {
        MetaUnreadConfig config = configStore.getSnapshot();
        MetaUnreadExecutorService.DispatchResult result = executorService.execute(guild, config);
        config.setLastRunAtEpochMs(System.currentTimeMillis());
        configStore.replaceAndPersist(config);
        return result;
    }

    boolean isDue(MetaUnreadConfig config, long nowEpochMs) {
        if (config == null) {
            return false;
        }

        long lastRun = Math.max(0L, config.getLastRunAtEpochMs());
        long intervalMinutes = Math.max(5L, config.getIntervalMinutes());
        long intervalMs = intervalMinutes * 60_000L;

        if (lastRun <= 0L) {
            return true;
        }
        return nowEpochMs - lastRun >= intervalMs;
    }

    private Guild resolveGuild() {
        if (!defaultGuildId.isBlank()) {
            return jda.getGuildById(defaultGuildId);
        }
        return jda.getGuilds().isEmpty() ? null : jda.getGuilds().get(0);
    }

    private void logResult(String source, MetaUnreadExecutorService.DispatchResult result) {
        if (result == null) {
            LOG.warn("Meta unread {} run returned null result.", source);
            return;
        }

        switch (result.status()) {
            case SENT -> LOG.info(
                    "Meta unread {} digest sent to channel {} (pages={}, unreadThreads={}, unreadMessages={}, warnings={}).",
                    source,
                    result.targetChannelId(),
                    result.pagesScanned(),
                    result.unreadThreads(),
                    result.unreadMessages(),
                    result.warningCount());
            case NO_UNREAD -> LOG.info(
                    "Meta unread {} check found no unread conversations (pages={}, warnings={}).",
                    source,
                    result.pagesScanned(),
                    result.warningCount());
            default -> LOG.warn("Meta unread {} check did not send digest: {}", source, result.message());
        }
    }
}
