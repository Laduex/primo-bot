package dev.saseq.primobot.meta;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Component
public class MetaUnreadMessageBuilder {
    private static final DateTimeFormatter RUN_AT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");
    private static final DateTimeFormatter UPDATED_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
    private static final int SNIPPET_MAX = 120;
    private static final int MAX_CONVERSATIONS_TO_SHOW = 15;
    private static final String MESSENGER_PLATFORM = "messenger";
    private static final String INSTAGRAM_PLATFORM = "instagram";
    private static final String AUTO_REPLY_MARKER = "we've received your message and will get back to you shortly";

    public String buildDigest(MetaUnreadSnapshot snapshot) {
        return buildDigest(snapshot, ZonedDateTime.now());
    }

    String buildDigest(MetaUnreadSnapshot snapshot, ZonedDateTime runAt) {
        MetaUnreadSnapshot safeSnapshot = snapshot == null
                ? new MetaUnreadSnapshot(0, 0, 0, List.of(), List.of())
                : snapshot;
        List<MetaUnreadConversation> conversations = safeConversations(safeSnapshot.conversations());

        StringBuilder content = new StringBuilder();
        content.append("**Meta Unread Digest**\n");
        content.append("Run At: `").append(runAt.format(RUN_AT_FORMATTER)).append("`\n");
        content.append("Pages Scanned: `").append(safeSnapshot.pagesScanned()).append("`\n");
        content.append("Unread Conversations: `").append(safeSnapshot.unreadThreadCount()).append("`\n");
        content.append("Unread Messages: `").append(safeSnapshot.unreadMessageCount()).append("`\n\n");

        appendPlatformSummary(content, conversations);
        appendPageSummary(content, conversations);

        content.append("\n**Recent Unread Conversations**\n");
        appendConversationSections(content, conversations, runAt);

        if (safeSnapshot.warnings() != null && !safeSnapshot.warnings().isEmpty()) {
            content.append("\n**Instagram Warnings**\n");
            for (String warning : safeSnapshot.warnings()) {
                content.append("- ").append(sanitizeLine(warning, 300)).append("\n");
            }
        }

        return content.toString().trim();
    }

    private void appendPlatformSummary(StringBuilder content,
                                       List<MetaUnreadConversation> conversations) {
        int messengerThreads = 0;
        int messengerMessages = 0;
        int instagramThreads = 0;
        int instagramMessages = 0;

        for (MetaUnreadConversation conversation : conversations) {
            int unread = Math.max(0, conversation.unreadCount());
            String platform = normalizePlatform(conversation.platform());
            if (INSTAGRAM_PLATFORM.equals(platform)) {
                instagramThreads += 1;
                instagramMessages += unread;
                continue;
            }
            messengerThreads += 1;
            messengerMessages += unread;
        }

        content.append("**By Platform**\n");
        content.append("- Messenger: threads `").append(messengerThreads)
                .append("`, messages `").append(messengerMessages).append("`\n");
        content.append("- Instagram: threads `").append(instagramThreads)
                .append("`, messages `").append(instagramMessages).append("`\n");
    }

    private void appendPageSummary(StringBuilder content,
                                   List<MetaUnreadConversation> conversations) {
        content.append("\n**By Page**\n");
        if (conversations.isEmpty()) {
            content.append("- (none)\n");
            return;
        }

        Map<String, PageSummary> grouped = new LinkedHashMap<>();
        for (MetaUnreadConversation conversation : conversations) {
            String key = groupKey(conversation);
            PageSummary summary = grouped.computeIfAbsent(
                    key,
                    unused -> new PageSummary(
                            sanitizeLine(conversation.pageName(), 80),
                            normalizePlatform(conversation.platform())));
            summary.threadCount += 1;
            summary.messageCount += Math.max(0, conversation.unreadCount());
        }

        List<PageSummary> ordered = new ArrayList<>(grouped.values());
        ordered.sort(Comparator
                .comparingInt(PageSummary::messageCount).reversed()
                .thenComparing(PageSummary::pageName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(PageSummary::platform, String.CASE_INSENSITIVE_ORDER));

        for (PageSummary summary : ordered) {
            content.append("- ")
                    .append(summary.pageName)
                    .append(" (")
                    .append(summary.platform)
                    .append("): threads `")
                    .append(summary.threadCount)
                    .append("`, messages `")
                    .append(summary.messageCount)
                    .append("`\n");
        }
    }

    private void appendConversationSections(StringBuilder content,
                                            List<MetaUnreadConversation> conversations,
                                            ZonedDateTime runAt) {
        if (conversations == null || conversations.isEmpty()) {
            content.append("- (none)\n");
            return;
        }

        int displayCount = Math.min(MAX_CONVERSATIONS_TO_SHOW, conversations.size());
        for (int index = 0; index < displayCount; index++) {
            MetaUnreadConversation conversation = conversations.get(index);
            content.append(index + 1)
                    .append(".\n")
                    .append("   Name: ")
                    .append(sanitizeLine(conversation.senderName(), 80))
                    .append("\n")
                    .append("   Platform: ")
                    .append(displayPlatform(normalizePlatform(conversation.platform())))
                    .append("\n")
                    .append("   Snippet: ")
                    .append(normalizeSnippet(conversation.snippet()))
                    .append("\n")
                    .append("   Time Received: `")
                    .append(sanitizeLine(conversation.updatedTime(), 40))
                    .append("`\n")
                    .append("   Days Ago: `")
                    .append(daysAgoLabel(conversation.updatedTime(), runAt))
                    .append("`\n");
        }

        int hiddenCount = conversations.size() - displayCount;
        if (hiddenCount > 0) {
            content.append("- ...and `")
                    .append(hiddenCount)
                    .append("` more unread conversation(s).\n");
        }
    }

    private String groupKey(MetaUnreadConversation conversation) {
        String pageName = conversation == null ? "Unknown Page" : sanitizeLine(conversation.pageName(), 80);
        String platform = conversation == null
                ? "unknown"
                : normalizePlatform(conversation.platform());
        return "**" + pageName + "** (" + platform + ")";
    }

    private String normalizePlatform(String platform) {
        String normalized = sanitizeLine(platform, 20).toLowerCase(Locale.ENGLISH);
        return switch (normalized) {
            case "facebook", "messenger" -> MESSENGER_PLATFORM;
            case "instagram" -> INSTAGRAM_PLATFORM;
            default -> normalized;
        };
    }

    private String normalizeSnippet(String snippet) {
        String normalized = sanitizeLine(snippet, SNIPPET_MAX);
        if (normalized.equals("(no text)")) {
            return normalized;
        }

        String lower = normalized.toLowerCase(Locale.ENGLISH);
        if (lower.contains(AUTO_REPLY_MARKER)) {
            return "(auto-reply acknowledgement)";
        }
        return normalized;
    }

    private String daysAgoLabel(String updatedTime, ZonedDateTime runAt) {
        String normalized = sanitizeLine(updatedTime, 40);
        if (normalized.equals("(no text)")) {
            return normalized;
        }

        try {
            OffsetDateTime updated = OffsetDateTime.parse(normalized, UPDATED_TIME_FORMATTER);
            Duration age = Duration.between(updated.toInstant(), runAt.toInstant());
            if (age.isNegative()) {
                return "0m";
            }
            return compactAge(age);
        } catch (Exception ignored) {
            return "(unknown)";
        }
    }

    private String displayPlatform(String platform) {
        return switch (platform) {
            case MESSENGER_PLATFORM -> "Messenger (account)";
            case INSTAGRAM_PLATFORM -> "Instagram";
            default -> sanitizeLine(platform, 20);
        };
    }

    private String compactAge(Duration duration) {
        long days = duration.toDays();
        if (days > 0) {
            return days + "d";
        }

        long hours = duration.toHours();
        if (hours > 0) {
            return hours + "h";
        }

        long minutes = Math.max(1, duration.toMinutes());
        return minutes + "m";
    }

    private List<MetaUnreadConversation> safeConversations(List<MetaUnreadConversation> conversations) {
        if (conversations == null || conversations.isEmpty()) {
            return List.of();
        }
        return conversations.stream()
                .filter(Objects::nonNull)
                .toList();
    }

    private String sanitizeLine(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            return "(no text)";
        }
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)).trim() + "...";
    }

    private static final class PageSummary {
        private final String pageName;
        private final String platform;
        private int threadCount;
        private int messageCount;

        private PageSummary(String pageName, String platform) {
            this.pageName = pageName;
            this.platform = platform;
        }

        public String pageName() {
            return pageName;
        }

        public String platform() {
            return platform;
        }

        public int messageCount() {
            return messageCount;
        }
    }
}
