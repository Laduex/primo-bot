package dev.saseq.primobot.ops;

import dev.saseq.primobot.util.DiscordMessageUtils;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Service
public class OpsAlertService {
    private static final int DISCORD_MESSAGE_MAX_LENGTH = 2000;

    private final JDA jda;
    private final String targetChannelId;

    public OpsAlertService(JDA jda,
                           @Value("${OPS_ALERT_CHANNEL_ID:}") String targetChannelId) {
        this.jda = jda;
        this.targetChannelId = targetChannelId == null ? "" : targetChannelId.trim();
    }

    public AlertResult sendAlert(OpsAlertRequest request) {
        if (targetChannelId.isBlank()) {
            return new AlertResult(false, "OPS_ALERT_CHANNEL_ID is not configured");
        }

        TextChannel target = jda.getTextChannelById(targetChannelId);
        if (target == null) {
            return new AlertResult(false, "target Discord channel was not found");
        }

        String content = formatAlert(request);
        for (String chunk : DiscordMessageUtils.chunkMessage(content, DISCORD_MESSAGE_MAX_LENGTH)) {
            target.sendMessage(chunk).complete();
        }

        return new AlertResult(true, "alert sent");
    }

    private String formatAlert(OpsAlertRequest request) {
        String severity = clean(request == null ? "" : request.severity(), "info").toUpperCase();
        String title = clean(request == null ? "" : request.title(), "Production alert");
        String message = clean(request == null ? "" : request.message(), "(no details)");
        String timestamp = OffsetDateTime.now(ZoneId.of("Asia/Manila"))
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        return """
                **[%s] %s**
                Time: `%s`

                %s
                """.formatted(severity, title, timestamp, message);
    }

    private String clean(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    public record AlertResult(boolean sent, String message) {
    }
}
