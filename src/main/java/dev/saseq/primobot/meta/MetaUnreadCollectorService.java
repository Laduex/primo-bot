package dev.saseq.primobot.meta;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class MetaUnreadCollectorService {
    private final MetaUnreadApiClient apiClient;

    public MetaUnreadCollectorService(MetaUnreadApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public MetaUnreadSnapshot collectUnread() {
        List<MetaPageAccess> pages = apiClient.listPages();
        List<MetaUnreadConversation> allConversations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (MetaPageAccess page : pages) {
            allConversations.addAll(collectPlatformUnread(page, "facebook", warnings));
            allConversations.addAll(collectPlatformUnread(page, "instagram", warnings));
        }

        allConversations.sort(Comparator
                .comparing(MetaUnreadConversation::updatedTime,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .reversed()
                .thenComparing(MetaUnreadConversation::pageName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(MetaUnreadConversation::conversationId, String.CASE_INSENSITIVE_ORDER));

        int unreadMessages = allConversations.stream()
                .mapToInt(conversation -> Math.max(0, conversation.unreadCount()))
                .sum();

        return new MetaUnreadSnapshot(
                pages.size(),
                allConversations.size(),
                unreadMessages,
                List.copyOf(allConversations),
                List.copyOf(warnings)
        );
    }

    private List<MetaUnreadConversation> collectPlatformUnread(MetaPageAccess page, String platform, List<String> warnings) {
        try {
            return apiClient.listUnreadConversations(page, platform);
        } catch (MetaGraphApiException ex) {
            warnings.add("%s unread check failed for `%s`: %s"
                    .formatted(displayPlatform(platform), page.pageName(), ex.briefMessage()));
        } catch (RuntimeException ex) {
            warnings.add("%s unread check failed for `%s`: %s"
                    .formatted(displayPlatform(platform), page.pageName(), briefRuntimeMessage(ex)));
        }
        return List.of();
    }

    private String displayPlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            return "Meta";
        }
        return switch (platform.trim().toLowerCase()) {
            case "facebook", "messenger" -> "Facebook";
            case "instagram" -> "Instagram";
            default -> platform;
        };
    }

    private String briefRuntimeMessage(RuntimeException ex) {
        String message = ex == null ? "" : ex.getMessage();
        if (message == null || message.isBlank()) {
            return "Unknown error";
        }
        return message.length() > 220 ? message.substring(0, 217) + "..." : message;
    }
}
