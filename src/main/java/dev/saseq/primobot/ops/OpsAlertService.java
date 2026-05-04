package dev.saseq.primobot.ops;

import dev.saseq.primobot.util.DiscordMessageUtils;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Service
public class OpsAlertService {
    private static final int DISCORD_EMBED_DESCRIPTION_MAX_LENGTH = 3800;
    private static final ZoneId MANILA_ZONE = ZoneId.of("Asia/Manila");
    private static final DateTimeFormatter ALERT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a 'PHT'");

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

        AlertView alert = formatAlert(request);
        int part = 1;
        for (String chunk : DiscordMessageUtils.chunkMessage(alert.message(), DISCORD_EMBED_DESCRIPTION_MAX_LENGTH)) {
            EmbedBuilder embed = new EmbedBuilder()
                    .setColor(alert.color())
                    .setTitle(part == 1 ? alert.title() : alert.title() + " (continued)")
                    .setDescription(chunk)
                    .addField("Severity", alert.severity(), true)
                    .addField("Time", alert.timestamp(), true)
                    .setFooter("Production operations alert");
            target.sendMessageEmbeds(embed.build()).complete();
            part++;
        }

        return new AlertResult(true, "alert sent");
    }

    private AlertView formatAlert(OpsAlertRequest request) {
        String severity = normalizeSeverity(clean(request == null ? "" : request.severity(), "info"));
        String title = clean(request == null ? "" : request.title(), "Production alert");
        String message = formatMessage(clean(request == null ? "" : request.message(), "(no details)"));
        String timestamp = OffsetDateTime.now(MANILA_ZONE).format(ALERT_TIME_FORMAT);

        return new AlertView("[%s] %s".formatted(severity, title), severity, timestamp, message, colorFor(severity));
    }

    private String clean(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private String normalizeSeverity(String severity) {
        return switch (severity.trim().toLowerCase()) {
            case "critical", "fatal" -> "CRITICAL";
            case "error", "failed", "failure" -> "ERROR";
            case "warning", "warn" -> "WARNING";
            case "success", "ok", "complete", "completed" -> "SUCCESS";
            default -> "INFO";
        };
    }

    private Color colorFor(String severity) {
        return switch (severity) {
            case "CRITICAL", "ERROR" -> new Color(196, 43, 28);
            case "WARNING" -> new Color(214, 136, 0);
            case "SUCCESS" -> new Color(35, 134, 54);
            default -> new Color(31, 111, 235);
        };
    }

    private String formatMessage(String message) {
        String normalized = message
                .replace("; roastery:", "\n- roastery:")
                .replace("; finlandia:", "\n- finlandia:")
                .replace("; jimenez:", "\n- jimenez:")
                .replace("; recipe-calculator:", "\n- recipe-calculator:")
                .replace(" bakes:", "\n- bakes:")
                .replace(". Failed apps:", ".\n\nFailed apps:")
                .replace(". Inspect manifests", ".\nInspect manifests");

        if (!normalized.equals(message) || normalized.contains("backup")) {
            return "```text\n" + normalized + "\n```";
        }

        return normalized;
    }

    private record AlertView(String title,
                             String severity,
                             String timestamp,
                             String message,
                             Color color) {
    }

    public record AlertResult(boolean sent, String message) {
    }
}
