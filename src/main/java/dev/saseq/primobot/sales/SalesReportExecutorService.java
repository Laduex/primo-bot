package dev.saseq.primobot.sales;

import dev.saseq.primobot.util.DiscordMessageUtils;
import dev.saseq.primobot.util.CrossProcessClaimStore;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.time.ZoneId;

@Component
public class SalesReportExecutorService {
    private static final int DISCORD_MESSAGE_MAX_LENGTH = 2000;
    private static final int FORUM_POST_TITLE_MAX = 100;
    private static final DateTimeFormatter FORUM_TITLE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String DISPATCH_DEDUPE_NAMESPACE = "sales-dispatch";
    private static final Duration DISPATCH_DEDUPE_TTL = Duration.ofMinutes(3);

    private final SalesAggregatorService aggregatorService;
    private final SalesReportMessageBuilder messageBuilder;
    private final CrossProcessClaimStore claimStore;

    public SalesReportExecutorService(SalesAggregatorService aggregatorService,
                                      SalesReportMessageBuilder messageBuilder,
                                      CrossProcessClaimStore claimStore) {
        this.aggregatorService = aggregatorService;
        this.messageBuilder = messageBuilder;
        this.claimStore = claimStore;
    }

    public DispatchResult execute(Guild guild,
                                  SalesReportConfig config,
                                  String overrideTargetChannelId,
                                  String selectedAccountId,
                                  boolean dailyOverview,
                                  boolean suppressRecentDuplicates) {
        if (guild == null) {
            return new DispatchResult(DispatchStatus.GUILD_NOT_FOUND, "", "", "No guild available", 0, 0);
        }

        String targetId = resolveTargetChannelId(config, overrideTargetChannelId, dailyOverview);
        if (targetId.isBlank()) {
            return new DispatchResult(DispatchStatus.TARGET_NOT_CONFIGURED, "", "", "No target channel configured", 0, 0);
        }

        PreparedDispatch prepared = prepareDispatch(config, selectedAccountId, dailyOverview);
        if (prepared.result() != null) {
            return prepared.result();
        }

        try {
            List<String> chunks = DiscordMessageUtils.chunkMessage(prepared.content(), DISCORD_MESSAGE_MAX_LENGTH);
            String dedupeKey = "";
            if (suppressRecentDuplicates) {
                dedupeKey = buildDispatchDedupeKey(targetId, selectedAccountId, dailyOverview, chunks);
                if (!claimStore.tryClaim(DISPATCH_DEDUPE_NAMESPACE, dedupeKey, DISPATCH_DEDUPE_TTL)) {
                    return new DispatchResult(DispatchStatus.DUPLICATE_SUPPRESSED,
                            targetId,
                            selectedAccountId,
                            "Duplicate scheduled dispatch suppressed",
                            prepared.successCount(),
                            prepared.failureCount());
                }
            }
            TextChannel textTarget = guild.getTextChannelById(targetId);
            if (textTarget != null) {
                if (suppressRecentDuplicates && matchesRecentBotDispatch(textTarget, chunks, guild)) {
                    return new DispatchResult(DispatchStatus.DUPLICATE_SUPPRESSED,
                            targetId,
                            selectedAccountId,
                            "Duplicate scheduled dispatch suppressed",
                            prepared.successCount(),
                            prepared.failureCount());
                }
                for (String chunk : chunks) {
                    textTarget.sendMessage(chunk).complete();
                }
            } else {
                ForumChannel forumTarget = guild.getForumChannelById(targetId);
                if (forumTarget == null) {
                    return new DispatchResult(DispatchStatus.TARGET_NOT_FOUND, targetId, selectedAccountId, "Target channel not found", 0, 0);
                }

                String title = buildForumPostTitle(prepared.zoneId(), selectedAccountId, dailyOverview);
                ThreadChannel thread = forumTarget.createForumPost(title, MessageCreateData.fromContent(chunks.get(0)))
                        .complete()
                        .getThreadChannel();
                for (int index = 1; index < chunks.size(); index++) {
                    thread.sendMessage(chunks.get(index)).complete();
                }
            }

            return new DispatchResult(DispatchStatus.SENT,
                    targetId,
                    selectedAccountId,
                    "Sent",
                    prepared.successCount(),
                    prepared.failureCount());
        } catch (Exception ex) {
            if (suppressRecentDuplicates) {
                claimStore.release(DISPATCH_DEDUPE_NAMESPACE, buildDispatchDedupeKey(targetId, selectedAccountId, dailyOverview, DiscordMessageUtils.chunkMessage(prepared.content(), DISCORD_MESSAGE_MAX_LENGTH)));
            }
            return new DispatchResult(DispatchStatus.SEND_FAILED, targetId, selectedAccountId, ex.getMessage(), 0, 0);
        }
    }

    public DispatchResult executeDirect(SalesReportConfig config,
                                        String selectedAccountId,
                                        MessageChannel destination,
                                        boolean dailyOverview) {
        if (destination == null) {
            return new DispatchResult(DispatchStatus.SEND_FAILED, "", selectedAccountId, "No destination channel", 0, 0);
        }

        PreparedDispatch prepared = prepareDispatch(config, selectedAccountId, dailyOverview);
        if (prepared.result() != null) {
            return prepared.result();
        }

        try {
            List<String> chunks = DiscordMessageUtils.chunkMessage(prepared.content(), DISCORD_MESSAGE_MAX_LENGTH);
            for (String chunk : chunks) {
                destination.sendMessage(chunk).complete();
            }
            return new DispatchResult(DispatchStatus.SENT,
                    destination.getId(),
                    selectedAccountId,
                    "Sent",
                    prepared.successCount(),
                    prepared.failureCount());
        } catch (Exception ex) {
            return new DispatchResult(DispatchStatus.SEND_FAILED, destination.getId(), selectedAccountId, ex.getMessage(), 0, 0);
        }
    }

    private PreparedDispatch prepareDispatch(SalesReportConfig config,
                                             String selectedAccountId,
                                             boolean dailyOverview) {
        SalesReportConfig effectiveConfig = filterConfigByAccount(config, selectedAccountId);
        if (effectiveConfig == null) {
            return new PreparedDispatch(null,
                    0,
                    0,
                    null,
                    new DispatchResult(DispatchStatus.ACCOUNT_NOT_FOUND, "", selectedAccountId, "Account not found", 0, 0));
        }

        ZoneId zoneId = resolveZoneId(effectiveConfig.getTimezone());
        SalesReportSnapshot snapshot = aggregatorService.aggregate(effectiveConfig, zoneId);
        String content = messageBuilder.buildMessage(
                snapshot,
                effectiveConfig.getMessageTone(),
                effectiveConfig.getSignature(),
                dailyOverview);
        int successes = (int) snapshot.accountResults().stream().filter(SalesAccountResult::success).count();
        int failures = (int) snapshot.accountResults().stream().filter(result -> !result.success()).count();
        return new PreparedDispatch(content, successes, failures, zoneId, null);
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

    private String buildDispatchDedupeKey(String targetId,
                                          String selectedAccountId,
                                          boolean dailyOverview,
                                          List<String> chunks) {
        String payload = String.join("\n<chunk>\n", chunks);
        String digest = sha256(payload);
        String reportType = dailyOverview ? "overview" : "update";
        String accountScope = selectedAccountId == null || selectedAccountId.isBlank() ? "all" : selectedAccountId.trim();
        return targetId + "|" + reportType + "|" + accountScope + "|" + digest;
    }

    private boolean matchesRecentBotDispatch(TextChannel channel, List<String> chunks, Guild guild) {
        List<Message> recent = channel.getHistory().retrievePast(Math.max(chunks.size(), 1)).complete();
        if (recent.size() < chunks.size()) {
            return false;
        }

        long botUserId = guild.getSelfMember().getIdLong();
        OffsetDateTime cutoff = OffsetDateTime.now().minus(DISPATCH_DEDUPE_TTL);
        for (int index = 0; index < chunks.size(); index++) {
            Message message = recent.get(index);
            String expected = chunks.get(chunks.size() - 1 - index);
            if (message.getAuthor().getIdLong() != botUserId) {
                return false;
            }
            if (message.getTimeCreated().isBefore(cutoff)) {
                return false;
            }
            if (!expected.equals(message.getContentRaw())) {
                return false;
            }
        }
        return true;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash dispatch payload", ex);
        }
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
        DUPLICATE_SUPPRESSED,
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
            return status == DispatchStatus.SENT || status == DispatchStatus.DUPLICATE_SUPPRESSED;
        }
    }

    private record PreparedDispatch(String content,
                                    int successCount,
                                    int failureCount,
                                    ZoneId zoneId,
                                    DispatchResult result) {
    }
}
