package dev.saseq.primobot.sales;

import dev.saseq.primobot.util.DiscordMessageUtils;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

@Component
public class SalesReportExecutorService {
    private static final int DISCORD_MESSAGE_MAX_LENGTH = 2000;

    private final SalesAggregatorService aggregatorService;
    private final SalesReportMessageBuilder messageBuilder;

    public SalesReportExecutorService(SalesAggregatorService aggregatorService,
                                      SalesReportMessageBuilder messageBuilder) {
        this.aggregatorService = aggregatorService;
        this.messageBuilder = messageBuilder;
    }

    public DispatchResult execute(Guild guild, SalesReportConfig config, String overrideTargetChannelId) {
        if (guild == null) {
            return new DispatchResult(DispatchStatus.GUILD_NOT_FOUND, "", "No guild available", 0, 0);
        }

        String targetId = resolveTargetChannelId(config, overrideTargetChannelId);
        if (targetId.isBlank()) {
            return new DispatchResult(DispatchStatus.TARGET_NOT_CONFIGURED, "", "No target channel configured", 0, 0);
        }

        TextChannel target = guild.getTextChannelById(targetId);
        if (target == null) {
            return new DispatchResult(DispatchStatus.TARGET_NOT_FOUND, targetId, "Target channel not found", 0, 0);
        }

        ZoneId zoneId = resolveZoneId(config == null ? null : config.getTimezone());
        SalesReportSnapshot snapshot = aggregatorService.aggregate(config, zoneId);
        String content = messageBuilder.buildMessage(
                snapshot,
                config == null ? "casual" : config.getMessageTone(),
                config == null ? "Thanks, Primo" : config.getSignature());

        try {
            for (String chunk : DiscordMessageUtils.chunkMessage(content, DISCORD_MESSAGE_MAX_LENGTH)) {
                target.sendMessage(chunk).complete();
            }

            int successes = (int) snapshot.accountResults().stream().filter(SalesAccountResult::success).count();
            int failures = (int) snapshot.accountResults().stream().filter(result -> !result.success()).count();
            return new DispatchResult(DispatchStatus.SENT, targetId, "Sent", successes, failures);
        } catch (Exception ex) {
            return new DispatchResult(DispatchStatus.SEND_FAILED, targetId, ex.getMessage(), 0, 0);
        }
    }

    private String resolveTargetChannelId(SalesReportConfig config, String overrideTargetChannelId) {
        String override = overrideTargetChannelId == null ? "" : overrideTargetChannelId.trim();
        if (!override.isBlank()) {
            return override;
        }
        if (config == null || config.getTargetChannelId() == null) {
            return "";
        }
        return config.getTargetChannelId().trim();
    }

    private ZoneId resolveZoneId(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (Exception ignored) {
            return ZoneId.of("Asia/Manila");
        }
    }

    public enum DispatchStatus {
        SENT,
        GUILD_NOT_FOUND,
        TARGET_NOT_CONFIGURED,
        TARGET_NOT_FOUND,
        SEND_FAILED
    }

    public record DispatchResult(DispatchStatus status,
                                 String targetChannelId,
                                 String message,
                                 int successCount,
                                 int failureCount) {
        public boolean sent() {
            return status == DispatchStatus.SENT;
        }
    }
}
