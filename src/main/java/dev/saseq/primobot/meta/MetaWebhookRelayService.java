package dev.saseq.primobot.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.concrete.NewsChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class MetaWebhookRelayService {
    private static final Logger LOG = LoggerFactory.getLogger(MetaWebhookRelayService.class);
    private static final int FORUM_POST_TITLE_MAX_LENGTH = 100;
    private static final DateTimeFormatter TITLE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String SIGNATURE_PREFIX = "sha256=";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JDA jda;
    private final MetaWebhookEventExtractor eventExtractor;
    private final String defaultGuildId;
    private final String verifyToken;
    private final String fallbackTargetChannelId;
    private final String appSecret;

    public MetaWebhookRelayService(@Lazy JDA jda,
                                   MetaWebhookEventExtractor eventExtractor,
                                   @Value("${DISCORD_GUILD_ID:}") String defaultGuildId,
                                   @Value("${META_WEBHOOK_VERIFY_TOKEN:}") String verifyToken,
                                   @Value("${META_WEBHOOK_TARGET_CHANNEL_ID:}") String fallbackTargetChannelId,
                                   @Value("${META_APP_SECRET:}") String appSecret) {
        this.jda = jda;
        this.eventExtractor = eventExtractor;
        this.defaultGuildId = safeTrim(defaultGuildId);
        this.verifyToken = safeTrim(verifyToken);
        this.fallbackTargetChannelId = safeTrim(fallbackTargetChannelId);
        this.appSecret = safeTrim(appSecret);
    }

    public boolean isVerificationRequestValid(String mode, String token) {
        return "subscribe".equalsIgnoreCase(safeTrim(mode))
                && !verifyToken.isBlank()
                && verifyToken.equals(token == null ? "" : token.trim());
    }

    public boolean isSignatureValid(String rawBody, String headerSignature) {
        if (appSecret.isBlank()) {
            return true;
        }

        String signature = safeTrim(headerSignature);
        if (!signature.startsWith(SIGNATURE_PREFIX)) {
            return false;
        }

        String providedHex = signature.substring(SIGNATURE_PREFIX.length());
        if (providedHex.isBlank()) {
            return false;
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((rawBody == null ? "" : rawBody).getBytes(StandardCharsets.UTF_8));
            String expectedHex = toHex(digest);
            return constantTimeEquals(expectedHex, providedHex.toLowerCase());
        } catch (Exception ex) {
            LOG.warn("Failed to validate Meta webhook signature: {}", ex.getMessage());
            return false;
        }
    }

    public int relayInboundChatPayload(String rawBody) {
        JsonNode payload;
        try {
            payload = JSON.readTree(rawBody == null ? "{}" : rawBody);
        } catch (Exception ex) {
            LOG.warn("Ignoring invalid Meta webhook payload: {}", ex.getMessage());
            return 0;
        }

        List<MetaInboundChatEvent> events = eventExtractor.extractInboundChatEvents(payload);
        if (events.isEmpty()) {
            return 0;
        }

        Guild guild = resolveGuild();
        if (guild == null) {
            LOG.warn("Skipping Meta webhook relay: no Discord guild is available.");
            return 0;
        }

        String targetChannelId = resolveTargetChannelId();
        if (targetChannelId.isBlank()) {
            LOG.warn("Skipping Meta webhook relay: target channel is not configured.");
            return 0;
        }

        int relayed = 0;
        for (MetaInboundChatEvent event : events) {
            try {
                if (sendToTarget(guild, targetChannelId, formatEventMessage(event))) {
                    relayed++;
                }
            } catch (Exception ex) {
                LOG.warn("Failed relaying Meta chat event to Discord channel {}: {}", targetChannelId, ex.getMessage());
            }
        }
        return relayed;
    }

    private boolean sendToTarget(Guild guild, String targetChannelId, String content) {
        MessageChannel directTarget = resolveDirectMessageTarget(guild, targetChannelId);
        if (directTarget != null) {
            directTarget.sendMessage(content).complete();
            return true;
        }

        ForumChannel forumTarget = guild.getForumChannelById(targetChannelId);
        if (forumTarget != null) {
            forumTarget.createForumPost(buildForumPostTitle(), MessageCreateData.fromContent(content)).complete();
            return true;
        }

        return false;
    }

    private String formatEventMessage(MetaInboundChatEvent event) {
        String platformLabel = "instagram".equalsIgnoreCase(event.platform()) ? "Instagram" : "Messenger";
        String pageId = safeTrim(event.pageOrAccountId());
        if (pageId.isBlank()) {
            pageId = "(unknown)";
        }
        return """
                **New %s chat received**
                Sender: `%s`
                Page/Account: `%s`
                Message: %s
                """.formatted(
                platformLabel,
                safeTrim(event.senderId()).isBlank() ? "(unknown)" : safeTrim(event.senderId()),
                pageId,
                sanitizePreview(event.previewText())
        );
    }

    private String sanitizePreview(String preview) {
        String value = safeTrim(preview);
        if (value.isBlank()) {
            return "`(no preview)`";
        }
        if (value.length() > 500) {
            value = value.substring(0, 500) + "...";
        }
        return value.replace("@everyone", "@\u200beveryone").replace("@here", "@\u200bhere");
    }

    private String buildForumPostTitle() {
        String title = "Meta Chat Alert | " + ZonedDateTime.now().format(TITLE_TIME_FORMATTER);
        if (title.length() <= FORUM_POST_TITLE_MAX_LENGTH) {
            return title;
        }
        return title.substring(0, FORUM_POST_TITLE_MAX_LENGTH);
    }

    private MessageChannel resolveDirectMessageTarget(Guild guild, String targetId) {
        TextChannel textChannel = guild.getTextChannelById(targetId);
        if (textChannel != null) {
            return textChannel;
        }

        NewsChannel newsChannel = guild.getNewsChannelById(targetId);
        if (newsChannel != null) {
            return newsChannel;
        }

        ThreadChannel threadChannel = guild.getThreadChannelById(targetId);
        if (threadChannel != null) {
            return threadChannel;
        }

        return null;
    }

    private Guild resolveGuild() {
        if (!defaultGuildId.isBlank()) {
            Guild guild = jda.getGuildById(defaultGuildId);
            if (guild != null) {
                return guild;
            }
        }
        return jda.getGuilds().isEmpty() ? null : jda.getGuilds().get(0);
    }

    private String resolveTargetChannelId() {
        return fallbackTargetChannelId;
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            sb.append(Character.forDigit((value >> 4) & 0xF, 16));
            sb.append(Character.forDigit(value & 0xF, 16));
        }
        return sb.toString();
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
