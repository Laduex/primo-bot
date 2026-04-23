package dev.saseq.primobot.sales;

import dev.saseq.primobot.util.DiscordMessageUtils;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.time.ZoneId;

@Component
public class SalesReportExecutorService {
    private static final int DISCORD_MESSAGE_MAX_LENGTH = 2000;
    private static final int FORUM_POST_TITLE_MAX = 100;
    private static final DateTimeFormatter FORUM_TITLE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final SalesAggregatorService aggregatorService;
    private final SalesReportMessageBuilder messageBuilder;

    public SalesReportExecutorService(SalesAggregatorService aggregatorService,
                                      SalesReportMessageBuilder messageBuilder) {
        this.aggregatorService = aggregatorService;
        this.messageBuilder = messageBuilder;
    }

    public DispatchResult execute(Guild guild,
                                  SalesReportConfig config,
                                  String overrideTargetChannelId,
                                  String selectedAccountId,
                                  boolean dailyOverview) {
        if (guild == null) {
            return new DispatchResult(DispatchStatus.GUILD_NOT_FOUND, "", "", "No guild available", 0, 0);
        }

        String targetId = resolveTargetChannelId(config, overrideTargetChannelId, dailyOverview);
        if (targetId.isBlank()) {
            return new DispatchResult(DispatchStatus.TARGET_NOT_CONFIGURED, "", "", "No target channel configured", 0, 0);
        }

        SalesReportConfig effectiveConfig = filterConfigByAccount(config, selectedAccountId);
        if (effectiveConfig == null) {
            return new DispatchResult(DispatchStatus.ACCOUNT_NOT_FOUND, targetId, selectedAccountId, "Account not found", 0, 0);
        }

        ZoneId zoneId = resolveZoneId(effectiveConfig.getTimezone());
        SalesReportSnapshot snapshot = aggregatorService.aggregate(effectiveConfig, zoneId);
        String content = messageBuilder.buildMessage(
                snapshot,
                effectiveConfig.getMessageTone(),
                effectiveConfig.getSignature(),
                dailyOverview);

        try {
            List<String> chunks = DiscordMessageUtils.chunkMessage(content, DISCORD_MESSAGE_MAX_LENGTH);
            TextChannel textTarget = guild.getTextChannelById(targetId);
            if (textTarget != null) {
                for (String chunk : chunks) {
                    textTarget.sendMessage(chunk).complete();
                }
            } else {
                ForumChannel forumTarget = guild.getForumChannelById(targetId);
                if (forumTarget == null) {
                    return new DispatchResult(DispatchStatus.TARGET_NOT_FOUND, targetId, selectedAccountId, "Target channel not found", 0, 0);
                }

                String title = buildForumPostTitle(zoneId, selectedAccountId, dailyOverview);
                ThreadChannel thread = forumTarget.createForumPost(title, MessageCreateData.fromContent(chunks.get(0)))
                        .complete()
                        .getThreadChannel();
                for (int index = 1; index < chunks.size(); index++) {
                    thread.sendMessage(chunks.get(index)).complete();
                }
            }

            int successes = (int) snapshot.accountResults().stream().filter(SalesAccountResult::success).count();
            int failures = (int) snapshot.accountResults().stream().filter(result -> !result.success()).count();
            return new DispatchResult(DispatchStatus.SENT, targetId, selectedAccountId, "Sent", successes, failures);
        } catch (Exception ex) {
            return new DispatchResult(DispatchStatus.SEND_FAILED, targetId, selectedAccountId, ex.getMessage(), 0, 0);
        }
    }

    private String resolveTargetChannelId(SalesReportConfig config,
                                          String overrideTargetChannelId,
                                          boolean dailyOverview) {
        String override = overrideTargetChannelId == null ? "" : overrideTargetChannelId.trim();
        if (!override.isBlank()) {
            return override;
        }

        if (config == null) {
            return "";
        }

        String configured = dailyOverview
                ? safeTrim(config.getOverviewTargetChannelId())
                : safeTrim(config.getTargetChannelId());

        if (configured.isBlank() && dailyOverview) {
            configured = safeTrim(config.getTargetChannelId());
        }
        return configured;
    }

    private SalesReportConfig filterConfigByAccount(SalesReportConfig config, String selectedAccountId) {
        if (config == null) {
            return null;
        }

        String requested = selectedAccountId == null ? "" : selectedAccountId.trim();
        if (requested.isBlank()) {
            return config;
        }

        List<SalesAccountConfig> selectedAccounts = new ArrayList<>();
        for (SalesAccountConfig account : config.getAccounts()) {
            if (account != null && requested.equalsIgnoreCase(account.getId())) {
                selectedAccounts.add(account);
            }
        }
        if (selectedAccounts.isEmpty()) {
            return null;
        }

        SalesReportConfig filtered = new SalesReportConfig();
        filtered.setEnabled(config.isEnabled());
        filtered.setTimezone(config.getTimezone());
        filtered.setTimes(config.getTimes());
        filtered.setTargetChannelId(config.getTargetChannelId());
        filtered.setOverviewTime(config.getOverviewTime());
        filtered.setOverviewTargetChannelId(config.getOverviewTargetChannelId());
        filtered.setMessageTone(config.getMessageTone());
        filtered.setSignature(config.getSignature());
        filtered.setLastRunDateBySlot(config.getLastRunDateBySlot());
        filtered.setAccounts(selectedAccounts);
        return filtered;
    }

    private ZoneId resolveZoneId(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (Exception ignored) {
            return ZoneId.of("Asia/Manila");
        }
    }

    private String buildForumPostTitle(ZoneId zoneId, String selectedAccountId, boolean dailyOverview) {
        String timestamp = ZonedDateTime.now(zoneId).format(FORUM_TITLE_TIME_FORMATTER);
        String scope = (selectedAccountId == null || selectedAccountId.isBlank()) ? "All Accounts" : selectedAccountId.trim();
        String reportType = dailyOverview ? "Daily Overview" : "Sales Update";
        String title = reportType + " | " + scope + " | " + timestamp;
        if (title.length() <= FORUM_POST_TITLE_MAX) {
            return title;
        }
        return title.substring(0, FORUM_POST_TITLE_MAX);
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    public enum DispatchStatus {
        SENT,
        GUILD_NOT_FOUND,
        TARGET_NOT_CONFIGURED,
        TARGET_NOT_FOUND,
        ACCOUNT_NOT_FOUND,
        SEND_FAILED
    }

    public record DispatchResult(DispatchStatus status,
                                 String targetChannelId,
                                 String accountId,
                                 String message,
                                 int successCount,
                                 int failureCount) {
        public boolean sent() {
            return status == DispatchStatus.SENT;
        }
    }
}
