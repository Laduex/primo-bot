package dev.saseq.primobot.meta;

import dev.saseq.primobot.util.DiscordMessageUtils;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.concrete.NewsChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class MetaUnreadExecutorService {
    private static final int DISCORD_MESSAGE_MAX_LENGTH = 2000;
    private static final int FORUM_POST_TITLE_MAX_LENGTH = 100;
    private static final DateTimeFormatter FORUM_TITLE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final MetaUnreadCollectorService collectorService;
    private final MetaUnreadMessageBuilder messageBuilder;

    public MetaUnreadExecutorService(MetaUnreadCollectorService collectorService,
                                     MetaUnreadMessageBuilder messageBuilder) {
        this.collectorService = collectorService;
        this.messageBuilder = messageBuilder;
    }

    public DispatchResult execute(Guild guild, MetaUnreadConfig config) {
        if (guild == null) {
            return new DispatchResult(DispatchStatus.GUILD_NOT_FOUND, "", "No guild available", 0, 0, 0, 0);
        }

        String targetId = safeTrim(config == null ? "" : config.getTargetChannelId());
        if (targetId.isBlank()) {
            return new DispatchResult(DispatchStatus.TARGET_NOT_CONFIGURED, "", "No target channel configured", 0, 0, 0, 0);
        }

        MetaUnreadSnapshot snapshot;
        try {
            snapshot = collectorService.collectUnread();
        } catch (Exception ex) {
            String message = ex.getMessage() == null || ex.getMessage().isBlank() ? "Unread check failed" : ex.getMessage();
            return new DispatchResult(DispatchStatus.FETCH_FAILED, targetId, message, 0, 0, 0, 0);
        }

        if (snapshot.unreadThreadCount() <= 0) {
            return new DispatchResult(
                    DispatchStatus.NO_UNREAD,
                    targetId,
                    "No unread conversations",
                    snapshot.pagesScanned(),
                    snapshot.unreadThreadCount(),
                    snapshot.unreadMessageCount(),
                    snapshot.warnings().size());
        }

        String content = messageBuilder.buildDigest(snapshot);
        List<String> chunks = DiscordMessageUtils.chunkMessage(content, DISCORD_MESSAGE_MAX_LENGTH);

        try {
            MessageChannel directTarget = resolveDirectMessageTarget(guild, targetId);
            if (directTarget != null) {
                for (String chunk : chunks) {
                    directTarget.sendMessage(chunk).complete();
                }
                return new DispatchResult(
                        DispatchStatus.SENT,
                        targetId,
                        "Sent",
                        snapshot.pagesScanned(),
                        snapshot.unreadThreadCount(),
                        snapshot.unreadMessageCount(),
                        snapshot.warnings().size());
            }

            ForumChannel forumTarget = guild.getForumChannelById(targetId);
            if (forumTarget != null) {
                String title = buildForumPostTitle();
                ThreadChannel postThread = forumTarget
                        .createForumPost(title, MessageCreateData.fromContent(chunks.get(0)))
                        .complete()
                        .getThreadChannel();
                for (int index = 1; index < chunks.size(); index++) {
                    postThread.sendMessage(chunks.get(index)).complete();
                }
                return new DispatchResult(
                        DispatchStatus.SENT,
                        targetId,
                        "Sent",
                        snapshot.pagesScanned(),
                        snapshot.unreadThreadCount(),
                        snapshot.unreadMessageCount(),
                        snapshot.warnings().size());
            }

            GuildChannel existingChannel = guild.getGuildChannelById(targetId);
            if (existingChannel != null) {
                return new DispatchResult(
                        DispatchStatus.TARGET_UNSUPPORTED,
                        targetId,
                        "Configured target channel type is not supported for digest delivery",
                        snapshot.pagesScanned(),
                        snapshot.unreadThreadCount(),
                        snapshot.unreadMessageCount(),
                        snapshot.warnings().size());
            }

            return new DispatchResult(
                    DispatchStatus.TARGET_NOT_FOUND,
                    targetId,
                    "Target channel was not found",
                    snapshot.pagesScanned(),
                    snapshot.unreadThreadCount(),
                    snapshot.unreadMessageCount(),
                    snapshot.warnings().size());
        } catch (Exception ex) {
            String message = ex.getMessage() == null || ex.getMessage().isBlank() ? "Failed sending digest" : ex.getMessage();
            return new DispatchResult(
                    DispatchStatus.SEND_FAILED,
                    targetId,
                    message,
                    snapshot.pagesScanned(),
                    snapshot.unreadThreadCount(),
                    snapshot.unreadMessageCount(),
                    snapshot.warnings().size());
        }
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

    private String buildForumPostTitle() {
        String title = "Meta Unread Digest | " + ZonedDateTime.now().format(FORUM_TITLE_TIME_FORMATTER);
        if (title.length() <= FORUM_POST_TITLE_MAX_LENGTH) {
            return title;
        }
        return title.substring(0, FORUM_POST_TITLE_MAX_LENGTH);
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    public enum DispatchStatus {
        SENT,
        NO_UNREAD,
        GUILD_NOT_FOUND,
        TARGET_NOT_CONFIGURED,
        TARGET_NOT_FOUND,
        TARGET_UNSUPPORTED,
        FETCH_FAILED,
        SEND_FAILED
    }

    public record DispatchResult(DispatchStatus status,
                                 String targetChannelId,
                                 String message,
                                 int pagesScanned,
                                 int unreadThreads,
                                 int unreadMessages,
                                 int warningCount) {
        public boolean sent() {
            return status == DispatchStatus.SENT;
        }
    }
}
